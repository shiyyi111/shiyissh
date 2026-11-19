/**
 * 演示用本地 shell：
 * 在没有真实 SSH 后端时，用确定性的模拟输出让终端面板可交互。
 * 所有命令都在本机完成，不发起任何网络请求。
 */

export interface ShellCtx {
  user: string;
  host: string;
  port: number;
  serverName: string;
  cwd: string;
}

export interface CmdResult {
  out: string;
  ctx: ShellCtx;
  clear?: boolean;
  exit?: boolean;
}

const ESC = "\x1b[";
const c = {
  reset: `${ESC}0m`,
  bold: `${ESC}1m`,
  dim: `${ESC}2m`,
  green: `${ESC}32m`,
  brightGreen: `${ESC}92m`,
  blue: `${ESC}34m`,
  brightBlue: `${ESC}94m`,
  cyan: `${ESC}36m`,
  yellow: `${ESC}33m`,
  magenta: `${ESC}35m`,
  red: `${ESC}31m`,
  grey: `${ESC}90m`,
  white: `${ESC}97m`,
};

export function shortHost(host: string): string {
  // IP 地址保持完整（截成 "10" 会像被截断），域名才取第一段
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return host;
  return host.split(".")[0] || host;
}

export function promptOf(ctx: ShellCtx): string {
  return `${c.bold}${c.brightGreen}${ctx.user}@${shortHost(ctx.host)}${c.reset}:${c.bold}${c.brightBlue}${ctx.cwd}${c.reset}$ `;
}

export function welcomeBanner(ctx: ShellCtx): string {
  const rule = "─".repeat(46);
  const lines = [
    `${c.dim}${rule}${c.reset}`,
    `  ${c.bold}${c.white}shyissh${c.reset} ${c.grey}· 演示会话（本地模拟）${c.reset}`,
    `  ${c.grey}主机${c.reset} ${c.cyan}${ctx.host}:${ctx.port}${c.reset}   ${c.grey}用户${c.reset} ${c.cyan}${ctx.user}${c.reset}`,
    `  ${c.grey}输入${c.reset} ${c.yellow}help${c.reset} ${c.grey}查看可用命令${c.reset}`,
    `${c.dim}${rule}${c.reset}`,
    "",
  ];
  return lines.join("\r\n") + "\r\n";
}

interface VDir {
  [name: string]: string[] | VDir;
}

const HOME: VDir = {
  "apps": {
    "web": ["index.js", "package.json", "dist/"],
    "api": ["server.ts", "package.json"],
    "worker": ["queue.js"],
  },
  "logs": ["nginx-access.log", "nginx-error.log", "app-2026-10-29.log"],
  "backups": ["db-2026-10-28.sql.gz", "config-2026-10-27.tar.gz"],
  ".ssh": ["authorized_keys", "id_ed25519", "known_hosts"],
  "deploy.sh": [],
  "docker-compose.yml": [],
  "README.md": [],
  ".bashrc": [],
  ".env": [],
};

const VAR_LOG: string[] = [
  "auth.log",
  "syslog",
  "nginx/",
  "postgresql/",
  "app/",
  "journal/",
  "dpkg.log",
];

const ETC: string[] = [
  "nginx/",
  "ssh/",
  "systemd/",
  "hosts",
  "hostname",
  "resolv.conf",
  "os-release",
  "fstab",
  "crontab",
];

function listDir(path: string): string[] | null {
  const norm = path.replace(/^~\/?/, "");
  if (!norm) return Object.keys(HOME);
  if (norm === "var/log" || norm === "/var/log") return VAR_LOG;
  if (norm === "etc" || norm === "/etc") return ETC;
  const parts = norm.split("/");
  let node: VDir | string[] | undefined = HOME;
  for (const part of parts) {
    if (Array.isArray(node) || !node) return null;
    node = node[part];
  }
  if (!node) return null;
  if (Array.isArray(node)) return node;
  return Object.keys(node);
}

function resolvePath(cwd: string, target: string): string {
  if (!target || target === "~") return "~";
  if (target.startsWith("~/")) {
    const rest = target.slice(2).replace(/\/+$/, "");
    return rest ? `~/${rest}` : "~";
  }
  if (target.startsWith("/")) return target.replace(/\/+$/, "") || "/";
  if (target === "..") {
    if (cwd === "~" || cwd === "/") return cwd === "~" ? "~" : "/";
    const segs = cwd.split("/");
    segs.pop();
    return segs.join("/") || "~";
  }
  const base = cwd === "~" ? "~" : cwd;
  return `${base}/${target}`.replace(/\/+/g, "/");
}

function colorEntry(name: string): string {
  const isDir = name.endsWith("/");
  const bare = isDir ? name.slice(0, -1) : name;
  if (isDir) return `${c.bold}${c.brightBlue}${bare}/${c.reset}`;
  if (bare.startsWith(".")) return `${c.grey}${bare}${c.reset}`;
  if (/\.(sh|js|ts|yml|yaml|json)$/.test(bare)) return `${c.brightGreen}${bare}${c.reset}`;
  if (/\.(md|log|gz|sql)$/.test(bare)) return `${c.cyan}${bare}${c.reset}`;
  return `${c.white}${bare}${c.reset}`;
}

function fakeStat(name: string, i: number): string {
  const isDir = name.endsWith("/");
  const perms = isDir ? "drwxr-xr-x" : "-rw-r--r--";
  const size = isDir ? 4096 : 640 + i * 1327;
  const day = 20 + (i % 9);
  const sizeStr = String(size).padStart(7);
  return `${perms} 1 ${c.cyan}deploy${c.reset} ${c.cyan}deploy${c.reset} ${sizeStr} ${c.grey}10月 ${day} ${String(9 + (i % 9)).padStart(2, "0")}:${String(10 + i).padStart(2, "0")}${c.reset} `;
}

const HELP = `${c.bold}shyissh 演示终端${c.reset} ${c.grey}（这些命令是本地模拟，不会真的连服务器）${c.reset}

 ${c.yellow}文件${c.reset}
   ls / ls -la        列出当前目录
   cd <目录>          切换目录   （支持 ~ / .. / /var/log）
   pwd                打印当前路径
   cat <文件>         查看文本文件

 ${c.yellow}系统${c.reset}
   whoami / id        当前用户
   hostname           主机名
   uname -a           内核信息
   uptime             运行时长与负载
   free -h            内存占用
   df -h              磁盘占用
   date               当前时间
   ps aux             进程列表（截断）
   top                资源快照
   ss -tulpn          监听端口

 ${c.yellow}服务${c.reset}
   docker ps          容器状态
   systemctl status nginx
   git status         仓库状态

 ${c.yellow}其他${c.reset}
   clear              清屏        Ctrl+L 同样有效
   exit               断开会话
   help               显示这份帮助

 ${c.grey}提示：Ctrl+C 中断当前输入，↑ / ↓ 浏览历史命令。${c.reset}`;

const OS_RELEASE = `PRETTY_NAME="Ubuntu 24.04.1 LTS"
NAME="Ubuntu"
VERSION_ID="24.04"
VERSION="24.04.1 LTS (Noble Numbat)"
ID=ubuntu
VERSION_CODENAME=noble
HOME_URL="https://www.ubuntu.com/"`;

export function runCommand(raw: string, ctx: ShellCtx): CmdResult {
  const input = raw.trim();
  if (!input) return { out: "", ctx };

  const [cmd, ...args] = input.split(/\s+/);
  const arg = args.join(" ");
  const ok = (out: string): CmdResult => ({ out, ctx });

  switch (cmd) {
    case "help":
    case "?":
      return ok(HELP);

    case "clear":
      return { out: "", ctx, clear: true };

    case "exit":
    case "logout":
      return { out: `${c.grey}已断开与 ${ctx.host} 的演示会话。${c.reset}`, ctx, exit: true };

    case "pwd":
      return ok(ctx.cwd === "~" ? "/home/deploy" : ctx.cwd.replace(/^~/, "/home/deploy"));

    case "whoami":
      return ok(ctx.user);

    case "id":
      return ok(
        `uid=1000(${ctx.user}) gid=1000(${ctx.user}) groups=1000(${ctx.user}),27(sudo),999(docker)`,
      );

    case "hostname":
      return ok(shortHost(ctx.host));

    case "date":
      return ok(new Date().toLocaleString("zh-CN", { hour12: false }));

    case "uname":
      return ok("Linux " + shortHost(ctx.host) + " 6.8.0-45-generic x86_64 GNU/Linux");

    case "uptime":
      return ok(
        ` ${new Date().toLocaleTimeString("zh-CN", { hour12: false })} up 27 days, load: 0.42 0.38 0.31`,
      );

    case "free":
      return ok(
        [
          `${c.grey}        total   used   free  shared   cache   avail${c.reset}`,
          `Mem:     31Gi   9.4Gi  1.2Gi   412Mi    21Gi    21Gi`,
          `Swap:   8.0Gi   0.0Gi  8.0Gi`,
        ].join("\r\n"),
      );

    case "df":
      return ok(
        [
          `${c.grey}Filesystem      Size  Used Avail Use% Mounted${c.reset}`,
          `/dev/nvme0n1p2  468G  182G  267G  41% /`,
          `/dev/nvme0n1p1  511M  6.1M  505M   2% /boot`,
          `tmpfs            16G   32M   16G   1% /dev/shm`,
          `/dev/nvme1n1    1.8T  1.1T  612G  65% /data`,
        ].join("\r\n"),
      );

    case "ls":
    case "ll":
    case "dir": {
      const target = arg
        .split(/\s+/)
        .filter((a) => a && !a.startsWith("-"))
        .join(" ");
      const path = target ? resolvePath(ctx.cwd, target) : ctx.cwd;
      const entries = listDir(path);
      if (!entries) {
        return ok(`${c.red}ls: cannot access '${target || path}': No such file or directory${c.reset}`);
      }
      const long = arg.includes("l") || cmd === "ll";
      const showHidden = arg.includes("a");
      const visible = showHidden ? entries : entries.filter((e) => !e.startsWith("."));
      if (!visible.length) return ok("");
      if (long) {
        const rows = visible.map((name, i) => `${fakeStat(name, i)}${colorEntry(name)}`);
        return ok(
          [`总用量 ${visible.length * 4}`, ...rows, ""].join("\r\n"),
        );
      }
      return ok(
        visible
          .map(colorEntry)
          .join("  ")
          .replace(/\s+$/, ""),
      );
    }

    case "cd": {
      if (!arg || arg === "~") return { out: "", ctx: { ...ctx, cwd: "~" } };
      if (arg === "/") return { out: "", ctx: { ...ctx, cwd: "/" } };
      const next = resolvePath(ctx.cwd, arg);
      const entries = listDir(next);
      if (!entries) {
        return ok(`${c.red}-bash: cd: ${arg}: No such file or directory${c.reset}`);
      }
      return { out: "", ctx: { ...ctx, cwd: next } };
    }

    case "cat": {
      if (arg === "/etc/os-release" || arg === "os-release") return ok(OS_RELEASE);
      if (arg === "README.md") {
        return ok(
          [
            `${c.bold}# ${ctx.serverName}${c.reset}`,
            "",
            "- 部署方式：" + `${c.cyan}docker compose${c.reset}`,
            "- 监控：Prometheus + Grafana",
            "- 备份：每日 03:00 全量，保留 14 天",
            "- 联系人：ops@example.com",
          ].join("\r\n"),
        );
      }
      if (arg === ".env") {
        return ok(
          [
            `${c.grey}NODE_ENV=production${c.reset}`,
            `${c.grey}PORT=3000${c.reset}`,
            `${c.grey}LOG_LEVEL=info${c.reset}`,
            `${c.grey}DATABASE_URL=postgres://app:***@10.0.12.44:5432/app${c.reset}`,
          ].join("\r\n"),
        );
      }
      if (!arg) return ok(`${c.grey}用法: cat <文件>${c.reset}`);
      return ok(`${c.red}cat: ${arg}: No such file or directory${c.reset}`);
    }

    case "ps":
      return ok(
        [
          `${c.grey}USER      PID  %CPU %MEM  COMMAND${c.reset}`,
          `root        1   0.0  0.1  /sbin/init`,
          `root      842   0.3  0.6  dockerd`,
          `deploy   1841   1.2  2.1  node dist/server.js`,
          `www-data 1843   0.1  0.4  nginx: worker`,
          `postgres 1201   0.4  1.8  postgres: writer`,
          `${c.grey}... (共 128 个进程，已截断)${c.reset}`,
        ].join("\r\n"),
      );

    case "top":
      return ok(
        [
          `top - ${new Date().toLocaleTimeString("zh-CN", { hour12: false })} up 27 days, load: 0.42 0.38 0.31`,
          `${c.grey}Tasks: 128 total, 1 running, 127 sleeping${c.reset}`,
          `${c.grey}%Cpu(s): 4.2 us, 1.1 sy, 94.2 id${c.reset}`,
          `Mem: 32126M total, 1244M free, 9621M used`,
          "",
          `${c.grey}  PID USER      %CPU %MEM  COMMAND${c.reset}`,
          ` 1841 deploy     1.2  2.1  node`,
          `  842 root       0.3  0.6  dockerd`,
          ` 1201 postgres   0.4  1.8  postgres`,
        ].join("\r\n"),
      );

    case "ss":
      return ok(
        [
          `${c.grey}Netid State  Local Address       Peer${c.reset}`,
          `tcp   LISTEN 0.0.0.0:22          *:*`,
          `tcp   LISTEN 0.0.0.0:80          *:*`,
          `tcp   LISTEN 0.0.0.0:443         *:*`,
          `tcp   LISTEN 127.0.0.1:3000      *:*`,
          `tcp   LISTEN 127.0.0.1:5432      *:*`,
        ].join("\r\n"),
      );

    case "docker": {
      if (args[0] === "ps") {
        return ok(
          [
            `${c.grey}NAME    IMAGE                UPTIME${c.reset}`,
            `web     nginx:1.27-alpine    27 days`,
            `api     app:2026.10.27       3 days`,
            `db      postgres:16-alpine   27 days`,
          ].join("\r\n"),
        );
      }
      if (args[0] === "stats") {
        return ok(
          [
            `${c.grey}NAME  CPU %  MEM USAGE/LIMIT   NET I/O${c.reset}`,
            `web   0.12%  42MiB/1GiB       1.2GB/3.4GB`,
            `api   1.84%  312MiB/2GiB      884MB/1.1GB`,
            `db    0.42%  288MiB/2GiB      96MB/220MB`,
          ].join("\r\n"),
        );
      }
      return ok(`${c.grey}演示终端支持: docker ps / docker stats${c.reset}`);
    }

    case "systemctl": {
      if (arg.startsWith("status")) {
        return ok(
          [
            `${c.bold}● nginx.service - high performance web server${c.reset}`,
            `   Loaded: loaded (nginx.service; enabled)`,
            `   Active: ${c.brightGreen}active (running)${c.reset} 27 days ago`,
            ` Main PID: 1843 (nginx)`,
            `    Tasks: 5 (limit: 38289)`,
            `   Memory: 24.6M`,
            `      CPU: 12min 44.220s`,
          ].join("\r\n"),
        );
      }
      if (arg.startsWith("list-units")) {
        return ok(
          [
            `nginx      loaded active running web server`,
            `postgresql loaded active running PostgreSQL`,
            `redis      loaded active running Redis 7`,
            `fail2ban   loaded active running Fail2Ban`,
          ].join("\r\n"),
        );
      }
      return ok(`${c.grey}演示终端支持: systemctl status nginx / systemctl list-units${c.reset}`);
    }

    case "git":
      return ok(
        [
          `On branch main`,
          `Your branch is up to date with 'origin/main'.`,
          "",
          `Changes not staged for commit:`,
          `  ${c.red}modified:   src/server.ts${c.reset}`,
          `  ${c.red}modified:   config/production.yml${c.reset}`,
          "",
          `${c.grey}no changes added to commit${c.reset}`,
        ].join("\r\n"),
      );

    case "echo":
      return ok(arg);

    case "sudo":
      return ok(
        arg
          ? `${c.yellow}[sudo] 演示环境不执行提权命令${c.reset}`
          : `${c.grey}用法: sudo <命令>${c.reset}`,
      );

    case "tail":
      return ok(
        [
          `10:31:02 INFO  GET /api/health 200 3ms`,
          `10:31:07 INFO  GET /api/connections 200 12ms`,
          `10:32:15 WARN  slow query 486ms sessions`,
          `10:33:41 INFO  POST /api/chat 200 208ms`,
        ].join("\r\n"),
      );

    default:
      return ok(
        `${c.red}-bash: ${cmd}: command not found${c.reset}\r\n${c.grey}输入 ${c.yellow}help${c.grey} 查看演示终端支持的命令${c.reset}`,
      );
  }
}

export function completionHint(input: string): string {
  const commands = [
    "help", "ls", "cd", "pwd", "whoami", "id", "hostname", "uname", "uptime",
    "free", "df", "ps", "top", "ss", "docker", "systemctl", "git", "cat",
    "echo", "clear", "exit", "date",
  ];
  const match = commands.filter((cmd) => cmd.startsWith(input) && cmd !== input);
  return match.length ? match.join("  ") : "";
}