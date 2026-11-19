# 运维命令速查（Commands）

> 按场景分组，共 30 条。命令均为只读或低风险操作，可直接复制执行；涉及 IP、域名、路径的示例按实际环境替换。
> 破坏性操作（删除、重启、DDL、KILL）不在此表，执行前必须走变更流程。

## 磁盘与文件

- `df -h` — 查看各挂载点容量与使用率，先确认哪个分区满。
- `df -i` — 查看 inode 使用率，空间未满但无法创建文件时先看这一项。
- `du -xh --max-depth=1 / 2>/dev/null | sort -rh | head -20` — 从根目录逐层定位大目录，-x 避免跨文件系统。
- `lsof +L1 2>/dev/null | head -20` — 列出已删除但仍被进程持有的文件，rm 后空间不释放时使用。
- `find /var/log -xdev -type f -size +1G -ls 2>/dev/null` — 找出日志目录中超过 1G 的文件。

## 内存与 CPU

- `free -h` — 看 available 而不是 free，判断真实可用内存。
- `ps aux --sort=-%mem | head -10` — 按物理内存占用排序，定位内存大户。
- `vmstat 1 5` — 每秒采样一次共 5 次，看 r（运行队列）、wa（IO 等待）、si/so（swap 换入换出）。
- `pidstat -u 1 3` — 按进程统计 CPU 使用率，需要 sysstat 包。

## 进程与服务

- `systemctl status nginx --no-pager` — 查看服务状态、最近日志与主进程退出码。
- `journalctl -u nginx -n 200 --no-pager` — 查看指定服务最近 200 行日志。
- `ss -lntp` — 列出所有监听中的 TCP 端口及对应进程。
- `ps -eo pid,ppid,stat,etime,cmd --sort=-%cpu | head -10` — 查看进程树关系、运行时长与启动命令，STAT 为 D 表示 IO 等待。

## 网络与端口

- `ping -c 4 10.0.0.1` — 测试三层连通性与往返延迟。
- `nc -vz -w 3 10.0.0.1 3306` — 测试目标端口是否可建立 TCP 连接，超时 3 秒。
- `traceroute -n 10.0.0.1` — 逐跳定位网络断点，-n 不反解域名。
- `ss -s` — 汇总各状态连接数，快速判断连接是否异常堆积。
- `curl -o /dev/null -s -w '%{http_code} %{time_total}\n' https://example.com/health` — 只输出状态码与总耗时，用于接口健康检查。

## 日志

- `tail -n 200 -f /var/log/nginx/error.log` — 实时跟踪错误日志，先看最近 200 行再持续输出。
- `grep -c 'ERROR' /var/log/app/app.log` — 统计错误条数，判断是否为刷屏式异常。
- `logrotate -d /etc/logrotate.conf` — 干跑 logrotate，检查切割规则是否会按预期执行。

## 数据库

- `mysqladmin -uroot -p status` — 查看 MySQL 运行时长、连接数、QPS 等概要指标。
- `mysql -uroot -p -e "SHOW FULL PROCESSLIST;"` — 查看全部连接及正在执行的 SQL，定位慢查询与锁等待。
- `mysqldumpslow -s t -t 10 /var/log/mysql/slow.log` — 按总耗时排序输出前 10 条慢查询。

## 容器

- `docker ps -a --filter 'status=exited'` — 列出已退出容器，确认退出时间与状态。
- `docker logs --tail 100 $(docker ps -aq | head -1)` — 查看最近一个容器的末尾日志。
- `docker inspect --format '{{.State.ExitCode}} {{.State.OOMKilled}}' $(docker ps -aq --filter 'status=exited' | head -1)` — 查看退出码与是否因 OOM 被杀。

## 证书与时间

- `openssl x509 -in /etc/nginx/ssl/server.crt -noout -dates -subject` — 查看本地证书的生效时间、到期时间与主题。
- `echo | openssl s_client -connect example.com:443 -servername example.com 2>/dev/null | openssl x509 -noout -dates` — 查看线上实际下发的证书有效期。
- `timedatectl` — 查看系统时间、时区与 NTP 同步状态。