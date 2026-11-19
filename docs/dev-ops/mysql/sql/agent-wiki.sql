-- 运维知识库（wiki） v1
-- 目的：把"人写的运维经验"和"Agent 沉淀的排查结论"放在一张可检索的表里，
-- 让模型在排查前先查经验，而不是每次都从零开始盲试。
--
-- 为什么用 MySQL FULLTEXT + ngram 而不是向量库：
--   1. 当前部署形态是单机 MySQL，不引入额外中间件就没有运维成本；向量库（Milvus/Qdrant/pgvector）
--      要额外进程、额外备份策略，本项目规模（经验条目几千条以内）用不上；
--   2. 运维问答的关键词特征明显（"磁盘满了""nginx 502""端口占用"），ngram 分词后的 BM25 相关性
--      已经够用，且结果可解释（能说清命中了哪些词）；
--   3. 真需要语义检索时，只要把 WikiService.search 换一个实现，接口不变。
--
-- ngram 说明：MySQL 默认分词器按空格切词，中文整句会被当成一个 token，检索必失效；
-- WITH PARSER ngram 按 2 字滑窗切分，中文短句可召回。ngram_token_size 默认 2。

CREATE TABLE IF NOT EXISTS `agent_wiki`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属用户，空串表示全局共享',
    `host_key`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属主机（connectionId），空串表示不限主机',
    `title`      VARCHAR(255) NOT NULL COMMENT '条目标题，检索结果直接展示',
    `content`    TEXT         NOT NULL COMMENT '条目正文（Markdown 或纯文本）',
    `tags`       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔标签，便于二次过滤',
    `source`     VARCHAR(32)  NOT NULL DEFAULT 'manual' COMMENT '来源：manual/agent/conversation',
    `hit_count`  INT          NOT NULL DEFAULT 0 COMMENT '被检索命中次数，用于排序加权',
    `created_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_host` (`user_id`, `host_key`),
    FULLTEXT KEY `ft_wiki` (`title`, `content`) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='运维知识库（FULLTEXT ngram 检索）';