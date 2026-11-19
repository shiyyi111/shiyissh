# Linux 运维知识库（种子语料 v1）

> 这份文档是 RAG 知识库的**初始语料**，不是给人从头读到尾的手册。
> 它的结构是刻意的：每个 `##` 小节就是一个独立的"知识条目"，标题即检索标题，
> 正文按「现象 → 判定 → 处理 → 回滚/验证」写，方便按标题切分后逐条向量化。
>
> 约定：
> - 每条开头先写**现象**（用户会怎么描述、日志里长什么样），因为用户提问用的是现象词，不是术语；
> - 命令都是**只读优先**，破坏性操作单独标注「需人工确认」；
> - 每条末尾的 `标签` 行用于二次过滤，不要删。
>
> 新增条目的规则：一条只讲一个问题；标题写"现象"，不写"模块名"；结论要能落到具体命令上。

## 1. 磁盘空间耗尽（No space left on device）

**现象**：写文件报 `No space left on device`；`df -h` 显示某个分区 100%；应用日志刷 `write failed`；MySQL 报 `Error writing file '/tmp/...'`。

**判定**：先分清是「块满了」还是「inode 满了」——两者报错一样，处理方式完全不同。

```bash
df -h              # Use% 是否 100%
df -i              # IUse% 是否 100%（inode 耗尽：大量小文件，如 session、缓存碎片）
du -xh --max-depth=1 / 2>/dev/null | sort -rh | head -20
```

**处理**：
1. 块满：按 `du` 结果从大到小定位。常见大头是 `/var/log`、`/var/lib/docker`、应用日志、数据库 binlog。
2. 日志类先确认是否被进程持有（`lsof +L1 | head`），直接 `rm` 不会释放空间，要 `truncate -s 0` 或重启对应进程。
3. Docker：`docker system df` 看镜像/容器/卷占比，`docker system prune -af` 清理无用对象（**需人工确认**）。
4. inode 满：找小文件密集目录 `find /var -xdev -type f | awk -F/ '{print $2"/"$3"/"$4}' | sort | uniq -c | sort -rn | head`，确认后可删缓存目录，不要盲删业务文件。
5. 处理后必须回到 `df -h` / `df -i` 验证，并检查应用是否恢复写入。

**注意**：不要用 `rm -rf /var/log/*` 一把梭——正在写的日志删了不释放空间，还可能删掉审计记录。

标签：disk, space, inode, no-space, logrotate

## 2. 内存不足与 OOM Killer

**现象**：进程莫名消失；`dmesg -T | grep -i "killed process"` 有记录；系统变慢、swap 使用率高；Java 服务被 `Killed` 但无异常栈。

**判定**：

```bash
free -h                    # available 还剩多少（看 available，不看 free）
dmesg -T | grep -iE "out of memory|killed process" | tail -20
cat /proc/meminfo | grep -E "MemAvailable|SwapTotal|SwapFree"
ps aux --sort=-%mem | head -10
```

**处理**：
1. 确认被 OOM 杀的是哪个进程、RSS 多大（`dmesg` 行里会带 `total-vm`、`rss`）。
2. 短期：重启该服务、或给它加 `MemoryMax`（systemd）限制，避免它拖垮整机。
3. 中期：看是不是缓存/连接池无上限（连接数、结果集、大文件读入内存）。
4. swap 已满且 available 极低时，先停非核心服务腾内存，不要直接 `reboot`（**需人工确认**）。

标签：memory, oom, swap, dmesg

## 3. CPU 负载高（load average 高）

**现象**：`top` 里 load 长时间高于核数；接口 RT 上升；`uptime` 显示 1/5/15 分钟负载都高。

**判定**：先分清是「真算」还是「等 IO」——load 高不等于 CPU 忙。

```bash
nproc
uptime
top -bn1 | head -15
vmstat 1 5          # r 列（可运行队列）高=CPU 争抢；wa 列高=IO 等待
pidstat -u 1 3      # 按进程看 CPU
```

**处理**：
1. `vmstat` 的 `wa` 高 → 转 IO 排查（`iostat -x 1` 看 `%util`、`await`）。
2. `r` 高、`us` 高 → 找 CPU 大户（`ps aux --sort=-%cpu | head`），看是否死循环、正则回溯、GC 频繁。
3. Java 服务：`jstack` 抓 3 次线程栈看是否卡在同一方法；`jstat -gcutil` 看 GC 占比。
4. 定位到进程后先降级（限流、摘流量），再改代码，不要在高峰期重启。

标签：cpu, load, vmstat, gc

## 4. 端口被占用（Address already in use）

**现象**：启动报 `bind: address already in use`；`nginx` 起不来；改了配置重启无效。

**判定**：

```bash
ss -lntp | grep :80
lsof -i :80
# 若看不到进程：可能是别的 netns / 容器占用
ss -lntp | head
docker ps --format '{{.Names}} {{.Ports}}'
```

**处理**：
1. 确认占用进程是不是"自己上一个没退干净的实例"（`ps -fp <pid>` 看启动命令与工作目录）。
2. 是遗留实例 → 先 `kill <pid>`，等 5 秒未退再 `kill -9`（**需人工确认**）。
3. 是别的服务 → 换端口或停掉对方，改完 `ss -lntp` 复查监听状态。

标签：port, bind, ss, lsof

## 5. 服务启动失败排查（systemd）

**现象**：`systemctl start xxx` 返回 failed；`systemctl status` 显示 `Result: exit-code`；服务起来几秒又挂。

**判定**：

```bash
systemctl status xxx --no-pager -l
journalctl -u xxx -n 100 --no-pager
systemctl cat xxx            # 看 ExecStart、User、WorkingDirectory、Environment
```

**处理**：
1. 看 `status` 里的 `code=exited, status=1/FAILURE` 与 `journalctl` 最后 20 行，通常是配置错、端口占用、权限不足、依赖服务未起。
2. 手工执行 `ExecStart` 那条命令，能看到被 systemd 吞掉的 stdout。
3. 权限问题：`ls -l` 看二进制/配置属主，`User=` 与文件权限是否匹配。
4. 依赖：`After=`/`Requires=` 是否漏了数据库、网络（`network-online.target`）。
5. 改完 `systemctl daemon-reload && systemctl restart xxx`，再 `status` 确认 `active (running)`。

标签：systemd, journalctl, service, 启动失败

## 6. nginx 502 Bad Gateway

**现象**：浏览器 502；`error.log` 里有 `connect() failed (111: Connection refused) while connecting to upstream`。

**判定**：

```bash
nginx -t
tail -100 /var/log/nginx/error.log
ss -lntp | grep <upstream端口>
curl -sv http://127.0.0.1:<upstream端口>/health
```

**处理**：
1. `Connection refused` → 后端没监听：查后端进程是否存活、端口是否改过、是否只监听了 `127.0.0.1` 而 nginx 配的是别的地址。
2. `upstream timed out` → 后端慢或挂了：看后端日志、线程池、数据库连接。
3. `no live upstreams` → 健康检查全失败：确认 `proxy_pass` 地址与 `upstream` 块。
4. 改完 `nginx -t` 通过再 `nginx -s reload`（**reload 不中断连接，restart 会**）。

标签：nginx, 502, upstream, gateway

## 7. nginx 504 Gateway Timeout

**现象**：长请求 504；`error.log` 有 `upstream timed out (110: Connection timed out)`。

**判定**：先确认是"后端处理慢"还是"网关等不及"。

```bash
grep -E "proxy_read_timeout|proxy_connect_timeout|proxy_send_timeout" /etc/nginx/nginx.conf /etc/nginx/conf.d/*.conf
tail -100 /var/log/nginx/error.log
# 直接打后端，测真实耗时
curl -o /dev/null -s -w '%{time_total}\n' http://127.0.0.1:<port>/slow-endpoint
```

**处理**：
1. 后端确实慢 → 优化后端（慢查询、外部调用超时），不要只调大 nginx 超时掩盖问题。
2. 业务本身长（导出、大文件）→ 改成异步任务 + 轮询，或单独 location 放宽 `proxy_read_timeout`。
3. 确认后端有超时保护，避免 nginx 放大了超时后连接堆积。

标签：nginx, 504, timeout, upstream

## 8. DNS 解析失败（Could not resolve host）

**现象**：`curl: (6) Could not resolve host`；`ping` 域名失败但 `ping IP` 正常；应用报 `UnknownHostException`。

**判定**：

```bash
cat /etc/resolv.conf
getent hosts example.com
nslookup example.com
dig +short example.com @8.8.8.8
```

**处理**：
1. `resolv.conf` 里 nameserver 不通 → 换可用 DNS（**需人工确认**，注意内网域名可能依赖内网 DNS）。
2. 本机 DNS 正常、特定域名失败 → 该域名解析记录问题或本地 hosts 覆盖（`cat /etc/hosts`）。
3. 容器内失败 → 看容器 `--dns` 与宿主机 `resolv.conf` 是否一致。
4. 应用侧：确认是否配了代理（`env | grep -i proxy`），代理会影响解析路径。

标签：dns, resolve, resolv.conf, network

## 9. 网络连通性排查（ping 通但端口不通）

**现象**：能 ping 通目标机，但 `telnet ip port` 连不上；接口超时。

**判定**：按"链路 → 本机监听 → 防火墙 → 安全组"顺序排除，不要跳步。

```bash
ip addr; ip route
ping -c 3 <ip>
ss -lntp | grep <port>            # 本机是否监听、监听在哪个地址
nc -zv <ip> <port>                # 端口连通性
traceroute -T -p <port> <ip>      # 路径上在哪一跳断
iptables -S; nft list ruleset 2>/dev/null | head -50
```

**处理**：
1. 本机未监听 → 服务问题，回去查进程。
2. 监听在 `127.0.0.1` → 改成 `0.0.0.0` 或对应网卡地址。
3. 本机防火墙拦截 → 放行端口（**需人工确认**），并确认规则持久化（`iptables-save`/`netfilter-persistent`）。
4. 云主机 → 还要看安全组/网络 ACL，这部分在机器上查不到。

标签：network, port, firewall, traceroute

## 10. SSH 连不上（Connection refused / timeout / Permission denied）

**现象**：`ssh: connect to host ... port 22: Connection refused` 或 `Connection timed out`，或密码正确仍 `Permission denied (publickey)`。

**判定**：
- `Connection refused`：sshd 没起，或端口不是 22。
- `Connection timed out`：网络/防火墙/安全组拦截。
- `Permission denied`：认证问题（密钥、用户、`AllowUsers`、`PermitRootLogin`）。

```bash
systemctl status sshd
ss -lntp | grep :22
tail -50 /var/log/auth.log 2>/dev/null || journalctl -u sshd -n 50 --no-pager
grep -E "^(Port|PermitRootLogin|PasswordAuthentication|AllowUsers)" /etc/ssh/sshd_config
```

**处理**：
1. sshd 未运行 → `systemctl start sshd`，并确认 `enabled`（避免重启后又连不上）。
2. 端口非 22 → 连接时 `-p` 指定，并同步安全组。
3. `Permission denied (publickey)` → 确认 `~/.ssh/authorized_keys` 权限（700/600）、属主正确、`AllowUsers` 包含该用户。
4. 改 `sshd_config` 后先 `sshd -t` 校验，再 `systemctl reload sshd`；**保持一个已登录会话不要关**，新开会话验证成功再退出。

标签：ssh, sshd, 22, authorized_keys, 连不上

## 11. 文件句柄耗尽（Too many open files）

**现象**：服务报 `Too many open files`；`accept` 失败；新连接建不上但 CPU 不高。

**判定**：

```bash
ulimit -n
cat /proc/<pid>/limits | grep "open files"
ls /proc/<pid>/fd | wc -l
lsof -p <pid> | wc -l
cat /proc/sys/fs/file-nr        # 系统级已分配/上限
```

**处理**：
1. 单进程超限 → 调 `LimitNOFILE`（systemd）或 `ulimit -n`，重启生效。
2. 系统级 `file-nr` 第一列接近上限 → 调 `fs.file-max`（**需人工确认**）。
3. 句柄泄漏：`lsof -p <pid> | awk '{print $5}' | sort | uniq -c | sort -rn` 看是不是 socket 只增不减，定位连接未关闭的代码路径。

标签：fd, ulimit, too-many-open-files, socket

## 12. 时间不同步（时钟漂移）

**现象**：日志时间戳乱序；TLS 证书报 `not yet valid`；分布式锁/令牌校验失败；`date` 与真实时间差几分钟。

**判定**：

```bash
timedatectl
chronyc tracking 2>/dev/null || ntpq -p
date -u
```

**处理**：
1. `System clock synchronized: no` → 起 `chronyd`/`systemd-timesyncd`，确认能访问上游 NTP。
2. 容器内时间取宿主机，宿主机不对则容器一起错。
3. 同步后复查业务：证书校验、JWT、日志排序相关告警。

标签：time, ntp, chrony, 时钟

## 13. MySQL 连接数打满（Too many connections）

**现象**：应用报 `Too many connections`；新连接被拒；已有连接还能用。

**判定**：

```bash
mysql -e "SHOW VARIABLES LIKE 'max_connections'; SHOW STATUS LIKE 'Threads_connected'; SHOW STATUS LIKE 'Threads_running';"
mysql -e "SHOW PROCESSLIST;" | head -30
```

**处理**：
1. 先看连接是不是都处于 `Sleep`（连接池配置过大 / 未归还），还是 `Query` 长时间执行。
2. 长查询 → `SHOW FULL PROCESSLIST` 找 `Time` 大的，`EXPLAIN` 分析，必要时 `KILL`（**需人工确认**）。
3. 应用侧检查连接池上限 × 实例数是否超过 `max_connections`。
4. 应急可临时调大 `max_connections`（`SET GLOBAL`，重启失效），但要评估内存。

标签：mysql, connections, processlist, 连接池

## 14. MySQL 慢查询与锁等待

**现象**：接口偶发超时；`SHOW PROCESSLIST` 里大量 `Waiting for ... lock`；CPU 不高但 RT 高。

**判定**：

```bash
mysql -e "SHOW VARIABLES LIKE 'slow_query_log'; SHOW VARIABLES LIKE 'long_query_time';"
mysql -e "SELECT * FROM information_schema.innodb_trx ORDER BY trx_started LIMIT 5\G"
mysql -e "SELECT * FROM performance_schema.data_lock_waits\G" | head -40
```

**处理**：
1. 锁等待 → 找阻塞源事务（`innodb_trx` 里最早开始的），确认它在等什么、持有什么。
2. 慢查询 → `EXPLAIN` 看是否全表扫、缺索引、隐式类型转换。
3. 大事务要拆小；DDL 走 online DDL 或低峰执行（**需人工确认**）。
4. 处理后复查 `Threads_running` 与慢日志是否恢复。

标签：mysql, slow-query, lock, innodb, 事务

## 15. Redis 内存告警与淘汰

**现象**：`OOM command not allowed when used memory > 'maxmemory'`；写入失败；命中率下降；延迟升高。

**判定**：

```bash
redis-cli INFO memory | grep -E "used_memory_human|maxmemory_human|maxmemory_policy"
redis-cli INFO stats | grep -E "evicted_keys|keyspace_hits|keyspace_misses"
redis-cli --bigkeys
redis-cli INFO keyspace
```

**处理**：
1. `maxmemory_policy=noeviction` 且内存满 → 写入直接失败，必须扩容或清理。
2. 淘汰率高（`evicted_keys` 增长快）→ 热 key 被淘汰，调大内存或优化 key 设计。
3. `--bigkeys` 找大 key（大 Hash/List/ZSet），拆分或改结构。
4. 确认没有把 Redis 当持久化存储用。

标签：redis, memory, eviction, bigkey

## 16. Docker 容器反复重启

**现象**：`docker ps` 里容器 `Restarting`；`docker logs` 看不到新日志；服务时好时坏。

**判定**：

```bash
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}'
docker inspect <name> --format '{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.RestartCount}}'
docker logs --tail 100 <name>
docker events --since 10m --until 0s --filter container=<name>
```

**处理**：
1. `OOMKilled=true` → 容器内存上限太小或应用泄漏，调 `-m` 或改应用。
2. `ExitCode=137` → 被 SIGKILL（OOM 或 `docker stop` 超时）。
3. `ExitCode=1` → 应用自身退出，看日志最后 20 行与启动参数、挂载、环境变量。
4. `RestartPolicy=always` 会掩盖问题，先改成 `no` 再排查，避免无限重启。

标签：docker, restart, oomkilled, exitcode

## 17. 日志暴涨 / 日志丢失

**现象**：磁盘突然被日志占满；或出问题时日志里没有关键信息。

**判定**：

```bash
du -sh /var/log/* | sort -rh | head
ls -lh /var/log/<app>/ | tail
grep -c "" /var/log/<app>/app.log
logrotate -d /etc/logrotate.d/<app>     # 干跑看规则是否生效
```

**处理**：
1. 暴涨 → 先限流/降级日志级别，再定位刷日志的代码（通常是循环里打异常栈）。
2. 被删但仍被进程持有 → `lsof +L1`，`truncate -s 0` 而不是 `rm`。
3. 日志切割未生效 → 检查 logrotate 配置与 `postrotate` 里是否让进程重开文件。
4. 丢失 → 确认日志级别、是否被采集侧过滤、容器日志驱动是否 `json-file` 且有大小限制。

标签：log, logrotate, disk, 日志

## 18. 文件权限与属主错误（Permission denied）

**现象**：读配置/写目录报 `Permission denied`；服务以非 root 启动后访问不了文件。

**判定**：

```bash
ls -ld /path; namei -l /path/to/file
id <user>
getfacl /path 2>/dev/null
```

**处理**：
1. 逐级检查目录执行位（`x`），缺 `x` 会表现为"文件明明可读却打不开"。
2. 属主/属组与服务运行用户不一致 → `chown`/`chgrp`（**需人工确认**）。
3. 不要用 `chmod -R 777` 解决——会引入安全风险，且掩盖真实问题。
4. SELinux/AppArmor 场景看 `ausearch -m avc -ts recent` / `dmesg | grep -i denied`。

标签：permission, chmod, chown, selinux

## 19. 证书过期与 HTTPS 握手失败

**现象**：浏览器/客户端报证书过期或不受信；`curl: (60) SSL certificate problem`；握手超时。

**判定**：

```bash
echo | openssl s_client -connect example.com:443 -servername example.com 2>/dev/null | openssl x509 -noout -dates -subject -issuer
curl -vI https://example.com
```

**处理**：
1. `notAfter` 已过 → 续签并 reload 服务（nginx/应用）。
2. 证书链不全 → 补中间证书，`openssl s_client -showcerts` 看链。
3. SNI 场景必须带 `-servername`，否则拿到的可能是默认站点证书。
4. 续签后验证：`curl -vI` 看 `SSL certificate verify ok`，并确认自动续签任务存在。

标签：tls, certificate, https, openssl, 证书

## 20. 备份与恢复校验

**现象**：以为有备份，真出事发现备份是空的/不可恢复。

**判定**：

```bash
ls -lh /backup | tail
du -sh /backup
# 数据库：确认备份文件能被解析（不是 0 字节、不是半截）
```

**处理**：
1. 备份三要素：**有**、**能恢复**、**恢复后数据对**。只满足第一条等于没有。
2. 定期做恢复演练到临时库，比对行数与关键表。
3. 备份落盘位置不要与被备份数据同盘；异地/对象存储另存一份。
4. 记录 RPO/RTO：多久备一次、恢复要多久。

标签：backup, restore, rpo, rto

## 21. 内核参数与文件系统只读

**现象**：写文件报 `Read-only file system`；`dmesg` 有 `EXT4-fs error`；系统突然不能写任何文件。

**判定**：

```bash
mount | grep " ro,"
dmesg -T | grep -iE "ext4|xfs|I/O error" | tail -20
df -h
```

**处理**：
1. 文件系统因 IO 错误被内核 remount 成只读 → 先保数据，再查磁盘健康（`smartctl -a`）。
2. 不要直接 `mount -o remount,rw` 掩盖问题——先确认底层盘没坏。
3. 云盘场景看是否被限流/掉盘，联系云厂商前先保留 `dmesg` 证据。

标签：filesystem, readonly, ext4, io-error, smartctl

## 22. 定时任务没执行（cron / systemd timer）

**现象**：脚本手工能跑，cron 不跑；凌晨任务没产出。

**判定**：

```bash
systemctl list-timers --all
crontab -l
journalctl -u cron -n 50 --no-pager
grep CRON /var/log/syslog | tail -20
```

**处理**：
1. cron 环境变量与登录 shell 不同（`PATH` 少、无 `HOME`）→ 脚本里写绝对路径、显式设环境变量。
2. 脚本无执行权限 / 换行符是 CRLF → `chmod +x`、`dos2unix`。
3. 输出被 cron 邮件吞掉 → 重定向到日志文件再看。
4. systemd timer 注意 `OnCalendar` 时区与 `Persistent=true`。

标签：cron, timer, 定时任务, crontab

## 23. 进程假死（端口在、请求不响应）

**现象**：`ss` 显示在监听，`curl` 挂住不返回；CPU 0%；重启后恢复。

**判定**：

```bash
ss -lntp | grep <port>
ps -o pid,stat,wchan:30,cmd -p <pid>
cat /proc/<pid>/status | grep -E "State|Threads"
jstack <pid> > /tmp/stack.txt 2>/dev/null   # Java
cat /proc/<pid>/task/*/stack 2>/dev/null | head
```

**处理**：
1. `State: D`（不可中断睡眠）→ 卡在 IO/内核，查磁盘、NFS、驱动。
2. 线程全卡在同一把锁 → 死锁或慢调用无超时，抓栈确认后改代码。
3. 连接队列满（`ss -lnt` 的 `Recv-Q` 接近 backlog）→ 调 backlog 或查 accept 线程。
4. 先保留现场（栈、`/proc` 信息）再重启，否则下次还会复现。

标签：hang, deadlock, jstack, d-state

## 24. 磁盘 IO 瓶颈（iowait 高、写入慢）

**现象**：`vmstat` 的 `wa` 高；写日志/写库变慢；`iostat` 里 `%util` 接近 100%。

**判定**：

```bash
iostat -x 1 5
pidstat -d 1 3
iotop -oP 2>/dev/null | head
cat /proc/pressure/io 2>/dev/null
```

**处理**：
1. 找 IO 大户（`pidstat -d`），区分是读放大（缓存不足）还是写放大（fsync 频繁、日志同步刷盘）。
2. 数据库：看 buffer pool 命中率、redo 刷盘策略、大事务。
3. 应用：批量写、减少 fsync 次数、异步刷盘（要评估丢数据风险）。
4. 系统层：`vm.dirty_ratio`/`dirty_background_ratio` 是否过高导致突发回写。
5. 云盘有 IOPS/带宽上限，超限表现为延迟升高，需要看云监控而不是只看机器指标。

标签：io, iowait, iostat, fsync, 磁盘

## 25. 排查方法论（怎么把上面的条目用起来）

**原则**：

1. **先确认影响面**：是单机、单服务，还是全站；是持续还是偶发；最近有没有变更（发布、配置、扩容、证书、网络策略）。
2. **先看现象再动手**：`uptime`、`df -h`、`free -h`、`ss -lntp`、`journalctl -n 50` 这五条是"体检五项"，能覆盖大半问题。
3. **一次只改一个变量**，改完立刻验证；不要同时改配置、重启服务、清缓存。
4. **保留现场**：栈、日志片段、`dmesg`、时间点；重启会销毁证据。
5. **破坏性操作前先确认**：`rm -rf`、`mkfs`、`dd`、重启、停服务、改防火墙，都要明确"影响谁、能不能回滚"。
6. **结论要沉淀**：现象 → 根因 → 处理 → 验证，四段写清楚，下次同类问题直接命中。

标签：方法, 排查, 运维, 流程