# Linux 运维排障手册（Runbook）

> 每条一个 `##` 小节，对应一条可独立检索、独立执行的运维规则。
> 正文固定为「现象 → 判定 → 处理 → 命令 → 风险 → 标签」，命令均为可直接执行的只读命令或明确标注的低风险操作。

## KB-001 磁盘空间不足
- 现象: 服务写入报 No space left on device；df 显示某个分区 Use% 接近 100%；应用日志出现 write failed
- 判定: df -h 看哪个挂载点满；du 从根目录逐层定位大目录；lsof +L1 找已删除但仍被进程占用的文件
- 处理: 先确认文件归属和可重建性再删；日志用 truncate -s 0 清空而不是 rm；被占用的大文件用 lsof 查到 pid 后重启对应服务释放
- 命令:
  df -h
  du -xh --max-depth=1 / 2>/dev/null | sort -rh | head -20
  lsof +L1 2>/dev/null | head -20
  find /var/log -xdev -type f -size +500M -ls 2>/dev/null
- 风险: 禁止直接 rm -rf 系统目录或正在写的日志；删除前确认文件可重建；清空日志前确认审计留存要求
- 标签: disk,storage,log,production

## KB-002 磁盘 inode 耗尽
- 现象: 磁盘显示还有剩余空间，但创建文件报 No space left on device；df -i 显示 IUse% 100%
- 判定: df -i 确认 inode 使用率；统计各目录文件数量，重点看 session、缓存、邮件队列、容器层
- 处理: 找到小文件密集目录，确认业务属性后删除过期缓存或临时文件；必要时迁移到独立分区
- 命令:
  df -i
  find / -xdev -printf '%h\n' 2>/dev/null | sort | uniq -c | sort -rn | head -20
  find /var -xdev -type f 2>/dev/null | wc -l
- 风险: 不要按目录大小判断 inode 问题；删除缓存前确认可重建；避免对根目录执行无差别 find -delete
- 标签: disk,inode,storage,production

## KB-003 内存不足与 OOM
- 现象: 进程莫名消失；dmesg 出现 Killed process；Java 服务无异常栈直接退出；系统响应变慢
- 判定: free -h 看 available 而不是 free；dmesg 与内核日志确认是否触发 OOM Killer；按 RSS 排序找内存大户
- 处理: 短期重启服务或给服务加 MemoryMax 限制；中期排查连接池、结果集、缓存无上限问题；swap 耗尽时先停非核心服务
- 命令:
  free -h
  cat /proc/meminfo | grep -E 'MemAvailable|SwapTotal|SwapFree'
  dmesg -T | grep -iE 'out of memory|killed process' | tail -20
  ps aux --sort=-%mem | head -10
- 风险: 不要在内存紧张时直接 reboot；重启前先保留现场；限制内存后要验证服务容量是否仍满足业务
- 标签: memory,oom,swap,production

## KB-004 swap 使用过高
- 现象: 系统整体变慢；free 显示 swap used 持续增长；业务 RT 抖动；磁盘 IO 同时升高
- 判定: swapon --show 看 swap 设备与使用量；vmstat 看 si/so 是否持续非零；定位单个进程的 VmSwap
- 处理: 先判断是内存真不足还是进程泄漏；临时释放可重启大内存进程；长期方案是扩容内存或限制进程内存
- 命令:
  swapon --show
  free -h
  vmstat 1 5
  grep VmSwap /proc/*/status 2>/dev/null | sort -k2 -rn | head -10
- 风险: swapoff 需要足够空闲内存，否则会触发 OOM；不要在生产高峰执行 swapoff
- 标签: memory,swap,performance,production

## KB-005 CPU 负载高
- 现象: top 中 load average 持续高于 CPU 核数；接口 RT 上升；CPU 使用率接近 100%
- 判定: nproc 确认核数；uptime 看 1/5/15 分钟趋势；top 与 pidstat 定位高 CPU 进程；区分用户态与内核态占用
- 处理: 定位到进程后先限流或摘流量；Java 服务抓线程栈看死循环、正则回溯、频繁 GC；确认原因后再改代码或扩容
- 命令:
  nproc
  uptime
  top -bn1 | head -15
  ps -eo pid,ppid,comm,%cpu,%mem --sort=-%cpu | head -10
  pidstat -u 1 3
- 风险: 不要在高负载时直接重启全部实例；先摘流量保留现场；kill -9 只作为最后手段
- 标签: cpu,load,performance,production

## KB-006 load average 高但 CPU 空闲
- 现象: load average 很高，但 top 中 CPU idle 仍很高；业务变慢；进程处于 D 状态
- 判定: vmstat 看 wa 与 b 列；iostat 看磁盘 util 与 await；ps 查 D 状态进程；确认是否存在 IO 或锁等待
- 处理: 按 IO 路径排查磁盘、NFS、云盘限流；定位大量读写或 fsync 的进程；必要时限流、拆分大查询或升级存储
- 命令:
  vmstat 1 5
  iostat -x 1 3
  ps -eo pid,stat,comm,wchan:32 | awk '$2 ~ /D/'
  pidstat -d 1 3
- 风险: D 状态进程通常无法被 kill，强行重启可能扩大故障；NFS 卡住时先确认网络与存储端
- 标签: load,io,disk,performance

## KB-007 进程被 kill 排查
- 现象: 服务进程突然消失；监控显示进程数归零；日志没有异常堆栈；systemd 显示 exited
- 判定: 查内核 OOM 记录；看 systemd 退出码（137 多为 SIGKILL，143 为 SIGTERM）；确认是否有人工操作或发布脚本
- 处理: OOM 则按内存条目处理；人工 kill 则核对变更与操作审计；崩溃则保留 core 与日志后重启
- 命令:
  dmesg -T | grep -iE 'killed process|out of memory' | tail -20
  journalctl -k --since '2 hours ago' | grep -i oom
  systemctl show -p ExecMainStatus -p ExecMainCode nginx
  last -x | head -20
- 风险: 不要只重启了事；未定位根因会周期性复发；core 文件可能很大，注意磁盘水位
- 标签: process,oom,systemd,production

## KB-008 端口被占用
- 现象: 服务启动报 Address already in use；bind 失败；新版本无法拉起
- 判定: ss 查监听端口对应进程；lsof 与 fuser 交叉确认；区分 IPv4/IPv6 与不同绑定地址
- 处理: 确认占用进程是否为核心服务；旧进程残留则优雅停止；配置冲突则改端口并同步上游
- 命令:
  ss -lntp | grep ':8080'
  lsof -i :8080
  fuser -n tcp 8080
  ss -tanp | grep ':8080'
- 风险: 不要直接 kill -9 不明进程；确认 PID 与启动命令后再操作；改端口要同步防火墙与负载均衡
- 标签: port,network,service,production

## KB-009 systemd 服务起不来
- 现象: systemctl start 失败；服务状态为 failed；开机不自启；反复重启
- 判定: systemctl status 看退出码与最后日志；journalctl -u 看完整报错；systemctl cat 检查单元文件路径与用户
- 处理: 修正单元文件后 daemon-reload；检查 ExecStart 路径、权限、环境变量、依赖服务；用 systemd-analyze verify 校验语法
- 命令:
  systemctl status nginx --no-pager
  journalctl -u nginx -n 100 --no-pager
  systemctl cat nginx
  systemd-analyze verify /etc/systemd/system/nginx.service
  systemctl show -p ExecMainStatus -p ExecMainCode nginx
- 风险: 修改单元文件后必须 daemon-reload；不要用 nohup 长期替代 systemd；注意 ProtectSystem 等沙箱选项
- 标签: systemd,service,startup,production

## KB-010 nginx 502 Bad Gateway
- 现象: 浏览器或客户端收到 502；nginx error.log 出现 connect() failed 或 upstream prematurely closed
- 判定: 先看 upstream 是否存活与端口是否监听；再看 nginx 到 upstream 的网络与超时；最后看 upstream 自身日志
- 处理: upstream 未启动则拉起；端口或地址配置错误则修 upstream 配置；上游崩溃则按上游服务排障
- 命令:
  nginx -t
  tail -n 100 /var/log/nginx/error.log
  ss -lntp | grep ':8080'
  curl -v http://127.0.0.1:8080/health
- 风险: reload 前必须 nginx -t；不要在未确认 upstream 状态时反复 reload；注意 SELinux 对反代的限制
- 标签: nginx,502,upstream,production

## KB-011 nginx 504 Gateway Timeout
- 现象: 请求长时间无响应后返回 504；error.log 出现 upstream timed out；部分接口偶发
- 判定: 看 upstream 处理耗时与连接数；确认是单请求慢还是整体排队；检查 proxy_read_timeout 与后端线程池
- 处理: 优化慢接口或异步化；合理调整超时但不要用来掩盖慢查询；扩容后端或增加连接池
- 命令:
  grep -E 'upstream timed out|timeout' /var/log/nginx/error.log | tail -20
  tail -n 100 /var/log/nginx/access.log
  curl -o /dev/null -s -w '%{http_code} %{time_total}\n' http://127.0.0.1:8080/health
  ss -tn state established '( sport = :8080 )' | wc -l
- 风险: 单纯调大超时会把压力堆到后端；超时时间要小于上游负载均衡的空闲超时
- 标签: nginx,504,timeout,production

## KB-012 连接数打满
- 现象: 新连接被拒绝；服务报 Too many open files 或 connection refused；ss -s 显示大量连接
- 判定: ss -s 看总量与各状态分布；确认是文件描述符、端口范围、应用连接池还是上游限流
- 处理: 先找连接来源与异常长连接；应用侧修连接泄漏；系统侧评估 somaxconn 与本地端口范围；不要盲目调大上限
- 命令:
  ss -s
  ss -tan | awk 'NR>1 {print $1}' | sort | uniq -c | sort -rn
  sysctl net.core.somaxconn net.ipv4.ip_local_port_range
  mysql -uroot -p -e "SHOW STATUS LIKE 'Threads_connected'; SHOW VARIABLES LIKE 'max_connections';"
- 风险: 调大连接上限会放大内存与后端压力；先定位泄漏点再扩容
- 标签: connection,network,tuning,production

## KB-013 TIME_WAIT 过多
- 现象: ss 统计 TIME_WAIT 数量很大；新连接偶发失败；短连接服务明显
- 判定: 统计 TIME_WAIT 数量与本地端口范围；确认是否为正常主动关闭；区分服务端与客户端角色
- 处理: 优先改长连接或连接池；客户端可开启 tcp_tw_reuse；缩短 fin_timeout 需评估；不要开启已移除的 tcp_tw_recycle
- 命令:
  ss -tan state time-wait | wc -l
  ss -tan state time-wait | awk '{print $4}' | cut -d: -f2 | sort | uniq -c | sort -rn | head
  sysctl net.ipv4.ip_local_port_range net.ipv4.tcp_fin_timeout net.ipv4.tcp_tw_reuse
- 风险: tcp_tw_reuse 只对客户端有效；修改内核参数要评估 NAT 与负载均衡场景
- 标签: network,tcp,timewait,production

## KB-014 DNS 解析失败
- 现象: 应用报 UnknownHostException 或 Name or service not known；curl 提示 Could not resolve host
- 判定: 查 resolv.conf 与 hosts；用 getent 走系统解析链路；用 dig 直连 DNS 服务器对比；确认是否仅容器内失败
- 处理: 修正 resolv.conf 或 DNS 服务地址；检查 search 域与 ndots；容器场景检查 daemon 与网络命名空间
- 命令:
  cat /etc/resolv.conf
  getent hosts example.com
  dig +short example.com @8.8.8.8
  resolvectl status
- 风险: 不要随意覆盖 resolv.conf，可能被 DHCP 或云厂商组件重写；内网域名不要依赖公网 DNS
- 标签: dns,network,resolution,production

## KB-015 网络不通
- 现象: 目标主机 ping 不通或端口连不上；应用报 Connection refused 或 Connection timed out；跨机房访问失败
- 判定: 先 ping 看三层连通；再用 nc 测端口；traceroute 看路径断点；最后查本机路由与安全组
- 处理: 按链路逐跳定位；本机路由错误修路由；中间设备拦截查安全组与防火墙；目标未监听则查目标服务
- 命令:
  ping -c 4 10.0.0.1
  telnet 10.0.0.1 3306
  nc -vz -w 3 10.0.0.1 3306
  traceroute -n 10.0.0.1
  ip route get 10.0.0.1
- 风险: 云环境安全组与 NACL 是两层，需分别确认；不要用 telnet 作为唯一判据，它可能被客户端策略影响
- 标签: network,connectivity,troubleshooting,production

## KB-016 防火墙拦截
- 现象: 本机服务正常监听，但外部访问超时；同网段部分机器可通；刚改过安全策略
- 判定: 确认 firewalld、iptables、nftables 哪一层在生效；看规则与默认策略；对比监听地址与放行端口
- 处理: 只放行必要来源与端口；规则变更先在测试机验证；云主机同步检查安全组
- 命令:
  firewall-cmd --state
  firewall-cmd --list-all
  iptables -L -n -v --line-numbers
  nft list ruleset
- 风险: 禁止为了排障直接 systemctl stop firewalld；清空规则可能导致失联；变更前保留当前规则导出
- 标签: firewall,network,security,production

## KB-017 SSH 连接慢
- 现象: ssh 登录前卡顿数秒到数十秒；输入密码后等待久；内网也慢
- 判定: ssh -vvv 看卡在哪个阶段；重点看 DNS 反解与 GSSAPI 认证；对比 IP 登录与域名登录
- 处理: 服务端关闭 UseDNS 与 GSSAPIAuthentication；客户端可复用连接；修正反向解析
- 命令:
  ssh -vvv -o ConnectTimeout=5 appuser@10.0.0.21
  time ssh -o BatchMode=yes appuser@10.0.0.21 true
  sshd -T | grep -E 'usedns|gssapiauthentication'
  journalctl -u sshd -n 50 --no-pager
- 风险: 修改 sshd_config 前备份并保留一个已登录会话；reload 后先用新连接验证再退出旧会话
- 标签: ssh,latency,dns,production

## KB-018 SSH 权限被拒
- 现象: 登录报 Permission denied (publickey)；密钥正确但无法登录；只有某台机器失败
- 判定: ssh -vvv 看服务端接受了哪些认证方式；检查 authorized_keys 与目录权限；查 sshd 日志确认拒绝原因
- 处理: 修正 .ssh 700、authorized_keys 600 与属主；确认公钥完整；核对 PermitRootLogin 与 AllowUsers
- 命令:
  ssh -vvv -i ~/.ssh/id_rsa appuser@10.0.0.21
  namei -l /home/appuser/.ssh/authorized_keys
  ls -ld /home/appuser /home/appuser/.ssh /home/appuser/.ssh/authorized_keys
  tail -n 50 /var/log/secure
- 风险: 不要用 chmod 777 修权限；不要关闭 PubkeyAuthentication 绕过问题；改配置前保留可用会话
- 标签: ssh,permission,authentication,production

## KB-019 文件句柄耗尽
- 现象: 应用报 Too many open files；新连接或读写失败；ls /proc/<pid>/fd 数量接近上限
- 判定: 看进程 limits 与系统 fs.file-max；统计进程 fd 数量与类型；确认是否连接或文件泄漏
- 处理: 提升 systemd LimitNOFILE 与 ulimit；修复未关闭的连接与流；必要时重启释放
- 命令:
  ulimit -n
  cat /proc/$(pgrep -f 'java.*\.jar' | head -1)/limits | grep 'open files'
  ls /proc/$(pgrep -f 'java.*\.jar' | head -1)/fd | wc -l
  lsof -p $(pgrep -f 'java.*\.jar' | head -1) | wc -l
  sysctl fs.file-max
- 风险: 只调大上限会延后故障；需同时排查泄漏；systemd 服务要改单元而不是只改 shell ulimit
- 标签: fd,ulimit,resource,production

## KB-020 时间不同步
- 现象: 日志时间与上游不一致；证书校验失败；分布式锁或 Token 失效；数据库主从时间偏差
- 判定: timedatectl 看时区与 NTP 同步状态；chronyc 或 ntpq 看偏差与源；对比多台机器 date
- 处理: 修正时区；恢复 chronyd 或 ntpd；内网统一指向可用时间源；容器继承宿主机时间
- 命令:
  timedatectl
  chronyc tracking
  chronyc sources -v
  date -u
- 风险: 大幅回拨时间可能影响证书、定时任务与分布式系统；先确认业务容忍度
- 标签: time,ntp,clock,production

## KB-021 crontab 不执行
- 现象: 到点没有执行；手动执行脚本正常；日志无输出；只有部分任务不跑
- 判定: crontab -l 确认表达式与用户；看 cron 服务状态；查 cron 日志确认是否触发；检查 PATH、权限与百分号转义；Debian 系对应 cron 与 /var/log/syslog
- 处理: 使用绝对路径；脚本内显式设置 PATH；输出重定向到日志；修正表达式后在测试机验证
- 命令:
  crontab -l
  systemctl status crond --no-pager
  journalctl -u crond --since today --no-pager
  grep CRON /var/log/cron | tail -30
- 风险: 不要用 root 跑无属主的脚本；cron 环境的 PATH 与登录 shell 不同；% 在 crontab 中需转义
- 标签: cron,schedule,automation,production

## KB-022 日志暴涨
- 现象: 磁盘使用率短时间快速上升；日志文件几分钟内增大数 GB；错误日志重复刷屏
- 判定: 按大小排序找增长文件；看单位时间增量；确认是调试日志、异常重试还是被攻击
- 处理: 先定位刷屏根因再清空；临时 truncate 控制水位；配置 logrotate 按大小与时间切割；修复重试风暴
- 命令:
  ls -lhS /var/log/*.log 2>/dev/null | head
  find /var/log -xdev -type f -size +1G -ls 2>/dev/null
  tail -n 200 /var/log/nginx/error.log
  logrotate -d /etc/logrotate.conf
- 风险: 清空日志会丢失排障证据；先备份关键片段；不要用 rm 删除被进程持有的文件
- 标签: log,disk,logrotate,production

## KB-023 MySQL 连接数满
- 现象: 应用报 Too many connections；新连接被拒绝；数据库响应变慢；Threads_connected 接近上限
- 判定: 看当前连接数与上限；按用户、来源、状态统计；确认是连接池泄漏还是慢查询占住连接
- 处理: 清理长时间 Sleep 连接；修复连接池配置；优化慢查询；必要时临时提高上限并观察内存
- 命令:
  mysql -uroot -p -e "SHOW STATUS LIKE 'Threads_connected'; SHOW VARIABLES LIKE 'max_connections';"
  mysql -uroot -p -e "SELECT USER, HOST, COMMAND, COUNT(*) FROM information_schema.processlist GROUP BY USER, HOST, COMMAND ORDER BY 4 DESC;"
  mysqladmin -uroot -p status
  mysql -uroot -p -e "SHOW FULL PROCESSLIST;"
- 风险: 不要随意 KILL 连接；先确认事务状态；提高 max_connections 会增加内存占用
- 标签: mysql,connection,database,production

## KB-024 MySQL 慢查询
- 现象: 接口 RT 升高；数据库 CPU 高；slow log 持续增长；特定 SQL 偶发数秒
- 判定: 确认慢查询日志已开启与阈值；用 mysqldumpslow 聚合；对目标 SQL 做 EXPLAIN 看索引与扫描行数
- 处理: 补合适索引；避免隐式类型转换与前置通配符；拆分大查询；必要时限流与缓存
- 命令:
  mysql -uroot -p -e "SHOW VARIABLES LIKE 'slow_query_log'; SHOW VARIABLES LIKE 'long_query_time';"
  mysqldumpslow -s t -t 10 /var/log/mysql/slow.log
  mysql -uroot -p -e "EXPLAIN SELECT * FROM orders WHERE user_id = 1001;"
  mysql -uroot -p -e "SHOW INDEX FROM orders;"
- 风险: 线上加索引要用 Online DDL 并评估锁与磁盘；不要直接改 SQL 而不看执行计划
- 标签: mysql,slowquery,index,database

## KB-025 MySQL 主从延迟
- 现象: 从库读到旧数据；Seconds_Behind_Master 持续增大；主库写入后从库迟迟不可见
- 判定: 看从库复制状态与延迟；确认大事务、无主键表、单线程回放或从库负载；对比主从 binlog 位点
- 处理: 拆分大事务；补齐主键与索引；开启并行复制；从库减压；延迟过大时读流量切回主库
- 命令:
  mysql -uroot -p -e "SHOW REPLICA STATUS\G" | grep -E 'Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Relay_Log_Space'
  mysql -uroot -p -e "SHOW PROCESSLIST;"
  mysql -uroot -p -e "SHOW VARIABLES LIKE 'slave_parallel_workers';"
  mysql -uroot -p -e "SHOW MASTER STATUS;"
- 风险: 不要用 skip-counter 或 sql_slave_skip_counter 跳过错误；先确认数据一致性；切换前核对位点
- 标签: mysql,replication,latency,database

## KB-026 Redis 内存满
- 现象: Redis 报 OOM command not allowed；写入失败；缓存命中率下降；实例内存接近 maxmemory
- 判定: INFO memory 看 used_memory 与 maxmemory；确认淘汰策略；--bigkeys 找大 key；看 key 的 TTL 分布
- 处理: 清理无 TTL 的大 key；设置合理过期时间；调整 maxmemory-policy；扩容或拆分实例
- 命令:
  redis-cli INFO memory
  redis-cli CONFIG GET maxmemory maxmemory-policy
  redis-cli --bigkeys
  redis-cli INFO keyspace
- 风险: --bigkeys 会扫描全库，低峰执行；KEYS * 禁止在生产使用；淘汰策略变更可能影响命中率
- 标签: redis,memory,cache,production

## KB-027 Java 进程 OOM 与栈溢出
- 现象: 应用抛 java.lang.OutOfMemoryError: Java heap space；或 StackOverflowError；进程频繁 Full GC 后退出
- 判定: 看 GC 使用率与堆上限；抓 histo 找占内存对象；抓线程栈确认递归或死锁；确认是否堆外内存泄漏
- 处理: 保留 heap dump 与线程栈；短期扩容或重启；修复集合无界、缓存过大、递归过深；配置 HeapDumpOnOutOfMemoryError
- 命令:
  jps -lvm
  jstat -gcutil $(pgrep -f 'java.*\.jar' | head -1) 1000 10
  jmap -histo:live $(pgrep -f 'java.*\.jar' | head -1) | head -30
  jstack $(pgrep -f 'java.*\.jar' | head -1) > /tmp/jstack.txt
- 风险: jmap -histo:live 会触发 Full GC，低峰执行；不要在生产长时间开启详细 GC 日志占满磁盘
- 标签: java,jvm,oom,production

## KB-028 容器退出码排查
- 现象: 容器反复重启；docker ps 看不到运行实例；Kubernetes Pod 处于 CrashLoopBackOff
- 判定: 看 ExitCode 与 OOMKilled；137 多为 SIGKILL 或 OOM，143 为 SIGTERM，127 为命令不存在，1 为应用错误；看容器日志与事件
- 处理: 按退出码定位；修正启动命令与镜像；补内存限制；检查依赖服务与配置挂载；Kubernetes 用 --previous 看上次日志
- 命令:
  docker ps -a --filter 'status=exited'
  docker inspect --format '{{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}' $(docker ps -aq --filter 'status=exited' | head -1)
  docker logs --tail 100 $(docker ps -aq --filter 'status=exited' | head -1)
  kubectl describe pod -n default $(kubectl get pods -n default --field-selector=status.phase!=Running -o name | head -1)
- 风险: 不要用 --privileged 或关闭健康检查绕过问题；查看 describe 前确认命名空间
- 标签: container,docker,exitcode,production

## KB-029 证书过期
- 现象: 浏览器提示证书已过期；客户端报 certificate has expired；小程序或 App 全部 HTTPS 请求失败
- 判定: openssl 查本地证书有效期；s_client 查线上实际下发证书；确认是否多级证书链缺失或域名不匹配
- 处理: 续签并替换证书；确认中间证书链完整；reload nginx 或重启服务；更新监控到期告警
- 命令:
  openssl x509 -in /etc/nginx/ssl/server.crt -noout -dates -subject
  echo | openssl s_client -connect example.com:443 -servername example.com 2>/dev/null | openssl x509 -noout -dates
  curl -vI https://example.com
  openssl s_client -connect example.com:443 -servername example.com -showcerts </dev/null
- 风险: 替换前备份旧证书；私钥权限保持 600；reload 前先 nginx -t；注意证书链顺序
- 标签: certificate,tls,https,production

## KB-030 磁盘 IO 高
- 现象: iostat 显示 %util 接近 100%；await 明显升高；业务读写变慢；load 同时升高
- 判定: 看设备级 util 与 await；iotop 定位进程；pidstat -d 看读写速率；确认是顺序大 IO 还是随机小 IO
- 处理: 限流大查询与批任务；优化 fsync 频率；拆分热点盘；云盘确认是否触发 IOPS 或吞吐上限
- 命令:
  iostat -x 1 3
  iotop -oP
  pidstat -d 1 3
  cat /proc/pressure/io
- 风险: 不要用 ionice 掩盖根因；降级前确认业务优先级；云盘扩容前核对实例规格上限
- 标签: disk,io,performance,production

## KB-031 内核参数调优
- 现象: 高并发下连接排队、TIME_WAIT 多、文件句柄不足；默认内核参数成为瓶颈
- 判定: 先确认瓶颈指标，再对照 somaxconn、backlog、文件句柄、本地端口范围；避免无指标盲调
- 处理: 小步调整并记录基线；写入 /etc/sysctl.d/ 持久化；逐项验证收益与副作用
- 命令:
  sysctl net.core.somaxconn net.core.netdev_max_backlog net.ipv4.ip_local_port_range
  sysctl fs.file-max fs.nr_open
  sysctl --system
  ulimit -n
- 风险: 生产禁止直接 sysctl -w 后不落盘；修改前备份；参数之间有关联，不要一次改多项
- 标签: kernel,sysctl,tuning,production

## KB-032 SELinux 拦截
- 现象: 服务启动失败但配置与权限都正常；nginx 无法反代或读文件；audit.log 出现 AVC denied
- 判定: getenforce 确认模式；ausearch 查 AVC；ls -Z 看文件上下文；确认是否刚迁移目录或改端口
- 处理: 用 semanage fcontext 与 restorecon 修正上下文；必要时加策略模块；临时 setenforce 0 仅用于验证
- 命令:
  getenforce
  sestatus
  ausearch -m avc -ts recent
  ls -Z /var/www/html
  semanage fcontext -l | grep /var/www
- 风险: 禁止永久关闭 SELinux；setenforce 0 只是临时验证；改上下文后要 restorecon 验证
- 标签: selinux,security,permission,production

## KB-033 包管理锁
- 现象: yum 或 apt 报 Unable to acquire the dpkg frontend lock；安装卡住；提示另一个进程持有锁
- 判定: 查是否有 apt、dpkg、yum、dnf 进程；确认锁文件被谁持有；区分正常安装与残留锁
- 处理: 等待当前事务结束；异常退出后确认无进程再清锁；yum 用 complete-transaction 修复
- 命令:
  ps aux | grep -E 'apt|dpkg|yum|dnf' | grep -v grep
  fuser -v /var/lib/dpkg/lock-frontend
  fuser -v /var/run/yum.pid
  yum-complete-transaction --cleanup-only
- 风险: 有进程持锁时禁止删锁；强删可能导致包数据库损坏；生产变更窗口内操作
- 标签: package,yum,apt,production

## KB-034 磁盘坏道与只读文件系统
- 现象: 文件系统突然变为只读；写入报 Read-only file system；dmesg 出现 I/O error 或 EXT4-fs error
- 判定: 看内核日志与挂载选项；smartctl 查盘健康；确认是文件系统错误、云盘异常还是硬件故障
- 处理: 立即停止写入并备份数据；硬件故障走更换流程；文件系统错误在维护窗口 umount 后 fsck
- 命令:
  dmesg -T | grep -iE 'I/O error|remount.*read-only|EXT4-fs error' | tail -30
  mount | grep '(ro,'
  smartctl -H /dev/sda
  smartctl -a /dev/sda | grep -E 'Reallocated|Pending|Uncorrectable'
- 风险: 禁止在业务运行时对根盘 fsck；坏盘不要反复读写；先备份再修复
- 标签: disk,filesystem,hardware,production

## KB-035 文件权限错误
- 现象: 服务报 Permission denied；配置能读但目录进不去；换用户后启动失败
- 判定: ls -l 看属主与模式；namei -l 逐级检查路径权限；id 确认运行用户与附加组；getfacl 看 ACL
- 处理: 按最小权限修正属主与模式；目录需要 x 位；不要用 777 快速绕过；补充必要 ACL
- 命令:
  ls -l /etc/nginx/nginx.conf
  namei -l /var/www/html/index.html
  id nginx
  getfacl /var/www/html 2>/dev/null
- 风险: chmod -R 777 会带来严重安全风险；改属主前确认服务运行用户；注意父目录权限
- 标签: permission,security,file,production

## KB-036 用户无法登录
- 现象: 正确密码仍提示登录失败；账号被锁定；SSH 或控制台都无法进入
- 判定: 查账号状态与锁定；看密码有效期；确认 shell 是否被禁用；查认证日志确认失败原因
- 处理: 解锁账号；重置过期密码；恢复 shell；确认家目录权限；必要时用单用户或救援模式
- 命令:
  passwd -S appuser
  chage -l appuser
  getent passwd appuser
  lastlog -u appuser
  journalctl -u sshd -n 50 --no-pager
- 风险: 不要直接编辑 /etc/shadow；重置密码前核实身份；保留至少一个可用管理入口
- 标签: user,login,ssh,production

## KB-037 定时任务日志
- 现象: 任务是否执行无法确认；执行了但无输出；失败后没有告警
- 判定: 查 cron 系统日志确认触发；查任务自身重定向日志；确认脚本退出码与输出路径
- 处理: 统一输出到带日期的日志文件；记录开始结束与退出码；失败接入告警；日志纳入轮转
- 命令:
  grep CRON /var/log/cron | tail -30
  journalctl -u crond --since today --no-pager
  tail -n 100 /var/log/mytask.log
  crontab -l
- 风险: 日志路径不可写会导致任务静默失败；不要把密码写入日志；轮转缺失会撑满磁盘
- 标签: cron,log,monitoring,production

## KB-038 备份失败
- 现象: 备份任务返回非零；备份目录没有新文件；文件大小为 0；恢复演练失败
- 判定: 查任务退出码与日志；确认源与目标空间；校验备份文件完整性；确认数据库导出是否被中断
- 处理: 修复空间或权限；重新执行并校验；增加失败告警；定期做恢复演练而不只是看文件存在
- 命令:
  df -h /backup
  find /backup -type f -mtime -1 -ls | head -20
  tar -tzf /backup/app-20250101.tar.gz >/dev/null && echo OK
  mysqldump -uroot -p --single-transaction app_db | gzip > /backup/app_db.sql.gz
- 风险: 未校验的备份等于没有备份；备份文件要异地保存；导出大库注意锁与磁盘
- 标签: backup,restore,database,production

## KB-039 数据库表锁
- 现象: 更新或查询卡住；接口超时；SHOW PROCESSLIST 出现 Waiting for table metadata lock
- 判定: 查 InnoDB 事务与锁等待；看 metadata locks；确认是否有长事务或未提交的 DDL
- 处理: 找到阻塞源并确认业务影响；结束长事务或低优先级会话；DDL 改到维护窗口并评估锁
- 命令:
  mysql -uroot -p -e "SHOW FULL PROCESSLIST;"
  mysql -uroot -p -e "SELECT * FROM information_schema.innodb_trx\G" | head -60
  mysql -uroot -p -e "SELECT * FROM performance_schema.metadata_locks WHERE LOCK_STATUS = 'PENDING';"
  mysql -uroot -p -e "SHOW ENGINE INNODB STATUS\G" | grep -A 20 'LATEST DETECTED DEADLOCK'
- 风险: KILL 会话前确认事务与回滚代价；长事务回滚可能更慢；不要在业务高峰做 DDL
- 标签: mysql,lock,transaction,database

## KB-040 网络丢包
- 现象: 请求偶发超时；ping 有丢包；TCP 重传升高；跨机房调用成功率下降
- 判定: 长 ping 统计丢包率；mtr 定位丢包跳点；看网卡 drop/error 与 TCP 重传；区分持续丢包与突发丢包
- 处理: 网卡或驱动问题升级修复；链路拥塞联系网络侧；云环境查安全组与限速；应用侧重试要带退避
- 命令:
  ping -c 100 -i 0.2 10.0.0.1
  mtr -rwc 50 10.0.0.1
  ip -s link show eth0
  ethtool -S eth0 | grep -iE 'drop|error|miss'
  ss -ti | grep -E 'retrans|rtt'
- 风险: 高频率 ping 可能被限速或误判为攻击；不要用应用层重试掩盖网络故障；抓包注意隐私与磁盘
- 标签: network,packetloss,tcp,production