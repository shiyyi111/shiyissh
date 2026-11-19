-- ============================================================================
-- AI 运维助手 · SSH 连接初始测试数据（我自己的服务器）
--
-- 前置：`ssh_connection` / `ssh_connection_config` 两张表已于 2026-10-29 建好，
--      结构以数据库现有 DDL 为准（本文件不再建表，只灌数据，可重复执行）。
-- 库：shiyissh    连接：127.0.0.1:3306 / root
-- 执行：mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 shiyissh < shyissh-ssh-connection.sql
--
-- 与教程示例的差异：
--   教程：2 条（腾讯云 198.51.100.20 / ubuntu、198.51.100.21 / root）
--   本项目：只有我自己的机器 → 192.0.2.10 / root / 22（阿里云 ECS，OpenSSH 7.4）
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 一、清理旧测试数据（幂等）
-- ---------------------------------------------------------------------------
DELETE FROM `ssh_connection_config`
WHERE `connection_id` IN ('3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d',
                          '9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d');
DELETE FROM `ssh_connection`
WHERE `connection_id` IN ('3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d',
                          '9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d');

-- ---------------------------------------------------------------------------
-- 二、SSH 连接（我自己的机器）
--    password 先留空：当前项目还没实现 SSH 密码加密工具，
--    后续实现后按下方「三」的模板回填密文；本地临时调试可用 encrypted=0 存明文。
--    status：0=未连接 1=已连接 2=已断开 3=连接失败（新建即 0）
-- ---------------------------------------------------------------------------
INSERT INTO `ssh_connection`
(`id`, `connection_id`, `connection_name`, `host`, `port`, `username`, `auth_type`,
 `password`, `private_key`, `encrypted`, `status`, `user_id`, `deleted`)
VALUES (1, '3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d', '阿里云ECS-192.0.2.10', '192.0.2.10', 22, 'root', 1,
        NULL, NULL, 1, 0, 'default', 0),
       (2, '9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d', '本机SSH-调试用', '127.0.0.1', 22, 'root', 1,
        NULL, NULL, 1, 0, 'default', 0);

-- ---------------------------------------------------------------------------
-- 三、SSH 高级配置（对应 connection_id）
--    connect_timeout 30s / keepalive 60s / 不压缩 / 严格校验 host key
--    startup_command 留空：连接成功后不自动执行命令
-- ---------------------------------------------------------------------------
INSERT INTO `ssh_connection_config`
(`id`, `connection_id`, `connect_timeout`, `keepalive_interval`, `startup_command`,
 `compression`, `strict_host_key_check`, `known_hosts`)
VALUES (1, '3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d', 30, 60, NULL, 0, 1, NULL),
       (2, '9b8a7c6d5e4f3a2b1c0d9e8f7a6b5c4d', 30, 60, NULL, 0, 1, NULL);

-- ---------------------------------------------------------------------------
-- 四、回填密码模板（等你拿到加解密工具后二选一）
-- ---------------------------------------------------------------------------
-- 4.1 密文方式（推荐，正式环境）
-- UPDATE `ssh_connection` SET `password` = '<AES 加密后的密文>', `encrypted` = 1
--  WHERE `connection_id` = '3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d';
--
-- 4.2 明文方式（仅本地调试，务必 encrypted=0）
-- UPDATE `ssh_connection` SET `password` = '你的真实密码', `encrypted` = 0
--  WHERE `connection_id` = '3f7c1d9a5b2e4c6f8a0d1e2f3a4b5c6d';
--
-- 4.3 在密码就绪前，想验证 SSH 连通性走已有的手动测试：
--   mvn -q -pl shyissh-server-app -am test-compile
--   mvn -q -pl shyissh-server-app test -Dtest=SshSessionPortManualTest \
--       -Dssh.host=192.0.2.10 -Dssh.port=22 -Dssh.username=root -Dssh.password=你的密码

-- ---------------------------------------------------------------------------
-- 五、校验
-- ---------------------------------------------------------------------------
SELECT c.id, c.connection_id, c.connection_name, c.host, c.port, c.username,
       c.auth_type, c.encrypted, c.status, c.user_id,
       cfg.connect_timeout, cfg.keepalive_interval, cfg.compression, cfg.strict_host_key_check
FROM `ssh_connection` c
         LEFT JOIN `ssh_connection_config` cfg ON cfg.connection_id = c.connection_id
WHERE c.deleted = 0
ORDER BY c.id;