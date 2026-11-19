-- =====================================================================
-- 长期记忆表 v2：增加作用域（USER / HOST / SESSION）与重要度、使用时间、过期时间
-- 数据库：shiyissh    表：long_term_memory
-- 目标：记忆按"用户 + 主机 + 类型 + 去重键"唯一，避免同一用户在多台机器上的
--       同名记忆互相覆盖；同时为召回打分提供 importance / last_used_at / expires_at。
-- =====================================================================

-- 1) 加列
ALTER TABLE long_term_memory
    ADD COLUMN `scope`        varchar(16)   NOT NULL DEFAULT 'USER' COMMENT '记忆作用域: USER/HOST/SESSION' AFTER user_id,
    ADD COLUMN host_key       varchar(64)   NULL                   COMMENT '主机标识(scope=HOST 时有效)' AFTER `scope`,
    ADD COLUMN importance     decimal(3,2)  NOT NULL DEFAULT 0.50  COMMENT '重要度 0~1' AFTER confidence,
    ADD COLUMN last_used_at   timestamp     NULL                   COMMENT '最近一次被召回的时间' AFTER hit_count,
    ADD COLUMN expires_at     timestamp     NULL                   COMMENT '过期时间, NULL 表示不过期' AFTER last_used_at,
    ADD INDEX idx_scope_host (`scope`, host_key),
    ADD INDEX idx_user_used  (user_id, last_used_at);

-- 2) 唯一键改造：加入 scope 与 host_key
--    原唯一键 (user_id, memory_type, memory_key) 不含主机维度 → 同一用户在
--    两台机器上的同名记忆会互相覆盖。
--    host_key 必须 NOT NULL DEFAULT ''：MySQL 唯一索引里 NULL 互不冲突，
--    留着 NULL 会让 USER 级记忆重复插入。
ALTER TABLE long_term_memory
    MODIFY COLUMN host_key varchar(64) NOT NULL DEFAULT '' COMMENT '主机标识(scope=HOST 时有效, 其它为空串)',
    DROP INDEX uk_user_type_key,
    ADD UNIQUE KEY uk_scope_key (user_id, `scope`, host_key, memory_type, memory_key);