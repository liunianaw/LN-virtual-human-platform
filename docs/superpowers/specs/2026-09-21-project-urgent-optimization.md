# 项目紧急优化执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [项目架构说明书](../../../项目架构说明书.md) · [新增代码规范](../../新增代码规范.md)。

日期：2026-09-21。版本：1.0。确认状态：用户已确认按“规范 → RabbitMQ/Outbox → 角色制作定向整改 → 目录调整”的顺序执行，并明确当前未提交代码也属于本次整改范围。

## 1. 当前执行状态

| 项目 | 当前事实与证据 |
|---|---|
| 当前阶段 | 正式编码与目录整改已完成，提交为 `41c85b8`、`d6f8b7b`；本文件保留为已完成技术基线。 |
| 已形成能力 | system 在事务内写 Outbox 并发布 RabbitMQ 持久消息；media 手动 ACK 消费，Worker 租约、attempt、回执和结果回写仍通过内部 HTTP 契约。 |
| 已知剩余风险 | 尚未在真实数据库和业务任务上完成第 9 节的端到端终点验收；不得把隔离 Broker 验证写成完整业务验收。 |
| 主代理静态逻辑验证 | 已完成受影响 Java 模块测试、Python 聚焦测试、启动脚本解析和隔离 RabbitMQ topology/ACK/retry/DLQ 验证，详见第 8 节。 |
| 用户体验验收 | 未执行；不默认发起付费生成。 |
| 下一步 | 后续业务计划直接复用本计划的消息与分层边界；需要时按第 9 节做受控的真实业务验收。 |

## 2. 目标、范围和边界

本次将角色制作的“本地事务写 Outbox”可靠地接到 RabbitMQ 持久消息和 Python Worker 手动 ACK，同时保留 Worker 向 system 领取步骤租约、保存回执、报告进度和结果的内部 HTTP 契约。新增及修改代码必须遵守《新增代码规范》。

本次不做全仓 MyBatis-Plus/Lombok 替换、不全量格式化、不重做已确认的角色制作功能、不默认启动服务、执行迁移或发起付费调用。目录移动放在消息链路和资产整改静态验证之后。

## 3. 已确认决策

| 规则 | 本次实现 |
|---|---|
| ORM | 继续 MyBatis + XML；不引入 MyBatis-Plus。 |
| 服务分层 | asset 新改业务 Service 统一为 `IxxxService` + `XxxServiceImpl`；请求 DTO 用 `@Valid`。 |
| Outbox 所有权 | system 在业务事务内写 `p_outbox`，system 内部发布器领取、发布确认并更新状态；media 不再领取或确认 Outbox。 |
| AMQP 拓扑 | durable direct exchange `ln.platform.events`，routing key `avatar.generation.requested.v1`，durable queue `ln.media.avatar-generation.v1`；另设 retry exchange/queue 和 DLQ，均为持久消息。 |
| 投递确认 | system 仅在 publisher confirm ACK 后置 Outbox 为 `SENT`；NACK、超时或异常回到 `PENDING` 并指数退避。确认丢失允许重复发布，依靠 eventId/步骤状态幂等。 |
| 消费确认 | media 使用 `aio-pika`、prefetch=1、手动 ACK。只有 system 已接受步骤终态/无可领取步骤/陈旧消息后 ACK；本地或 system 暂时错误进入有限延迟重试；耗尽进入 DLQ。 |
| 付费不确定性 | Provider 已提交而结果不明时保留 `UNKNOWN`，然后 ACK；MQ 重投不得再次触发付费请求。 |

消息体为 `{schemaVersion,eventId,eventType,traceId,accountId,payload:{taskId}}`；不带 Outbox ID、凭证、文件内容或永久 URL。eventType 固定 `AVATAR_GENERATION_REQUESTED`，schemaVersion=1。

## 4. 现有链路与强制复用

`AssetService` 在同一事务写 `p_generation_task`、步骤和 `p_outbox`。旧的 Worker `/outbox/claim`、`/outbox/sent` HTTP 轮询链路已移除；system 发布器与 `ruoyi_media.worker.__main__ --consume-rabbit` 已取代它。Worker 继续通过既有内部 API 租赁可执行步骤和保存结果。

必须复用 `GenerationWorker.handle`、`SystemGenerationPlatform.claim/prepareAttempt/progress/saveReceipt/submit...`、数据库租约/attempt/结果状态机，以及当前 provider 的 UNKNOWN 处理。RabbitMQ 仅负责“唤醒任务”，不承载租约、厂商调用或结果事实。

## 5. 完整业务流程

| 步骤 | 触发与输入 | 处理、持久化和恢复 |
|---|---|---|
| 1 | 用户创建/重做角色 | Service 在同一事务写业务事实和 PENDING Outbox。 |
| 2 | system 定时发布器 | 原子领取到期 PENDING/SENDING Outbox，发布持久消息并等待 confirm；ACK→SENT，失败→PENDING+指数退避。 |
| 3 | media Rabbit 消费者 | 校验白名单事件并调用现有 `GenerationWorker.handle`；Worker 仍从 system 领取唯一可做步骤。 |
| 4 | 处理结果 | 连续处理该任务的当前可领取步骤；步骤成功、失败、UNKNOWN、无工作或陈旧消息均 ACK 原 MQ 消息。 |
| 5 | 可恢复故障 | 没有任何 provider 请求证据的临时错误延迟重试；达到上限投递 DLQ。provider 已提交的不确定结果保存 UNKNOWN，不回队列。 |

## 6. 数据、状态与不变量

不增加并行的任务事实表。`p_outbox.status` 仍为 `PENDING/SENDING/SENT`，但其 lease owner 改为 system publisher；现有 lease 字段复用。新增版本化迁移只在确有列或索引缺口时添加，绝不改已执行 V1。

I1：业务事实与 Outbox 同事务；I2：Outbox 仅由 system publisher 标记 SENT；I3：MQ 至少一次投递不引发重复付费；I4：消息 ACK 之前，system 已持久化可继续执行或最终事实；I5：eventId、accountId、taskId 在 Python 解析、system claim 和 SQL 关联中一致；I6：RabbitMQ 故障不使任务丢失，Outbox 退避后可重发。

## 7. 编码顺序与完整能力切片

| 切片 | 完整行为 | 修改位置 | 静态退出条件 |
|---|---|---|---|
| A | 规范落地与消息契约 | 本规范、当前计划、system AMQP 配置/DTO | 调用方只有一套 event 语义。 |
| B | Outbox 发布 | system publisher/mapper XML/POM/配置 | confirm 后才 SENT；失败不丢失；旧 Worker Outbox API 无调用方。 |
| C | Rabbit 消费 | media requirements、consumer、入口、Compose/本机启动 | durable 声明、手动 ACK、有限 retry/DLQ；继续复用 HTTP 步骤契约。 |
| D | asset 定向规范化 | `asset` 的 Controller/DTO/Service/impl/Mapper | Controller 只保留边界；管理员判断在 Service；无嵌套请求 DTO。 |
| E | 目录整理 | `ruoyi-session/runtime`、`ln-relay` | 已核对：前者当前不存在；后者已迁至 `tools/local-relay`，启动脚本和说明书已同步；HTTP 路径仍为 `/ln-relay/v1`，不改变会话配置。 |

## 8. 主代理静态逻辑验证

| 要求 | 实际代码路径 | 当前结论 |
|---|---|---|
| I1/I2 | Asset 写 Outbox → publisher Mapper → RabbitTemplate confirm → p_outbox | 已由发布器单测核对：confirm ACK 后才标记 SENT，NACK 回到 PENDING 并按尝试次数退避。 |
| I3/I4 | Rabbit consumer → GenerationWorker.handle → system lease/attempt/result API → ACK/retry | 已在隔离 RabbitMQ Broker 验证：持久 topology 声明、手动 ACK、非法消息 DLQ 与 WAITING→retry；仍待携带真实数据库任务的完整业务终点验收。 |
| I5/I6 | event DTO/Python 校验、retry 头、SENDING lease 与退避 SQL | 已静态核对：事件字段、有限重试、DB 租约和失败退避均有实现。 |
| 分层规范 | asset Controller → `I*Service` → `*ServiceImpl` → Mapper XML | 已核对：控制器只处理 HTTP 边界；管理员规则进入 Service；请求 DTO 不再嵌套 Controller/Service。 |

辅助检查仅运行受影响 Java 模块编译、Python 语法/聚焦测试及 Compose 配置校验，不默认连接外部厂商或数据库。

## 9. 用户终点验收与修复

| 场景 | 用户操作 | 预期结果 |
|---|---|---|
| 正常制作 | 创建一个已允许的制作任务 | Rabbit 队列有消费事实，task/steps 前进，Worker 不只是心跳。 |
| Rabbit 重启/confirm 失败 | 在安全环境中中断消息链路后恢复 | Outbox 保持或回到 PENDING，恢复后重发，无丢任务。 |
| 重复消息/UNKNOWN | 重投同一消息或制造已提交结果未知 | 不新建付费请求；系统保留唯一 attempt 和 UNKNOWN 事实。 |
| 管理员入口 | 使用公共角色创建/管理入口 | Controller 无业务编排，Service 拒绝非管理员伪造调用。 |

本轮编码已完成并通过受影响模块验证。隔离 Broker 已验证消息拓扑、手动 ACK、retry 和 DLQ；携带真实数据库任务的完整业务链路验收仍待按本节执行。

## 10. 跨模块交接

system 是消息生产、Outbox 发布与业务状态唯一写入方；ruoyi-media 是 Rabbit 消费方与媒体执行方，只通过既有内部 HTTP 契约改变步骤事实。Vue、SDK、session 不消费本次制作消息。后续目录整理必须同步本机启动脚本、Compose、README 与 Python import，不能以移动目录改变此边界。
