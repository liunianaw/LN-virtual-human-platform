# 管理员公共资产下架、紧急停用与安全删除执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.2。确认状态：用户于 2026-09-21 确认按本计划实施；本轮只做代码与静态代理逻辑验收，不启动服务、不迁移数据库、不调用 COS 或外部 API。

## 1. 当前执行状态

| 项目 | 事实 |
|---|---|
| 阶段/基线 | 以 2026-09-21 当前源码和 `b19888a` 为基线；L-A～L-C 已完成静态实现。 |
| 已有能力 | 新增统一 PublicAssetLifecycleService、管理员 API/最小管理页、V9 清理状态迁移、会话引用账本、停用撤销消费者及带租约的清理执行器。 |
| 静态/用户结论 | `mvn -B -ntp -pl ruoyi-modules/ruoyi-system -am compile` 与 `npm run typecheck` 通过；未启动服务、未执行迁移、未调用 COS/API。 |
| 用户终点验收 | 尚未执行：下架旧绑定、紧急停用活动会话、引用删除冲突、实际对象删除与失败重试均由用户以测试资产验收。 |
| 下一步 | 用户完成第9节终点验收后，按现象修复并再次静态复核。 |

## 2. 目标、范围和执行边界

管理员从公共角色/声音详情查看引用，选择普通下架、紧急停用或无引用删除，直到用户消费端限制正确生效、允许删除的资源清理完成。必须交付影响提示、状态转换、可靠通知、受引用保护的清理。制作/预览/发布沿已完成角色能力；声音发布由声音计划负责。不是一键清桶、批量清历史或重置数据库。计划本身不授权实际下架、停用和删除；遵C5。

## 3. 已确认决策与待决事项

| 规则 | 来源 | 实施结论 |
|---|---|---|
| 下架与停用不同 | FR-AV-06、数据库6.11 | UNLISTED不影响已有引用；DISABLED覆盖全部旧快照 |
| 永久删除被引用时禁止 | 数据库9.2 | 本人也不能绕过APP_CURRENT/SESSION/GENERATION引用 |
| 旧版本及复用文件不可误删 | 不可变版本与已完成单动作复用 | 对p_file逐项查所有关联，不按角色路径批量删除 |
| 用户私有声音延期 | 本轮决定 | 此计划不开发私有Voice生命周期；私人角色删除由资产库入口共用清理内核 |

不新增自动到期下架或自动删除策略。恢复启用DISABLED公共资产的业务规则不在本次，不能自行把停用等同普通下架再发布绕过；后续若需恢复须专门明确。

## 4. 现有代码链路与强制复用

| 来源/方法 | 当前行为 | 复用与差异 |
|---|---|---|
| [AvatarPublicationServiceImpl](../../../RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/asset/service/impl/AvatarPublicationServiceImpl.java):changeOfficialStatus | 锁资源、更新状态、写AVATAR_STATUS_CHANGED | 保留事件/状态，补ETag/幂等、对应消费者，不另建同义事件 |
| deleteAvatar→AvatarPublicationMapper.countActiveReferences/countApplicationReferences/countRecoverableSessionReferences | 删除前检查引用、转DELETING、写AVATAR_DELETE_REQUESTED | 共用锁约束；补消费、文件去重清理和最终状态 |
| ObjectStorage.delete/CosObjectStorage | 可删除指定对象 | 直接复用；限定已校验p_file.object_key，不接受客户端对象路径 |
| `ruoyi_media.worker.__main__ --consume-rabbit` | 当前消费生成任务，不是通用删除队列 | 不把删除事件塞进生成器；system/job中添加有租约/去重的删除执行 |
| session/runtime与system/voice/DebugSessionService | 部分当前状态校验和过期撤销 | 加状态事件消费者、活动连接关闭、音频读前检查；不可仅改列表 |

## 5. 完整业务流程

| 步骤 | 输入/操作 | 处理与持久输出 | 反馈/失败恢复 |
|---|---|---|---|
| 1 | 管理员选公共资产 | 查询主状态/版本/引用计数、ETag | 显示“下架不影响已有使用”“停用会阻断” |
| 2 | 普通下架并填原因 | 锁行校验PUBLISHED→UNLISTED，审计/Outbox同事务 | 从新绑定目录移除，原应用仍可用 |
| 3 | 紧急停用并确认 | DRAFT/PUBLISHED/UNLISTED→DISABLED，可靠撤销事件 | 活动会话停止，新请求/音频读取拒绝 |
| 4 | 申请删除 | 与新引用锁同主记录；有引用409；无引用→DELETING+事件 | 202删除中，不宣称对象已消失 |
| 5 | 清理执行 | 查询全部文件使用方，删安全对象/版本关联，更新清理状态 | 部分失败保持删除中，管理员可重试 |
| 6 | 完成 | 对象与关系安全清理后DELETED/deleted_at | 删除完成，无法获取新签名；审计留必要元数据 |

## 6. 接口、数据与状态契约

C3是唯一共享引用/删除事务定义。目标新管理接口下列均A+platform:asset:manage，C2约定；{kind}仅avatars或voices，不接受任意表名。

| 目标接口及用途 | 请求 → data |
|---|---|
| GET /api/v1/admin/{kind}/{resourceId}/references；执行前查看影响 | 分页 → counts{applications,sessions,generations}、items安全摘要、ETag |
| POST /api/v1/admin/{kind}/{resourceId}/unpublish；普通下架 | reason必填1～500、If-Match、Idempotency-Key → resourceId/status=UNLISTED/revision |
| POST /api/v1/admin/{kind}/{resourceId}/disable；紧急停用 | reason同上、acknowledgeImpact=true、If-Match、Idempotency-Key → status=DISABLED/revision |
| DELETE /api/v1/admin/{kind}/{resourceId}；申请无引用删除 | If-Match、Idempotency-Key → 202 resourceId/status=DELETING |
| POST /api/v1/admin/{kind}/{resourceId}/cleanup-retries；重试已删除中的物理清理 | {}、If-Match、Idempotency-Key → status=DELETING，不再发生成 |
| GET /api/v1/admin/{kind}/{resourceId}；查询完成 | → status/cleanupStatus/lastErrorCode/revision/ETag |

既有 /system/asset/admin/avatars/{id}/unpublish、disable、/system/asset/avatars/{id} DELETE保留兼容并调用同一服务；迁移前端时不能把缺少新增头的旧请求直接当“校验通过”，旧入口也在服务事务内做相应并发和授权检查。

字段来源：resourceId来自目录主资源ID，不能是版本ID；reason来自管理员输入只进入受控审计；revision由主表返回；counts来自按holder去重的引用事实，不能由前端提交。示例 POST voices/3001/unpublish {"reason":"暂不接受新使用"} → {"resourceId":"3001","status":"UNLISTED","revision":"4"}；已有应用保留voiceVersionId而非自动换声音。

状态：PUBLISHED→UNLISTED；DRAFT/PUBLISHED/UNLISTED→DISABLED；合法非终态且无引用→DELETING→DELETED。DELETING期间拒绝新绑定和签名生成；失败保持原删除状态并记录可重试摘要。UNLISTED再次发布仅走对应已定义发布流程；DISABLED不可用普通发布绕过。

复用p_avatar/p_voice及版本、p_resource_reference/p_file/p_outbox/p_inbox/p_job_lease。新增Voice事件与必要清理字段追加迁移；已存在Avatar事件类型沿用。清理租约、次数及下次重试持久化；过期Worker结果按epoch拒绝。数据库状态/事件/幂等一事务，对象删除在外；对象不存在成功、有共享引用不得删除。重试限制C5。

不变量L1下架不误停旧使用；L2停用阻止旧授权继续消费；L3绑定与删除同锁互斥；L4有共享文件引用不删；L5只在实际清理完成后DELETED。已有签名COS URL无法保证瞬间撤回已下载字节：停用必须拒绝新授权/读取并关闭平台活动播放，残余签名有效期如实提示，不承诺收回用户已下载文件。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整行为/落点 | 不变量/静态退出 | 交接 |
|---|---|---|---|---|
| L-A | C3、应用A-B | ✅ `GET /api/v1/admin/{kind}/{resourceId}/references`、下架与 Voice 同一生命周期内核；会话创建时写 SESSION 引用 | L1/L3；状态、ETag、幂等、引用计数在同一平台库事务内检查 | 资产库/应用 |
| L-B | 播报D-A/D-C | ✅ 状态事件写 Outbox；`PublicAssetRevocationWorker` 以 Inbox 去重后关闭受影响 DEBUG Session | L2；新会话准入拒绝 DISABLED，已写 SESSION 引用的活动会话可靠重试撤销 | 用户停止体验 |
| L-C | L-A、现有ObjectStorage | ✅ 删除转 DELETING、V9 持久清理状态与租约、共享文件排除、失败退避、重试 API/UI | L3～L5；只使用服务端 p_file.object_key，完成存储删除后才标 DELETED | 私有角色删除复用 |

## 8. 主代理静态逻辑验证

| 不变量 | 所读生产方→消费方 | 当前结论/辅助检查 | 体验项 |
|---|---|---|---|
| L1/L2 | 生命周期内核→DebugSessionService 准入/SESSION 引用→Outbox→撤销 Worker→session close | 代码链路已读；UNLISTED不改历史引用，DISABLED事件只处理活动 SESSION；未做真实会话体验 | 新绑定被拒，旧使用按两种状态区分 |
| L3/L4 | 引用详情/删除事务→SESSION/APP_CURRENT/GENERATION 计数→清理器→p_file全引用 | 代码链路已读；删除先 409 引用，再 DELETING；清理器排除目标外共享文件 | 使用中删除被拒 |
| L5 | DELETING→清理租约→ObjectStorage.delete→p_file→DELETED | 代码链路已读；存储异常保持 FAILED/DELETING并退避，手工重试只重置为 PENDING；未真实删对象 | 失败显示删除中、重试后完成 |

## 9. 用户终点验收与修复

用可处置的公共测试资产及一个引用应用：下架后原应用继续、新应用不能选；紧急停用后活动播报停止；仍有引用删除被拒；退出/删除可恢复调试并解除应用引用后允许删除。实际删除只在用户明确选择的测试资产上执行；共享文件场景用已有数据或受控离线证据，不要求重新付费制作。失败由主代理修复双方锁/消费者后静态复查，用户复验。当前未执行；Git遵C1。静态实现按结果提交；保留用户原有 `logs/` 内容。

## 10. 跨模块交接与项目贯通

[个人资产库](2026-09-19-user-asset-library.md)复用引用检查和删除执行但不授管理员权限；[应用](2026-09-19-user-application-config.md)/[播报](2026-09-19-user-console-speech.md)负责引用建立和释放；[任务管理](2026-09-19-admin-task-operations.md)展示清理异常。生产删除事件不等于删除完成，必须保留消费端缺口到真正实现并体验通过。
