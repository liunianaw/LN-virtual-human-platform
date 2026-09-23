# 用户应用创建、配置发布与版本采用执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：1.0。确认状态：用户于2026-09-22确认本计划，作为后续新会话的实施依据；本轮仅校准计划文档，未执行编码或运行时操作。

## 1. 当前执行状态

| 项目 | 事实 |
|---|---|
| 当前阶段/版本 | A-A～A-C 静态实现完成（1.0）；未启动服务或执行数据库迁移。 |
| 已有能力 | 新增应用列表/详情/创建/发布/启停、资源选择、Vue页面、菜单、网关路由和应用停用撤销消费者；复用既有 p_application/p_app_config/引用表及 DEBUG 绑定。 |
| 主代理静态结论 | Java `compile`、Vue `typecheck` 与离线结构检查通过；发布不指向 TTS/Relay/生成调用。 |
| 用户验收/修复 | 尚未执行真实页面、登录、数据库迁移、DEBUG会话或播报验收；角色制作已由用户确认完成。 |
| 下一步 | 用户按第9节执行终点体验和迁移后验收；任何失败只沿直接消费链修复，不用手写种子应用绕过页面。 |

## 2. 目标、范围和执行边界

用户创建自己的应用，选择已有公共或私有角色及官方声音，发布固定配置，进入平台内调试；修改形成新配置，新会话用新版本、旧会话不漂移。必须交付列表/详情/创建/配置发布/版本选择/停用；不开放外部Secret、业务Session、CHAT、Relay/私有Voice、ASR、Skills和页面感知。主代理静态检查，用户体验，边界见C1/C5。

## 3. 已确认决策与待决事项

| 决策 | 依据 | 落地 |
|---|---|---|
| 应用分别绑定角色与声音版本 | 需求FR-APP-01/02 | 不把声音写入角色、不只保存主资源ID |
| 官方更新不自动切换应用 | FR-AV-06 | 用户明确采用新版本并发布配置 |
| 下架不影响原绑定，停用覆盖快照 | FR-AV-06、FR-APP-02 | 遵C3，在新配置中保留原依赖和新增依赖分别检查 |
| 私有语音延期 | 本轮用户决定 | 只列官方声音；保存时服务端也拒绝新Relay绑定 |

已发现上位表示差异：已删除接口文档的历史§7.1创建返回DRAFT，数据库6.15没有DRAFT枚举。本稿保持数据库ACTIVE+current_config_id=null，公开配置状态configStatus=UNCONFIGURED；不用不存在的数据库状态。此映射是目标契约澄清，确认后以本计划第6节为唯一目标定义，不能默默写入DRAFT。无其他业务待决项。

## 4. 现有代码链路与强制复用

| 来源→落点 | 事实与保留行为 | 适配/新增 |
|---|---|---|
| [DebugSessionService.binding](../../../RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/voice/DebugSessionService.java) | 查询ACTIVE应用、SPEAK_ONLY、发布Voice及完整角色 | 复用消费形状，补统一引用/禁用校验，不另写第二套绑定规则 |
| AvatarPublicationService.requireOwnedPublishedVersion、public/detail查询 | 检查本人/官方角色版本 | 提供角色选择器与绑定校验，区分已有下架引用续用 |
| VoiceService.read及官方目录目标 | 官方声音版本输出 | 使用声音计划目录，不要求用户填写数据库ID |
| p_application/p_app_config/p_resource_reference（数据库6.15～6.18） | 已有不可变配置及引用设计 | 新增ApplicationController/Service/Mapper及Vue应用页面，沿用表不新建同义配置表 |
| 若依菜单/身份、C2请求适配 | 框架已有 | 新菜单权限和API链路；不得浏览器传accountId |

## 5. 完整业务流程

| 步骤 | 用户操作与输入来源 | 处理/持久输出 | 反馈/失败去向 |
|---|---|---|---|
| 1 | 创建名称、说明 | p_application账号取C，ACTIVE、current_config_id=null | 显示未配置，禁止进入调试 |
| 2 | 从目录选角色及明确版本、官方声音版本 | 读取详情/状态，不发生生成合成 | 下架/无权资源不给新选项 |
| 3 | 点击发布配置 | 锁应用/依赖→完整授权校验→插入config→指针/引用/幂等同事务 | 返回configVersionId/新ETag；失败保留旧配置 |
| 4 | 打开调试 | 传applicationId，不允许前端覆盖绑定 | 跳至播报计划；未配置则提示补全 |
| 5 | 采用角色或声音新版本 | 读取当前配置作为表单初值，用户明确选版本再发布 | 旧Session固定旧configVersionId |
| 6 | 停用应用 | epoch+状态+撤销事件同事务 | 旧会话失效；重新启用也不复活旧grant |

## 6. 接口、数据与状态契约

新增目标，C及platform:application:read/write；浏览器/gateway/system同路径，C2外壳。

| 接口/时机 | 请求 → 响应data |
|---|---|
| GET /api/v1/applications；列表 | 分页/status → items含applicationId/name/status/configStatus/currentConfigVersionId |
| POST /api/v1/applications；新建 | name、description；Idempotency-Key → applicationId/status=ACTIVE/configStatus=UNCONFIGURED/currentConfigVersionId=null、ETag |
| GET /api/v1/applications/{applicationId}；编辑/调试前 | → 主表信息、当前配置及versions摘要、ETag |
| GET /api/v1/applications/{applicationId}/config-versions/{configVersionId}；历史查看 | → 本应用不可变配置 |
| POST /api/v1/applications/{applicationId}/config-versions；原子发布 | ConfigInput、If-Match、Idempotency-Key → applicationId/configVersionId/versionNo/configHash/capabilities/effectiveLimits |
| POST /api/v1/applications/{applicationId}/status；启停 | status=ACTIVE/DISABLED、reason1～500、If-Match、Idempotency-Key → status/revision |

ConfigInput精确字段：

| 字段 | 类型/必填/来源 | 校验/映射 |
|---|---|---|
| mode | 必填SPEAK_ONLY | p_app_config.mode，不接受CHAT |
| avatarVersionId | 必填ID；角色选择器 | 查version→avatar→owner/status，八动作正式包；存avatar_version_id |
| voiceVersionId | 必填ID；官方声音目录 | 查version→voice→服务，OFFICIAL及有效；存voice_version_id |
| llmRelayVersionId、asrRelayVersionId | 两个独立字段，均省略或null | 本次不启用；任一非空422 |
| skills | 省略或[] | 不创建p_app_skill |
| contextPolicy | 必填对象，仅{enabled:false} | 服务端补明确禁用规范值到快照，不接受开启 |
| runtimeLimits | 可省略或{}；本次不开放任意调参 | 按C4.4生效字段返回effectiveLimits并冻结；不是厂商收费额度 |

例：POST config-versions {"mode":"SPEAK_ONLY","avatarVersionId":"22001","voiceVersionId":"3101","contextPolicy":{"enabled":false}} → data {"applicationId":"4001","configVersionId":"4101","versionNo":1,"configHash":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","capabilities":["avatar:read","speak:write"],"effectiveLimits":{"maxTextCodePoints":8000,"maxCodePointsPerSegment":200,"maxConcurrentSegments":2,"maxBufferedSegments":2,"maxAudioBytes":5242880,"ttsTimeoutSeconds":30,"turnTimeoutSeconds":300,"playbackWaitSeconds":60,"temporaryAudioTtlSeconds":900}}。ID/hash为示例，实际hash由规范化配置计算；有效限制定义在C4.4。

应用ID产生→p_application→路由；配置ID产生→p_app_config→current_config_id→DEBUG冻结→s_session.app_config_id，绝不能互换。versionNo为普通int。约束唯一(application_id,version_no)，旧config不可UPDATE。状态ACTIVE未配置→ACTIVE已配置→DISABLED；停用后启用需配置仍有效，新会话重新授权。

引用锁序、APP_CURRENT替换与SESSION保护严格遵C3。发布/引用/指针/revision/幂等同事务；发布无外部生成。超时同键查结果；无权403、不可用422/409、引用或revision冲突按C2。追加菜单及必要迁移，保留旧应用数据、既有Relay配置不批量改写；此类旧配置在新UI标只读“不在本轮新增配置范围”。

不变量A1账号不可伪造；A2绑定版本不可变；A3应用绑定与删除互斥；A4官方声音以外不新增绑定；A5保存发布不发生TTS。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整行为/落点 | 不变量/静态退出 | 交接 |
|---|---|---|---|---|
| A-A | C2、个人目录、声音目录接口契约 | 页面创建→选择→发布→查询；新增Application服务与Vue页；无官方已发布声音时提示等待发布，不造种子绕过 | A1/A2/A4/A5；静态链路可先完成，真实使用待声音V-C | DEBUG固定配置及C4专用试听入口 |
| A-B | C3 | 发布新版本→释放旧APP_CURRENT但保留SESSION→旧版本可查 | A2/A3；同锁资源引用及并发删除闭合 | 生命周期 |
| A-C | 播报授权/撤销 | 停用→旧会话拒绝→新启用重新授权 | A1/A2；epoch持久变化、事件消费者接通 | 用户调试 |

## 8. 主代理静态逻辑验证

| 不变量 | 实际走查点 | 当前结论/辅助检查 | 用户终点 |
|---|---|---|---|
| A1/A4 | 新DTO→C身份→角色/Voice联表查询 | `ApplicationController`不接收账号；服务端仅允许本人/官方已发布 Avatar 与已发布官方 Voice，Relay字段拒绝；Java编译、Vue类型和离线结构检查通过 | 双账号不能选他人私有角色 |
| A2/A3 | 发布事务→引用表→DebugSessionService.binding→删除事务 | 同事务锁应用→校验依赖→插入不可变config→替换指针→释放旧APP_CURRENT→写新引用；SESSION引用不释放，既有删除保护复用 | 更新应用不改变旧会话，引用资源不可删 |
| A5 | 发布调用链→Outbox类型 | 发布服务只访问Mapper；无TTS/Relay/生成适配器。启停仅写 `APPLICATION_STATUS_CHANGED`，消费者关闭既有DEBUG Session | 保存配置不发起收费调用 |

## 9. 用户终点验收与修复

准备普通用户A/B、一个公共角色、A的私有角色和已发布官方声音。A创建应用→绑定公共角色播报→改用自己私有角色→查看旧配置→尝试使用B私有角色被拒→管理员下架公共角色时旧绑定仍可用、新绑定被拒→停用应用立即停止。绑定/保存本身不付费，真实播报按对应计划用户执行。失败保留原配置，修复后复验绑定及其直接消费链，不重做角色制作。当前未执行；Git遵C1。

## 10. 跨模块交接与项目贯通

[个人资产](2026-09-19-user-asset-library.md)/[官方声音](2026-09-19-admin-official-voices.md)供版本；本计划写固定配置和APP_CURRENT；[播报](2026-09-19-user-console-speech.md)写SESSION引用并消费；[生命周期](2026-09-19-admin-public-asset-lifecycle.md)用同锁保护删除。没有平台内实际播报之前，只能说配置能力完成，不能说应用使用闭环完成。

2026-09-22：本地发布验收发现 `operation_id` 原为 64 字符，而应用发布会写入 `application:{应用ID}:{Idempotency-Key}`；合法的 64 字符幂等键可使该值达到约 96 字符。新增 `V15__expand_resource_reference_operation_id.sql` 将共享引用表字段扩至 128，覆盖发布与 DEBUG 会话的同一追踪字段，不截断幂等键。
