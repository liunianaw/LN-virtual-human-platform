# 管理员平台任务、调用事实与异常核对执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.1。确认状态：用户于2026-09-22授权按本计划实施；初始仅做静态代理逻辑验收，后续用户明确要求启动项目并进行本地页面验收，仍不默认发起新的付费外部调用。

## 1. 当前执行状态

| 项目 | 当前事实 |
|---|---|
| 阶段/基线 | 以当前源码和 `869ab3f` 为实施基线；本计划 O-A～O-C 静态实现完成。 |
| 已有能力 | 原任务/attempt/回执/恢复链路、调用事实表和公共资产生命周期清理均复用保留。 |
| 本次实现 | generation 与 TTS 调用事实可靠入账、跨账号安全查询/详情、ETag+幂等核对、审计迁移、网关和管理页面。 |
| 静态结论/用户验收 | system 与 session 受影响模块离线编译通过，前端类型检查通过；2026-09-22 追加的调用事实回归通过，system 已重启并完成无厂商提交的历史卡死任务收敛。 |
| 下一步 | 用户刷新任务与调用页面复验显示和交互；真实厂商查询仍须另行授权。 |

## 2. 目标、范围和执行边界

管理员能从一个异常任务或调用进入，看到所属账号、阶段、厂商请求关联、已知用量及安全失败原因，执行不会盲目重复收费的核对/清理重试，并看到最终处理结果。必须交付任务查询、调用查询、未知核对、操作审计；不包含批量重发、手动伪造成功、充值/次数限额恢复、全文提示词日志或新生成引擎。普通用户继续仅查看本人任务，管理员可诊断不等于取得公开分享私有素材权限。

## 3. 已确认决策与待决事项

| 规则 | 来源 | 实施含义 |
|---|---|---|
| 取消平台制作次数限制 | 用户此前决定，C1 | 不加回一次额度；保留真实厂商调用量和未知费用 |
| 未知请求先核对 | 原制作计划、本契约C2 | 查询原taskId/回执；没有证据不能自动换键重发 |
| 不记录秘密和业务正文 | 数据库6.27、需求FR-DATA-01 | 只显示安全摘要/ID/用量，成本未知为null |
| 不能人工把未完整产物标成功 | 已完成角色契约 | 核对厂商调用事实不直接更改动作验收/发布 |

无新供应商待选项。无法自动查询的UNKNOWN允许管理员提交核对依据，但只调整调用事实；生成结果恢复继续经过原处理校验。

## 4. 现有代码链路与强制复用

| 来源→落点 | 当前行为 | 复用及差异 |
|---|---|---|
| AssetService.listGenerationTasks/readGenerationTask→AssetMapper | 本人任务/阶段 | 新管理员查询入口明确管理员身份和account过滤，不解除普通接口隔离 |
| AvatarProductionService.production/recover、GenerationWorkerService.saveReceipt/submitSucceeded/submitTerminal | 动作、尝试、厂商回执和结果入库 | 复用核对/恢复规则，记录操作者与实际资源账号；不得用管理员ID冒充owner |
| ruoyi-media/worker/generation.py:_obtain_image → qwen_image.query_async/recover | 查询任务/下载原结果，不重发有证据的生成 | 直接复用，只增加安全事实输出与审计 |
| session/runtime/TtsAdapterSupport、SpeakOnlyRuntimeService→PersistentRuntimeStore | 语音发送/成功/失败与段状态 | 补C5可靠调用事件，保留供应商明确失败与未知区别 |
| p_call_record/p_usage_daily/p_outbox/inbox | 数据库6.27/28、6.33/34既有契约 | 复用唯一operation_key及差额汇总，不能追加同义统计流水 |
| 若依操作日志 | 基础审计框架 | 白名单记录actor/subject/operation/reason，不记录原始请求/签名URL |

## 5. 完整业务流程

| 步骤 | 管理员输入/触发 | 处理与持久输出 | 页面反馈/失败恢复 |
|---|---|---|---|
| 1 | 按账号、时间、任务阶段筛选 | 分页读任务/step/attempt及安全摘要 | 不因只有前五动作成功就显示整套成功 |
| 2 | 打开任务或调用 | 展示业务ID、厂商请求ID/taskId、状态、可得用量、时间 | UNKNOWN与失败分开；缺成本显示未知 |
| 3 | 点击核对原结果 | 稳定requestId→受权限的原任务查询/回执恢复 | 显示处理中；无taskId提示需要人工依据，不自动重发 |
| 4 | 提交人工核对依据 | 锁调用/attempt，留操作者、时间、证据摘要；状态单调更新 | 不修改视觉确认和资产发布 |
| 5 | 数据恢复或清理重试 | 调用既有下载/加工恢复或生命周期清理接口 | 已保存原图不重生成；租约冲突刷新 |
| 6 | 查看处理结果与用量 | p_call_record唯一事件、差额汇总 | 合成/生成事实与页面是否播放、产物是否发布分开 |

## 6. 接口、数据与状态契约

新增目标，A+platform:operations:read/reconcile，C2外壳/路由；普通任务接口不改变权限。查询时间from/to可省略，默认近7天，最大区间30天；历史UNKNOWN单独状态筛选可跨该默认窗口查询，不能因此隐去未结事项。

| 接口/用途 | 请求 → data |
|---|---|
| GET /api/v1/admin/generation-tasks | accountId/status/errorCode/from/to/pageNum/pageSize → 安全摘要分页 |
| GET /api/v1/admin/generation-tasks/{taskId} | → task、steps[]、attempts[]、allowedOperations[]；隐藏原图/原始回执URL |
| GET /api/v1/admin/call-records | accountId/capability/status/taskId/sessionId/时间分页 → 调用摘要 |
| GET /api/v1/admin/call-records/{callId} | → 调用事实及核对记录/ETag |
| POST /api/v1/admin/generation-tasks/{taskId}/attempts/{attemptId}/reconciliations | reason、Idempotency-Key、If-Match → operationId/status=PROCESSING/COMPLETED |
| GET /api/v1/admin/operations/{operationId} | → status/safeMessage/resourceId，不含凭证 |
| POST /api/v1/admin/call-records/{callId}/reviews | reviewedStatus、evidenceNote、可得成本字段、If-Match、Idempotency-Key → callId/status/reviewedAt |

| 字段 | 类型/来源与消费 |
|---|---|
| taskId/attemptId/callId | ID字符串，分别来源对应实体；后台按关系联表，不能拿attempt当task |
| operationKey | 后端稳定调用标识，客户端只读；C5原发送→本地持久→Outbox→call唯一行→统计 |
| providerRequestId/providerTaskId | 只读可空字符串，厂商回执；request追踪/task查询各自用途，不互换；凭证和带签名下载地址不输出 |
| status/reviewedStatus | status读STARTED/SUCCEEDED/FAILED/UNKNOWN/CANCELLED；核对仅允许证据确认SUCCEEDED/FAILED/CANCELLED；调用成功不自动发布资产，已确认终态不能无依据回退 |
| inputChars/imageCount/audioDurationMs/usageAvailable | 数量可空非负，usageAvailable为非空布尔；来自真实适配结果，缺数量不填0，前端不决定计量 |
| costAmount/currency/costSource | 金额可空十进制串，币种可空ISO字符串，来源为UNKNOWN/PROVIDER/CONSOLE/ESTIMATED；人工依据最多标CONSOLE，估算标ESTIMATED，不冒充厂商账单 |
| reason/evidenceNote | 必填1～500/1～1000安全文本；审计只保存安全依据，不存秘密、正文或原始供应商响应 |

例：POST attempts/5101/reconciliations {"reason":"查询原厂商任务结果"} → {"operationId":"6101","status":"PROCESSING"}；该调用不含“再生成”参数。call详情示例 {"callId":"7101","status":"UNKNOWN","usageAvailable":false,"costAmount":null,"costSource":"UNKNOWN"}。

数据：已有p_generation_task/step/attempt只读或复用原恢复服务；p_call_record/usage_daily由C5事件维护；attempt已有reviewed_by/at/note依实际迁移核对复用；调用审核若无足够字段则追加小迁移及安全审计，不修改旧DDL。不保存第二份正文账本。
taskId筛选通过task→step→attempt及C5稳定operationKey连接p_call_record；该表没有task_id，不凭空查询不存在列。providerTaskId从关联attempt/受保护回执摘要读取，不冒充provider_request_id。任务详情的每个attempt返回其etag，核对命令的If-Match针对该attempt；call详情ETag针对call。无revision列时由受保护业务快照计算，更新锁内复核，不只比时间戳。
状态：STARTED→SUCCEEDED/FAILED/UNKNOWN/CANCELLED，UNKNOWN→证据支持的终态；任务状态仍由既有制作引擎计算。核对操作PROCESSING→COMPLETED/FAILED可用p_api_idempotency+outbox及结果关联表达，不为页面新建通用工作流引擎。
调用更新、日汇总差额、幂等/审计同事务；上游查询在事务外。一次核对自动查询最多5次退避，超过后显示待人工，不自动清UNKNOWN。下载恢复仍受原URL期限；过期明确不能恢复，不能自动付费替代。

不变量O1读取与执行权限分别校验；O2核对/恢复不触发新生成；O3重复事件不重复累计；O4成功调用不等于成功资产/播放；O5成本未知不虚构。事件详情见C5，跨账号人工操作actor和subject分别持久记录。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整行为/落点 | 不变量/静态退出 | 交接 |
|---|---|---|---|---|
| O-A | C2/C5、既有attempt | 可靠调用事件→库→跨账号安全查询→Vue列表/详情 | O1/O3/O5；唯一键/事务/脱敏/权限两端齐全 | 调试错误诊断 |
| O-B | O-A、既有query_async/recover | 管理员核对→原尝试恢复→状态展示/审计 | O2/O4；没有新生成POST分支，旧租约拒绝 | 制作异常收尾 |
| O-C | O-A、生命周期L-C | 清理异常→同一清理操作重试→最终展示 | O1/O3；不向生成Worker发送删除事件 | 运维完成 |

执行记录：O-A 已将 Worker 内已持久的生成状态直接写入唯一 `generation:{attemptId}` 调用事实；TTS 在 session 事务中写 `s_operation` 与 `s_outbox`，私有投递器使用受保护 system 内部入口并由 `p_inbox` 去重，system 按差额更新日汇总。O-B 的管理员命令先锁目标 attempt、检查 ETag 与幂等记录，再只复用原 `recover` 路径；没有厂商任务/回执证据时拒绝，人工调用核对仅允许 UNKNOWN→终态。O-C 复用已完成的公共资产生命周期清理重试与最终状态展示，未向生成 Worker 增加删除消息。

## 8. 主代理静态逻辑验证

| 要求 | 生产方→消费方 | 当前结论/辅助检查 | 用户体验 |
|---|---|---|---|
| O1/O2 | 管理员命令→真实owner映射→原恢复方法→provider查询 | `OperationsService.reconcile` 先锁 attempt 并以真实 `accountId` 调原恢复；无 task/receipt 证据拒绝，路径没有新生成 POST 分支；控制器同时检查管理员与 `platform:operations:reconcile`。 | 普通用户无权限；核对只安排原任务恢复。 |
| O3/O5 | TTS/media证据→C5事件→call唯一键→日汇总 | generation 在 Worker 状态事务中以 `generation:{attemptId}` 写事实；TTS 稳定分段 ID→`s_outbox`→私有入口→`p_inbox`→唯一 `p_call_record`，旧/新事实差额更新日汇总；未知成本保持 `null`/`UNKNOWN`。 | 已有调用可查；未知不显示为零。 |
| O4 | 核对调用终态→动作结果校验/人工确认 | 调用人工核对只允许 UNKNOWN→SUCCEEDED/FAILED/CANCELLED，且审计依据写独立表；生成核对仍经原结果加工/人工确认链路，不改资产发布状态。 | 调用成功不直接发布角色或宣称可播放。 |

## 9. 用户终点验收与修复

使用已有成功/失败/UNKNOWN任务，不为了测试异常额外付费。管理员查到不同账号的安全摘要；用户不能访问管理接口；有真实taskId的核对查询原请求；没有凭据的未知状态保持需核对；相同操作重试不重复累计；费用缺失显示未知；清理失败可查看并重试。需真实核对时用户单独授权，不把本计划当云查询授权。修复聚焦对应查询/事务/状态，再由用户复验。2026-09-22 代理已在用户授权的本地环境启动 system 并核对历史卡死任务的数据库终态；未发起新厂商请求，管理页面终点操作由用户复验。Git提交、推送与合并仍须用户单独授权。

2026-09-22 本地验收修复：“调用事实”列表使用 `JdbcTemplate.queryForList` 返回数据库原列名 `account_id`，而摘要组装按 `accountId` 取值，导致 `Long.toString` 空值拆箱异常。已改为和详情接口相同的显式行映射，并以 H2 真实 snake_case 列表回归覆盖 `callId/accountId/etag`；`OperationsServiceTest` 与 system 受影响模块编译通过，服务已重启并就绪，页面终点由用户刷新复验。

## 10. 跨模块交接与项目贯通

[播报](2026-09-19-user-console-speech.md)与完成的制作链提供调用事实；本计划只负责可靠归集、管理员诊断和安全核对；[生命周期](2026-09-19-admin-public-asset-lifecycle.md)提供清理操作。不能为“运维可点恢复”重新改变制作协议，不能靠管理员手动改数据库掩盖未闭合的用户流程。
