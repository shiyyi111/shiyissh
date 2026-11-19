# 查询同义词与别名表（Synonyms）

> 用于查询归一化：检索前把用户口语、别名映射为标准词，再进入关键词与向量两路召回。
> 覆盖磁盘、内存、CPU、网络、端口、权限、服务、数据库、缓存、日志、证书、容器等高频场景，共 50 组。

| 别名 | 标准词 | 语言 |
|---|---|---|
| 磁盘满 | No space left on device | zh |
| 磁盘空间不足 | No space left on device | zh |
| 硬盘满了 | No space left on device | zh |
| 磁盘写不进去 | No space left on device | zh |
| disk full | No space left on device | en |
| inode 满 | inode exhausted | zh |
| 小文件太多 | inode exhausted | zh |
| 内存不足 | OOM | zh |
| 内存爆了 | OOM | zh |
| 内存吃满 | OOM | zh |
| out of memory | OOM | en |
| 进程被杀了 | OOM killed | zh |
| 交换分区满 | swap exhausted | zh |
| swap 使用高 | swap exhausted | zh |
| CPU 高 | high CPU usage | zh |
| CPU 打满 | high CPU usage | zh |
| 负载高 | load average high | zh |
| 机器卡 | high load | zh |
| load average 高 | load average high | en |
| 网络不通 | network unreachable | zh |
| 连不上 | connection refused | zh |
| 丢包 | packet loss | zh |
| 延迟高 | high latency | zh |
| 请求超时 | timeout | zh |
| 端口占用 | port already in use | zh |
| 端口被占 | port already in use | zh |
| Address already in use | port already in use | en |
| 端口不通 | port unreachable | zh |
| 权限不够 | permission denied | zh |
| 没有权限 | permission denied | zh |
| 403 forbidden | permission denied | en |
| 文件权限错误 | permission denied | zh |
| 服务起不来 | service failed to start | zh |
| 启动失败 | service failed to start | zh |
| 502 | Bad Gateway | en |
| 504 | Gateway Timeout | en |
| 连接数满 | too many connections | zh |
| 慢查询 | slow query | zh |
| 主从延迟 | replication lag | zh |
| 表锁 | table lock | zh |
| 缓存满 | Redis OOM | zh |
| redis 内存满 | Redis OOM | zh |
| 日志暴涨 | log growth | zh |
| 日志太大 | log growth | zh |
| 证书过期 | certificate expired | zh |
| https 报错 | certificate expired | zh |
| 容器退出 | container exit | zh |
| 退出码 137 | OOM killed | en |
| 句柄耗尽 | too many open files | zh |
| too many open files | too many open files | en |