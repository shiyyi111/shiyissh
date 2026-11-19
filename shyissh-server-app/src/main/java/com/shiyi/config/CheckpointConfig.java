package com.shiyi.config;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 图检查点存储装配。
 * <p>
 * 检查点解决的是"一次执行要跨两次 HTTP 请求"的问题：图跑到 [approval] 前停住，
 * 把那一刻的 state（含模型生成的工具调用）存下来，人工批准后再从同一处恢复，
 * 保证"人看到的命令"和"真正执行的命令"是同一条。
 * <p>
 * 默认用 {@link MysqlSaver}：检查点落在业务库，服务重启、滚动发布都不影响待审批的执行。
 * 为什么不用 {@link MemorySaver}：审批常常不是几分钟的事（人可能过一会儿才看手机），
 * 而内存检查点在进程重启后就没了，用户点"批准"时只会得到一句"无法从断点继续"。
 * <p>
 * 表结构由 {@link MysqlSaver} 自己按 {@link CreateOption#CREATE_IF_NOT_EXISTS} 建，
 * 首次启动会在库里创建检查点相关表；生产环境如果不想让应用建表，
 * 把 {@code app.agent.checkpoint.create=create_none} 打开并预先执行建表 SQL 即可。
 * <p>
 * 状态里有 Spring AI 的 {@code Message} 与 {@code ToolCall}，必须用
 * {@link SpringAIJacksonStateSerializer} 才能原样往返（普通 Jackson 序列化器会把
 * 它们变成 Map，恢复时模型拿到的历史就不是 {@code Message} 了）。
 * <p>
 * {@code app.agent.checkpoint.store=memory} 可以显式退化成内存实现；
 * 没有这个 bean 时服务照常启动，图会编译成"不带断点"的版本，
 * 审批退回"落单 + 批准后重新发起请求"的兜底路径。
 *
 * @author shiyi
 */
@Configuration
public class CheckpointConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckpointConfig.class);

    /** 检查点存储实现：mysql（默认，跨重启）/ memory（单进程演示用）。 */
    @Value("${app.agent.checkpoint.store:mysql}")
    private String store;

    /** 建表策略：create_if_not_exists（默认）/ create_none（预先手工建表）/ create_or_replace。 */
    @Value("${app.agent.checkpoint.create:create_if_not_exists}")
    private String create;

    /** 每个线程缓存多少个检查点的元数据，调大可减少查库次数。 */
    @Value("${app.agent.checkpoint.max-cached-threads:16}")
    private int maxCachedThreads;

    @Bean
    @ConditionalOnMissingBean(BaseCheckpointSaver.class)
    @ConditionalOnProperty(name = "app.agent.checkpoint.enabled", havingValue = "true", matchIfMissing = true)
    public BaseCheckpointSaver checkpointSaver(ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if ("memory".equalsIgnoreCase(store) || dataSource == null) {
            log.warn("[checkpoint] 使用内存检查点存储（进程重启后待审批的执行会丢失）{}",
                    dataSource == null ? "：容器里没有 DataSource" : "");
            return new MemorySaver();
        }
        try {
            BaseCheckpointSaver saver = MysqlSaver.builder()
                    .dataSource(dataSource)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new))
                    .createOption(createOption())
                    .maxCachedThreads(maxCachedThreads)
                    .build();
            log.info("[checkpoint] 使用 MySQL 检查点存储，建表策略={}，缓存线程数={}", create, maxCachedThreads);
            return saver;
        } catch (Exception e) {
            // 建表失败（权限不足、表已存在但结构不同等）不该让服务起不来：退回内存实现，
            // 审批仍可用，只是重启后会丢——比整个应用启动失败要可接受得多。
            log.error("[checkpoint] MySQL 检查点存储初始化失败，退回内存实现（重启后待审批执行会丢失）：{}", e.getMessage(), e);
            return new MemorySaver();
        }
    }

    private CreateOption createOption() {
        if ("create_none".equalsIgnoreCase(create)) {
            return CreateOption.CREATE_NONE;
        }
        if ("create_or_replace".equalsIgnoreCase(create)) {
            return CreateOption.CREATE_OR_REPLACE;
        }
        return CreateOption.CREATE_IF_NOT_EXISTS;
    }
}