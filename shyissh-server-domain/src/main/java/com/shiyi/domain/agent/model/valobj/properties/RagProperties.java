package com.shiyi.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识检索（RAG）配置：{@code app.rag.*}。
 * <p>
 * 全部带默认值，<b>不配也能启动</b>——这是刻意的：向量库是"增强项"，不是启动依赖。
 * 配错或连不上时按 {@code fail-open} 退回纯关键词检索，问答照常进行，
 * 而不是让整个服务起不来。
 * <p>
 * 换 embedding 模型时要同时改 {@code model} 与 {@code dimensions}，
 * 并让 {@code collection-prefix} 带上维度（如 {@code shiyissh_1024}），
 * 否则新旧向量混在一个集合里，检索结果会莫名其妙地差。
 *
 * @author shiyi
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 总开关；关掉后知识检索退回纯 MySQL FULLTEXT。 */
    private boolean enabled = true;

    private Vector vector = new Vector();

    private Embedding embedding = new Embedding();

    private Retrieval retrieval = new Retrieval();

    private Keyword keyword = new Keyword();

    private Rewrite rewrite = new Rewrite();

    private Rerank rerank = new Rerank();

    private Normalize normalize = new Normalize();

    /** 向量库配置。 */
    @Data
    public static class Vector {

        /** 向量路开关；关掉后只用关键词。 */
        private boolean enabled = true;

        /**
         * 向量库故障时是否降级为纯关键词。
         * true（默认）= 降级后继续回答；false = 直接报错，适合压测时暴露问题。
         */
        private boolean failOpen = true;

        /** 存储实现：当前只有 {@code qdrant}（早期的 {@code mysql-blob} 已随 P1 删除）。 */
        private String store = "qdrant";

        /**
         * 集合名前缀；实际集合名 = 前缀 + "_" + 维度。
         * 带上维度是为了换模型时天然隔离，不必手工清理旧集合。
         */
        private String collectionPrefix = "shiyissh";

        /**
         * Qdrant 地址。
         * 注意：Qdrant 部署在虚拟机里时这里要填虚拟机的 IP（如 192.0.2.10），
         * 不能用 localhost——localhost 在 Windows 上指本机，指不到虚拟机。
         */
        private String host = "localhost";

        /** HTTP 端口（REST，6333）。 */
        private int port = 6333;

        /** gRPC 端口（6334）；用 REST 客户端时不需要。 */
        private int grpcPort = 6334;

        /** API Key；Qdrant 未开启鉴权时留空。 */
        private String apiKey = "";

        /** 单次调用超时（毫秒）。超过就当作向量路失败，走降级。 */
        private int timeoutMs = 3000;
    }

    /** 向量化配置。 */
    @Data
    public static class Embedding {

        private boolean enabled = true;

        /** 模型名；实测网关支持 text-embedding-v4 / text-embedding-v3。 */
        private String model = "text-embedding-v4";

        /** 向量维度；text-embedding-v4 支持 1024/768/512 等，默认 1024。 */
        private int dimensions = 1024;

        /** 批量大小；一次请求塞太多会被网关拒绝，16 是稳妥值。 */
        private int batchSize = 16;

        /** 单次请求超时（毫秒）。 */
        private int timeoutMs = 3000;

        /** 基础地址；留空则复用 chat 模型配置里的 base-url（该地址已含 /v1）。 */
        private String baseUrl = "";

        /** embeddings 路径；与 base-url 拼成完整地址，默认 /embeddings。 */
        private String embeddingsPath = "/embeddings";

        /** API Key；留空则复用 chat 模型配置里的 api-key。 */
        private String apiKey = "";

        /** 单条文本最大字符数；超出部分截断（向量化只取语义主干，不需要全文）。 */
        private int maxChars = 2000;
    }

    /**
     * 关键词检索路配置。
     * <p>
     * 默认走 {@code bm25}：进程内 Lucene + 中文分词 + BM25 相似度。
     * 旧的 {@code sparse}（Qdrant 稀疏向量 + 2-gram）保留为回滚开关——两条路的差异只在
     * "关键词腿怎么算分"，其余（稠密路、融合、重排）完全共用。
     */
    @Data
    public static class Keyword {

        /** 关键词路开关；关掉后只剩稠密向量一路召回。 */
        private boolean enabled = true;

        /** 实现：{@code bm25} = Lucene；{@code sparse} = Qdrant 稀疏向量（回滚用）。 */
        private String backend = "bm25";

        /** BM25 词频饱和系数 k1；常见取值 1.2。 */
        private float k1 = 1.2f;

        /** BM25 文档长度归一系数 b（0~1）；常见取值 0.75。 */
        private float b = 0.75f;

        /** 关键词路召回条数；<=0 时回落到 {@code retrieval.keywordTopK}。 */
        private int topK = 20;

        /** 双路 RRF 融合的平滑常数；<=0 时回落到 {@code retrieval.rrfK}。 */
        private int rrfK = 60;
    }

    /** 检索与融合参数。 */
    @Data
    public static class Retrieval {

        /** 关键词路召回条数。 */
        private int keywordTopK = 20;

        /** 向量路召回条数。 */
        private int vectorTopK = 20;

        /** 融合后最终返回条数。 */
        private int finalTopK = 5;

        /** RRF 平滑常数，见 {@code RrfFusion.DEFAULT_K}。 */
        private int rrfK = 60;

        /** 低于该融合得分的结果丢弃；0 表示不设阈值。 */
        private double minScore = 0.0d;

        /** 单条命中回填给模型的正文截断长度。 */
        private int snippetChars = 300;
    }

    /**
     * LLM 改写配置。
     * <p>
     * 默认关闭：先用规则归一化 + 混合召回跑出基线，等评测显示"表述完全不同"的 badcase
     * 占比超过 30% 再打开。打开后也只做"改写重试"，不在每次请求的主路径上调用。
     */
    @Data
    public static class Rewrite {

        private boolean enabled = false;

        /** 改写模型；留空则复用 chat 模型。 */
        private String model = "";

        /** 单次改写超时（毫秒）；超时直接放弃改写，用原查询检索。 */
        private int timeoutMs = 1500;

        /** 改写输出的 token 上限。 */
        private int maxTokens = 100;

        /** 触发阈值：首轮命中条数少于该值才改写。 */
        private int triggerMinHits = 2;

        /** 改写结果缓存分钟数；同样的问句不必重复调用模型。 */
        private int cacheMinutes = 1440;
    }

    /**
     * 重排（rerank）配置。
     * <p>
     * 混合召回给的是"两路名次的融合分"，它衡量的是"哪条更容易被找到"，
     * 不衡量"哪条真的回答了问题"。rerank 用交叉编码器对 (query, doc) 逐对打分补上这一段，
     * 典型收益在 top3 的排序质量上。
     * <p>
     * <b>有界原则</b>：只对召回的前 {@link #maxDocs} 条、用截断后的正文调一次，
     * 超时/失败一律回退原顺序。它是"排序优化"不是"可用性依赖"，
     * 所以默认 fail-open，且记忆召回路径不得调用（记忆每轮跑两次，会放大成两倍外呼）。
     */
    @Data
    public static class Rerank {

        /** 开关；关掉后退回"召回分数 + 词覆盖率"的本地排序。 */
        private boolean enabled = true;

        /** 模型名；默认为账号可用的 {@code qwen3.7-text-rerank}（{@code gte-rerank} 在部分账号下会 403）。 */
        private String model = "qwen3.7-text-rerank";

        /** 单次请求超时（毫秒）；超时即回退原顺序，不阻塞问答。 */
        private int timeoutMs = 800;

        /** 重排后保留条数；默认与 {@code retrieval.finalTopK} 一致。 */
        private int topN = 5;

        /** 最多送多少条候选去重排；上限来自"延迟 × 条数"的成本，不是模型限制。 */
        private int maxDocs = 20;

        /** 单条候选送进去的正文截断长度；重排只看"是否相关"，不需要整篇。 */
        private int maxChars = 500;

        /** 服务地址；留空用默认的 DashScope 原生端点。 */
        private String baseUrl = "";

        /** API Key；留空则按 embedding → chat → 环境变量的顺序复用同一把 key。 */
        private String apiKey = "";
    }

    /** 查询归一化配置。 */
    @Data
    public static class Normalize {

        /** 是否启用同义词表（{@code ops_synonym}）替换。 */
        private boolean synonymEnabled = true;

        /** 同义词表缓存分钟数；改表后最多等这么久生效。 */
        private int synonymCacheMinutes = 30;

        /** 归一化后最多保留多少个检索词。 */
        private int maxTerms = 8;
    }
}