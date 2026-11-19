# shyissh-server

基于 **Spring Boot 3.4.3 + Spring AI 1.1.8 + Spring AI Alibaba Graph** 的 SSH 运维智能体：
用户用自然语言描述服务器问题，图链路（意图 → 上下文装配 → ReAct 循环 → 命令分级 → 观察 → 收口）调用 SSH 工具排查；
变更类命令走人工审批（`interruptBefore(approval)` + `MysqlSaver` 断点续跑）。

## 模块（6 子模块 + 根 POM）

| 模块 | 职责 |
| --- | --- |
| `shyissh-server-api` | 对外接口定义 |
| `shyissh-server-app` | 启动、装配、Controller、配置（`application*.yml`、`agent/ssh-agent.yml`） |
| `shyissh-server-domain` | 图链路与领域服务（`graph/*`、上下文压缩、长期记忆、RAG、命令守卫） |
| `shyissh-server-infrastructure` | MyBatis DAO、SSH、Qdrant、外部网关 |
| `shyissh-server-trigger` | 触发器 / 入口适配 |
| `shyissh-server-types` | 共享类型与常量 |

## 快速开始

1. 依赖：JDK 17、Maven 3.9+、MySQL 8（库名 `shiyissh`）、Qdrant（知识检索与记忆语义召回；连不上时降级为纯关键词/MySQL 候选池）。
2. 初始化：按需执行 `docs/dev-ops/mysql/sql/` 下的脚本；图检查点表（`GRAPH_THREAD` / `GRAPH_CHECKPOINT`）由 `MysqlSaver` 自动创建。**静态知识不再入库**——`docs/knowledge-base/*.md` 在启动后由 `KnowledgeSourceSync` 切段向量化写入 Qdrant（`app.rag.knowledge.sync-on-startup`，幂等）。
3. 配置：数据源在 `application-dev.yml`；模型与三 Agent 在 `agent/ssh-agent.yml`。**API Key 走环境变量，不要提交明文**。
4. 启动：`mvn -o -B -pl shyissh-server-app -am spring-boot:run`（默认端口 `8091`）。
5. 测试：`mvn -o -B -pl shyissh-server-domain test`（当前 155 条，真模型 / 需库用例另作手工验证，见 `shyissh-server-app/src/test/java/com/shiyi/test/`）。
6. 检索评测：`shyissh-server-app/src/test/java/com/shiyi/test/knowledge/RetrievalBaselineProbeTest`（32 条固定查询，输出 recall@5 / MRR / hit@1 / p95）。

## 环境变量（不进仓库的密钥）

启动前必须提供 DashScope 的 key，`application.yml` 与 `agent/ssh-agent.yml` 里都只保留占位符 `${DASHSCOPE_API_KEY:}`：

```bash
# PowerShell
$env:DASHSCOPE_API_KEY="sk-..."
# bash
export DASHSCOPE_API_KEY="sk-..."
# 或 JVM 参数
-DDASHSCOPE_API_KEY=sk-...
```

> 旧版本把 key 明文写在 `agent/ssh-agent.yml:14` 并已进入 git 历史，需在阿里云控制台吊销重发。

## 安全配置（必须）

从 2026-11-19 起，缺少下面两个变量时后端会**主动拒绝**而不是降级运行，这是刻意的：

| 变量 | 不配置的后果 | 说明 |
| --- | --- | --- |
| `APP_API_KEY` | 所有 `/api/**` 返回 401 | 请求需带 `X-Api-Key: <key>`（或 `Authorization: Bearer <key>`）；前端用 `localStorage.shyissh_api_key` 或构建期 `VITE_API_KEY` 提供 |
| `APP_CRYPTO_KEY` | 保存/读取 SSH 连接凭据时抛 `IllegalStateException` | 用于 AES-GCM 加密 `ssh_connection_config` 里的密码与私钥；**一旦设置不要更换**，换了旧凭据无法解密 |

完整变量清单见根目录 [`.env.example`](.env.example)。数据库口令、Qdrant 地址、百度 AppBuilder Key 同样只走环境变量。

## 桌面客户端（`client/`）

Tauri 2 + React + TypeScript 写成的 SSH 终端与对话客户端，对接后端 `8091`：

```bash
cd client
pnpm install
pnpm tauri dev     # 开发
pnpm tauri build   # 打包桌面安装包
```

请求头需带 `X-Api-Key`：在客户端里填写后端 `APP_API_KEY` 的值，或构建期设置 `VITE_API_KEY`。

## 文档

- 运维知识库（启动时切段向量化进 Qdrant）：`docs/knowledge-base/` 下的 runbook / policies / incidents / commands / inventory / synonyms
- 图链路深挖：`docs/agent-scaffold-deep-dive.md`
