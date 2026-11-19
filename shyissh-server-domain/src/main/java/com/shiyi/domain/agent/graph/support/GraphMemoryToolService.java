package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import com.shiyi.domain.agent.service.wiki.WikiService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图内置工具（记忆 / 知识库）的执行实现。
 * <p>
 * 为什么单独抽出这个类：这类工具是"图自己提供的"，既不在 SSH 里，也不在 MCP/skills 注册表里。
 * 挂载点在 {@code LlmNode}（模型看得见哪些工具），执行点在 {@code ToolNode}（真正跑工具的地方）。
 * 如果两处各写一份实现，就会出现"模型能看见工具、调用后却拿到未知工具"这种接不上线的故障 ——
 * 所以这里只留一份实现，两处都委托它。
 *
 * @author shiyi
 */
@Service
public class GraphMemoryToolService {

    /** recallMemory 单次返回的记忆条数：工具返回值也占上下文，5 条足够支撑一次判断。 */
    private static final int MEMORY_RECALL_LIMIT = 5;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /** wiki 读写：{@code saveWiki} 落 agent_wiki；落库后由 {@code VectorIndexWorker} 异步补向量，检索侧同一集合。 */
    @Resource
    private WikiService wikiService;

    /**
     * 检索走混合链路（权威知识 + wiki + 向量，RRF 融合）。
     * 允许为 null：单元测试里手工 new 这个类时不装配 Spring，此时退回 {@link WikiService} 的老路径。
     */
    @Resource
    private KnowledgeRetriever knowledgeRetriever;

    /** 按需检索长期记忆；kind 给了就按类型再过滤一次。 */
    public String recallMemory(String userId, String hostKey, RecallMemoryRequest request) {
        if (longTermMemoryService == null) {
            return "（记忆服务未装配）";
        }
        String query = request == null || request.query() == null ? "" : request.query();
        List<LongTermMemoryEntity> memories = longTermMemoryService.queryRelevantMemories(
                userId, hostKey, query, MEMORY_RECALL_LIMIT);
        return renderMemories(memories, request == null ? null : request.kind());
    }

    /** 把模型总结出的一条结论写入长期记忆。 */
    public String saveMemory(String userId, String sessionId, String hostKey, SaveMemoryRequest request) {
        if (longTermMemoryService == null) {
            return "（记忆服务未装配）";
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            return "（写入长期记忆失败：内容为空）";
        }
        longTermMemoryService.saveUserMemory(userId, sessionId, request.content(), request.kind(), hostKey);
        return "（已写入长期记忆）";
    }

    /**
     * 检索知识库。
     * <p>
     * 有 {@link KnowledgeRetriever} 时走混合检索：md 权威知识与 wiki 同在一个 Qdrant 集合，
     * dense + sparse 两路由向量库做 RRF 融合（md 用 payload 正文、wiki 回表取正文并按归属过滤）。
     * 没有时（单测环境）退回 {@link WikiService}，只查 wiki。
     * 这个降级是有意的：知识检索是辅助能力，装配不齐时宁可少一路，也不能让工具直接报错。
     */
    public String wikiSearch(String userId, String hostKey, WikiSearchRequest request) {
        String query = request == null ? "" : request.query();
        int limit = request == null || request.limit() == null
                ? (knowledgeRetriever != null ? 5 : WikiService.DEFAULT_LIMIT)
                : request.limit();
        if (knowledgeRetriever != null) {
            try {
                KnowledgeRetriever.Result result = knowledgeRetriever.retrieve(userId, hostKey, query, limit);
                return renderKnowledge(result);
            } catch (Exception e) {
                // 检索层自己已经 fail-open，这里再兜一层：工具永远返回文本，不把异常抛进图
                return "（知识库检索失败：" + e.getMessage() + "）";
            }
        }
        if (wikiService == null) {
            return "（知识库服务未装配）";
        }
        return wikiService.render(wikiService.search(userId, hostKey, query, limit));
    }

    /** 把一次排查的结论沉淀进知识库。 */
    public String saveWiki(String userId, String hostKey, SaveWikiRequest request) {
        if (wikiService == null) {
            return "（知识库服务未装配）";
        }
        if (request == null) {
            return "（写入知识库失败：参数为空）";
        }
        return wikiService.remember(userId, hostKey, request.title(), request.content(), request.tags())
                ? "（已写入知识库）"
                : "（写入知识库失败，可能是标题或正文为空）";
    }

    /**
     * 渲染混合检索结果。
     * <p>
     * 把来源和类型一起给模型看，是为了让它区分"这是人工维护的权威手册"和"这是别人排查后沉淀的经验"：
     * 前者可以直接引用，后者需要结合当前机器状态验证。不给来源的话，模型会把两者当成同等可信。
     * 命令块单独一行，是因为模型经常直接把命令抄出来执行，给它一段可复制的原文比让它自己拼更安全。
     */
    private String renderKnowledge(KnowledgeRetriever.Result result) {
        if (result == null || result.isEmpty()) {
            return "（知识库里没有找到相关条目。请把这次结论当作新知识，排查完成后用 saveWiki 沉淀。）";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("知识库检索结果（引用时请写 [编号]，这些是历史经验，不是当前机器的实时状态）：\n");
        int index = 1;
        for (KnowledgeRetriever.Hit hit : result.hits()) {
            builder.append('[').append(index++).append("] ").append(nullSafe(hit.title()));
            String meta = buildMeta(hit);
            if (!meta.isEmpty()) {
                builder.append('（').append(meta).append('）');
            }
            builder.append('\n').append("    ").append(nullSafe(hit.snippet())).append('\n');
            if (hit.commands() != null && !hit.commands().isBlank()) {
                builder.append("    命令: ").append(hit.commands().replace("\n", "；")).append('\n');
            }
        }
        return builder.toString();
    }

    private String buildMeta(KnowledgeRetriever.Hit hit) {
        StringBuilder meta = new StringBuilder();
        if (hit.kind() != null && !hit.kind().isBlank()) {
            meta.append("类型：").append(hit.kind());
        }
        if (hit.tags() != null && !hit.tags().isBlank()) {
            if (meta.length() > 0) {
                meta.append("，");
            }
            meta.append("标签：").append(hit.tags());
        }
        if (hit.source() != null && !hit.source().isBlank()) {
            if (meta.length() > 0) {
                meta.append("，");
            }
            meta.append("来源：").append(hit.source());
        }
        return meta.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 把召回结果渲染成工具返回值。
     * <p>
     * 有 kind 时按类型再过滤一次：召回本身是多因子混合排序（关键词、时间、命中次数），
     * 模型明确说"我只想看排查案例"时不能把环境事实也塞回去，否则等于没过滤。
     */
    private String renderMemories(List<LongTermMemoryEntity> memories, String kind) {
        if (memories == null || memories.isEmpty()) {
            return "（没有找到相关记忆）";
        }

        String filter = (kind == null || kind.isBlank()) ? "" : resolveKindFilter(kind);
        StringBuilder builder = new StringBuilder();
        int matched = 0;
        for (LongTermMemoryEntity memory : memories) {
            if (!filter.isEmpty() && !filter.equals(memory.getMemoryType())) {
                continue;
            }
            builder.append("- [").append(memory.getMemoryType()).append("] ")
                    .append(memory.getContent()).append('\n');
            matched++;
        }
        if (matched == 0) {
            return "（没有找到 " + kind + " 类型的记忆）";
        }
        return "找到 " + matched + " 条相关记忆：\n" + builder;
    }

    /**
     * 工具入参的 kind 是模型自由填写的字符串，这里按关键词归一到记忆类型常量，
     * 与 {@code LongTermMemoryService#resolveMemoryType} 用同一套匹配规则，保证查询侧与写入侧一致。
     */
    private String resolveKindFilter(String kind) {
        String key = kind.trim().toUpperCase();
        if (key.contains("PREFERENCE")) {
            return "USER_PREFERENCE";
        }
        if (key.contains("SOFTWARE") || key.contains("VERSION")) {
            return "SOFTWARE_FACT";
        }
        if (key.contains("CASE") || key.contains("TROUBLE") || key.contains("FAIL")) {
            return "TROUBLESHOOTING_CASE";
        }
        return "ENVIRONMENT_FACT";
    }
}