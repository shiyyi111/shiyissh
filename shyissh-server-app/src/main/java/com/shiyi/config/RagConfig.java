package com.shiyi.config;

import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 知识检索（RAG）的装配入口。
 * <p>
 * 这里只做一件事：把 {@code app.rag.*} 绑定成 {@link RagProperties} bean。
 * 具体实现（Qdrant 客户端、embedding 客户端、混合检索器）都带
 * {@code @ConditionalOnProperty} 或"配置缺失就退化为空实现"的分支，
 * 所以本类不需要感知开关状态——开关在读配置的组件里判断，
 * 这样"关掉向量"只是少几个 bean，不会让应用起不来。
 *
 * @author shiyi
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
}