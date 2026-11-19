# 结构化清单（Inventory）

> 本文件是**结构化清单示例**，用于给 `ops_host` / `ops_service` 表提供种子数据。
> 它**不进向量库**：主机名、IP、端口、版本这类精确字段走结构化查询与过滤，不参与语义召回。
> 示例数据仅用于演示字段与关联关系，不对应真实生产环境。

## 主机清单（ops_host，8 台）

| host_key | hostname | ip | env | role | os | cpu | memory | disk | owner | status |
|---|---|---|---|---|---|---|---|---|---|---|
| prod-web-01 | prod-web-01 | 10.20.1.11 | prod | nginx | Rocky Linux 9.4 | 8C | 16G | 200G | ops-web | online |
| prod-web-02 | prod-web-02 | 10.20.1.12 | prod | nginx | Rocky Linux 9.4 | 8C | 16G | 200G | ops-web | online |
| prod-app-01 | prod-app-01 | 10.20.2.11 | prod | application | Rocky Linux 9.4 | 16C | 32G | 500G | ops-app | online |
| prod-app-02 | prod-app-02 | 10.20.2.12 | prod | application | Rocky Linux 9.4 | 16C | 32G | 500G | ops-app | online |
| prod-db-01 | prod-db-01 | 10.20.3.11 | prod | mysql-primary | Rocky Linux 9.4 | 16C | 64G | 1T | ops-dba | online |
| prod-db-02 | prod-db-02 | 10.20.3.12 | prod | mysql-replica | Rocky Linux 9.4 | 16C | 64G | 1T | ops-dba | online |
| prod-cache-01 | prod-cache-01 | 10.20.4.11 | prod | redis | Rocky Linux 9.4 | 8C | 32G | 200G | ops-middleware | online |
| prod-mq-01 | prod-mq-01 | 10.20.5.11 | prod | rocketmq | Rocky Linux 9.4 | 8C | 32G | 500G | ops-middleware | online |

补充字段说明：

| 字段 | 说明 |
|---|---|
| host_key | 主机唯一标识，也是知识条目的归属过滤键 |
| env | 环境标识，示例均为 prod |
| role | 主机角色，用于按角色筛排障手册 |
| status | online / offline / maintenance |

## 服务清单（ops_service，6 个）

| service_key | name | env | host_keys | port | version | owner | healthcheck | depends_on |
|---|---|---|---|---|---|---|---|---|
| nginx-lb | nginx 入口网关 | prod | prod-web-01,prod-web-02 | 80,443 | 1.26.2 | ops-web | GET /health | order-service,user-service |
| gateway | API 网关 | prod | prod-app-01,prod-app-02 | 8080 | 2.4.1 | ops-app | GET /actuator/health | user-service,order-service |
| order-service | 订单服务 | prod | prod-app-01,prod-app-02 | 8081 | 3.2.0 | ops-order | GET /actuator/health | mysql-cluster,redis-cache,rocketmq |
| user-service | 用户服务 | prod | prod-app-01,prod-app-02 | 8082 | 3.1.5 | ops-user | GET /actuator/health | mysql-cluster,redis-cache |
| mysql-cluster | MySQL 主从集群 | prod | prod-db-01,prod-db-02 | 3306 | 8.0.36 | ops-dba | mysqladmin ping | — |
| redis-cache | Redis 缓存 | prod | prod-cache-01 | 6379 | 7.2.4 | ops-middleware | redis-cli ping | — |

服务与主机是多对多关系：`host_keys` 为逗号分隔的主机标识，落库时拆到关联表；`depends_on` 用于故障影响面分析。RocketMQ 作为独立中间件由 `prod-mq-01` 承载，未列入上表 6 个核心服务。

## 使用方式

- 检索知识条目时，先按 `host_key` 或 `service_key` 过滤归属，再做关键词与向量召回。
- 主机名、IP、端口、版本只用于结构化精确匹配，不写入向量文本。
- 变更主机角色或服务依赖后，同步更新本清单与 `ops_host` / `ops_service` 表。