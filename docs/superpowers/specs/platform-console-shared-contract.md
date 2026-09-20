# 平台内使用共享契约

依据：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)。

日期：2026-09-19。版本：0.1，讨论稿。适用于本轮七份计划；不替代已完成角色制作契约。此处描述目标，不能据此宣称接口已经存在。

2026-09-20续编：用户确认有意移除独立接口说明书，接口约定纳入各业务计划；复杂共用接口集中于本契约，引用方不重复定义。历史依据为15bb6ca中的接口稿，仅用于迁移核对，不是执行前需要恢复的文件。本次只承接平台内范围；外部开发者接口及延期能力留待对应计划，不因未迁入本文而取消需求。

## C1. 范围、事实与实施顺序

- 用户确认角色制作已经完成；仅作为输入能力复用，不重新生成、不重新安排制作验收。既有制作流程的未知结果及恢复规则保持。
- 用户明确延期私有语音转接配置、私有声音配置与试听。已有代码和数据保留，不删除；本轮应用的新配置只选择官方声音，不要求用户先提供 Relay。
- 本轮包含官方服务管理、官方声音、平台任务与调用管理、公共资产生命周期、个人资产库、应用配置、平台播报调试。
- 外部开发者 Key/Secret 接入、外部示例、AI/ASR/Skills/页面感知、Webhook、声音克隆均不在本次。实现平台调试所必需的运行时连接和播放器复用，不等于开展完整外部接入。
- 不恢复已取消的平台制作次数限制，不引入新的购买/充值或收费套餐。厂商调用事实、实际用量、并发安全和重复调用防护仍保留。真实调用授权和预算必须在执行前明确，历史预算不能当作无限续期授权。
- 当前事实以 2026-09-19 工作区为准：基于 15bb6ca，存在未提交业务实现及迁移。旧计划执行记录可能滞后；本轮不动这些代码，也不把新增计划标成编码完成。
- 编码依赖：服务配置 → 官方声音保存；资产目录 → 应用配置；这两支到位后 → 共用播报链路 → 官方试听/发布与应用调试验收。引用保护从应用配置开始同步实现，不能等删除模块才补。调用事实记录随发送链路接入，管理页面可后做。
- 每份计划按第7节能力切片推进。计划确认后统一提交；编码完成按阶段提交；修复仅在用户指定时提交。不另维护清单、不派子代理、不自动编写测试。

## C2. 路由、身份和字段通则

### 已有事实

现有角色浏览器调用 /system/asset/**，gateway 去掉 /system 后到 system 的 /asset/**，保留 AjaxResult 数值 code。Voice/DEBUG 控制器内部已有 /api/v1/**，但仓库 gateway 配置当前仅有 /system/** 等旧路由，不能把内部地址写成现成公网接口。前端现有 request.ts 与 api/asset/avatar.ts 保留，不全局改外壳。

### 本轮目标

- 新增管理 API 使用本契约C2的 /api/v1/**；gateway 按明确前缀转发到 system，路径保持不变。/api/v1/runtime/** 和 /api/v1/realtime 单独路由到 session；内部 /internal/v1/** 不对公网转发。
- 本轮管理入口只开放 C（后台登录）及管理员 A，不实现 M/B 开放接入。管理员必须同时有管理员角色和对应按钮权限，普通用户身份来自登录；公开 DTO 不接收 accountId、operatorId 或业务身份。
- 拟新增菜单权限：platform:service:read/write/check、platform:officialVoice:read/write、platform:operations:read/reconcile、platform:asset:read/manage、platform:application:read/write/debug。在若依菜单迁移中明确授予角色；不把菜单隐藏当授权。旧 system:asset:* 入口保持原校验。
- 新接口采用本契约C2 的 code/message/requestId/data/error 外壳、实际 HTTP 状态；专用 console API 封装，不进入若依正文重复提交缓存。既有 Voice 裸 DTO、DEBUG 响应应在新增前端接入前统一适配，旧已确认使用方单独兼容，不混用两种 code。
- 创建、发布、核对及其他有副作用命令带 Idempotency-Key；编辑/生命周期带 If-Match，缺失428、旧版本412；同幂等键异参409。成功先查已完成幂等记录，再比较当前 revision。p_api_idempotency/s_api_idempotency 按本契约C2，不另建去重账本。
- 列表 pageNum>=1、pageSize 默认20最大100，返回 items/total/pageNum/pageSize；排序固定 createdAt/id 倒序，筛选字段白名单。实体不存在或无权访问不泄露他人信息。
- 长整型 ID、revision、epoch 一律十进制字符串；时间 UTC RFC3339，页面转本地时区；费用 decimal 用十进制字符串或 null，null 表示未知，不是零。

| 公共字段 | 类型、必填及来源 | 持久化/传递/消费 |
|---|---|---|
| resourceId/versionId | 正整数十进制字符串，路径或创建响应必填 | 服务端分配→实体主键→列表/选择器→提交绑定→按资源类型、所属主对象和可见性验证，不跨类型互换 |
| revision/ETag | 查询返回字符串；写入 If-Match 必填 | 主表 revision→ETag→更新事务锁内比对→递增；前端不得自算 |
| Idempotency-Key | 1～64 ASCII 随机串，写命令必填 | 客户端一次操作稳定生成→服务端作用域+摘要→原结果；超时先查询、不换键重付费 |
| name/description | name 1～100；description 可省略或 null清空、最多1000 | 页面→DTO→主表；不得进入厂商请求当模型参数 |
| expiresAt | UTC 时间或 null（确无到期） | 服务端签名/授权有效期→消费者；过期重新授权，不延长旧地址 |
| safeMessage/errorCode | 安全摘要/稳定错误码；失败返回 | 去除厂商正文、凭证及签名URL；前端按错误码提供允许操作 |

只读 GET 可以有界重试，建议最多3次；写入超时查原操作。所有付费发送必须有稳定调用标识并记录结果未知窗口；不能用本地幂等声称供应商绝对一次执行。

### C2.1. 身份、响应和幂等的完整公共约定

- C是若依登录Token；A是具有相应权限的管理员C；S是仅当前Session有效的短期Token；I为固定服务身份和操作范围的内部凭证。HTTP使用Authorization: Bearer，路由只接受指定类型。网关清除外来账号/内部来源标头，运行时独立检查S，不能把全部/api/v1加入匿名白名单，也不能让若依过滤器把S当C。
- UTF-8 JSON、camelCase；拒绝未定义写字段。省略使用默认值，仅明确允许清空的字段可为null；PUT为完整替换。创建201、异步202、已完成200；文件二进制及WSS不用JSON外壳。
- 新接口完整成功示例：{"code":"OK","message":"已受理","requestId":"req-example-1","data":{"operationId":"6101","status":"PROCESSING"},"error":null}。失败示例（HTTP409）：{"code":"RESOURCE_IN_USE","message":"资产仍有引用","requestId":"req-example-2","data":null,"error":{"retryable":false,"details":{"applicationCount":1}}}。各计划中的data示例仅省略此固定外壳。
- X-Request-Id可选，校验后采用或由后端生成，头/体同时返回；追踪ID不承担幂等。400格式错误，401未认证/过期，403禁止操作，404不存在或安全隐藏，409幂等/引用冲突，412修订冲突，422参数或能力不符，428缺少If-Match，429当前运行上限，502上游错误，503依赖暂不可用。上游结果未知用PROVIDER_RESULT_UNKNOWN，不据此自动重试付费。
- 幂等scope为账号、操作、目标和必要签发身份组成的版本化规范对象SHA-256；request_hash为规范参数摘要，秘密先做keyed digest。记录与业务事务同提交，两库各写自己的去重表。至少保留24小时并覆盖执行期，PROCESSING及未完成补偿不得到期删除。任务/轮次业务键在实体保留期另行防重。
- 同键同参返回原实体和当前状态，未完成返回同一202；同键异参409 IDEMPOTENCY_CONFLICT。ETag可用revision或受保护业务快照摘要，必须锁内比对；重复成功操作优先返回原结果，不误报旧ETag。
- Token不进入通用幂等响应缓存。签发去重行与grant同事务；重试只能复用同一个有效grant，不放大授权。撤销去重、epoch和Outbox同事务；重复撤销不再次提升epoch误伤新授权。所有返回秘密的响应no-store。

## C3. 版本绑定、引用与删除互斥

权威业务依据：需求 FR-AV-06、FR-APP-02；数据库 §6.18、§9.2；架构 §10。本节由应用、调试、公共生命周期和个人资产计划共同消费。

1. 普通应用绑定准入：Avatar 必须已发布，属于本人或 OFFICIAL；本轮 Voice 只可引用已发布 OFFICIAL。唯一候选例外为C4管理员专用试听，仍建立同样引用保护，不能走普通应用入口。普通下架 UNLISTED 禁止新依赖，但现有配置和会话保留原固定版本。更换其他配置时，原有未变化引用可保留；不可借此新增另一应用的绑定。
2. 当前禁用的账号、应用、官方资产、服务或秘密覆盖历史快照，不能通过旧版本重新授权。写业务状态及撤销事件同事务；运行时每次取授权/开始段/读音频查当前有效状态，活动连接接收撤销通知并停止。
3. system 是资产/引用唯一写入方。应用发布事务按固定顺序锁应用主表，再按资源类型和ID排序锁依赖主表，复核状态，插入不可变 p_app_config，更新指针、revision、APP_CURRENT 引用和幂等结果。旧 Session 的 SESSION 引用独立保留。
4. 创建 DEBUG Session：system 锁同一应用及资源，生成稳定 referenceOperationId，保存依赖闭包 RESERVED；session 内部API以该操作创建 CREATING 记录；system CONFIRMED 后 session ACTIVE。失败按 operationId 双方查询核对，预留过期不是直接释放理由；任一端失联保持不可删除。
5. 删除同样锁目标主表，检查 APP_CURRENT/SESSION/GENERATION 中 RESERVED或CONFIRMED。存在则409 RESOURCE_IN_USE；无引用才转DELETING，阻止新引用并写Outbox。应用解绑不等于可恢复Session已释放。
6. 消费者用事件ID/inbox及业务状态去重，重新核验后清理无活跃holder的旧配置/版本关联及安全可删文件；旧版本复用相同 p_file 时检查全局引用，不能按对象前缀整片删除。
7. COS 删除事务外执行，失败保持 p_file.DELETE_PENDING 与主资源DELETING；对象已不存在视为成功。全部关联完成才DELETED和deleted_at。不先报成功再清理。
8. 状态变更与引用查询仅给调用者可见信息。用户看到自己的应用名称及引用数量；不得泄漏其他租户的会话或私有素材。

目标内部服务 I 接口（均为新增接线，不是浏览器入口）：

| 方法/内部路径 | 用途/字段 → 返回 | 责任 |
|---|---|---|
| POST /internal/v1/resource-references/reserve | 创建会话前；可信 accountId/applicationId/configVersionId、referenceOperationId → reservationId/state | system 校验并展开闭包，客户端不得提交自选资源集合 |
| POST /internal/v1/resource-references/{reservationId}/confirm | sessionId/referenceOperationId → CONFIRMED | system 校验与创建记录相符 |
| GET /internal/v1/resource-references/operations/{operationId} | 补偿核对 → state/sessionId（可null） | system 返回持久事实 |
| POST /internal/v1/resource-references/{reservationId}/release | sessionId、referenceOperationId、reason → RELEASED | 需session已不可恢复的可信证明，幂等；超时保持预留 |
| GET /internal/v1/debug-session-operations/{operationId} | system核对 → sessionId/status 或明确NOT_FOUND | session查自身库，不跨库SQL |
| POST /internal/v1/runtime-access/check | 可信session/grant/config绑定 → allowed/reason/currentEpochs | system查实时资产/服务/账号限制；session同时查自身grant；失败关闭，不能回退允许 |

引用ID由system生成，operationId由发起服务生成并在两库唯一关联；sessionId由session生成→确认引用→后续释放，禁止拿applicationId代替。

## C4. 官方声音试听与应用播报共用链路

复用 DebugSessionService/SessionDebugClient、ConsoleDebugGrantService、SpeakOnlyRuntimeService、官方TTS适配器、TemporaryWavStorage、avatar-sdk。禁止再建绕过会话鉴权和调用记录的“直接试听厂商”浏览器接口。

普通应用只绑定已发布声音。为避免“先公开发布才可试听”的循环，目标方案为：system为管理员维护专用的 VOICE_PREVIEW 应用（新增 p_application.purpose：USER默认/VOICE_PREVIEW；普通创建不可传入）。试听入口将候选官方声音和已发布可访问角色冻结为专用配置，由后台创建 DEBUG Session；仅该管理员和候选版本可用，普通应用列表/新绑定不暴露。未发布候选不得通过普通应用发布入口引用。

该方案是本稿待确认的实现设计，不是现存能力。新增字段通过追加迁移，旧应用默认USER；试听配置不更新公共Voice当前版本，关闭后释放Session引用，再清理专用配置与APP_CURRENT引用。管理账号最多一个活动试听会话；替换先停止旧轮。没有可用已发布角色时明确要求选择已有角色，不偷偷生成。

| 目标接口 | 调用时机及请求 | 响应/归属 |
|---|---|---|
| POST /api/v1/admin/voices/{voiceId}/versions/{versionId}/preview-sessions | 管理员点击试听；avatarVersionId必填字符串；Idempotency-Key | sessionId/applicationId/configVersionId；声音候选及角色由system复核；创建本身不合成 |
| POST /api/v1/applications/{applicationId}/debug-sessions | 用户进入应用调试；{}；Idempotency-Key | 同上，来自已发布应用固定配置 |
| POST /api/v1/debug-sessions/{sessionId}/tokens | 原登录取得/刷新S；{}；Idempotency-Key | token/expiresAt，token仅内存，不通用缓存 |
| DELETE /api/v1/debug-sessions/{sessionId} | 离开调试/关闭试听；Idempotency-Key | 202 state=DELETING；立即失效，可靠释放引用；不等于删除应用 |
| POST /api/v1/runtime/connection-tickets | S 换CONNECT/REAUTHORIZE一次性票据 | ticket/expiresAt/webSocketUrl/protocol，按C4.1 |
| GET /api/v1/runtime/avatar-package | S加载冻结Avatar版本 | C4.5正式manifest，含新签名及expiresAt |
| GET /api/v1/runtime/media/{mediaId} | S取本轮音频 | WAV二进制，no-store；不得转无鉴权地址 |
| POST /api/v1/runtime/stop | S，turnId/reason | turnId/alreadyStopped；与WSS同一状态转换 |

浏览器通过 /api/v1/realtime 固定子协议ln-avatar.v1、首帧ticket鉴权；不得尝试浏览器WebSocket自定义Authorization头。WSS完整信封、speech.create/turn.stop/playback.report、audio.segment/failed和终止事件直接复用C4.1～C4.4，本轮拒绝chat/ASR/context命令。旧 /api/v1/runtime/ws 与HTTP debug/speech 不再作为新UI主链；兼容期仍同鉴权、同状态逻辑，不能保留旁路。

- sessionId→s_session及grant→S→票据→connectionEpoch→turnId→segmentId/mediaId，各环节按父级归属及当前epoch验证。S至少包含本轮必要 session:read/avatar:read/speak:write；范围由后端赋予。
- 标准音频为24kHz、单声道PCM16 WAV；以C4.4为准，核对WavAudio实际检查，不按旧函数名猜采样率。
- 生成事实、实际播放、调用费用分别记录。停止先停本地播放器，再通知后台；迟到音频不播放但仍登记已发生调用。
- 纯播报文本只在本轮临时使用，不保存AI历史或记录到请求日志。音频不可访问与物理清理分开；s_temp_object持久登记，重启扫描及删除实现必须落地。
- 身份失效和关闭连接即停止；重连查状态、不自动重发或重播。上游未知保持待核对，不自动重合成。

### C4.1. DEBUG授权、连接与查询

DEBUG身份由当前C推导，禁止输入businessUserId/principalType。s_session_grant记录grant_source=CONSOLE_DEBUG及issuer_console_ref，Key字段为空。S过期时间取15分钟与来源登录剩余期限较短者；只允许原登录来源刷新。登出可靠撤销该登录引用下的grant并关闭连接，不影响同账号其他有效登录；新登录不得复活旧grant。账号停用覆盖全部授权。

connection-tickets请求为{"purpose":"CONNECT"}或{"purpose":"REAUTHORIZE"}；响应data包含ticket（高熵一次性字符串）、expiresAt（UTC）、webSocketUrl（部署生成的绝对地址）、protocol="ln-avatar.v1"。票据30秒有效，绑定grant/session/purpose；续授权票据额外绑定当前connectionEpoch。票据不入URL、子协议或日志。

浏览器以固定子协议连接/realtime，5秒内发首帧{"v":1,"type":"connection.auth","requestId":"connect-1","data":{"ticket":"EXAMPLE_ONE_TIME_TICKET"}}。原子消费并再次校验授权后分配持久递增connectionEpoch，替换旧连接、停止旧轮；未认证不得收业务事件。connection.reauthorize使用相同data结构及当前epoch，新票据必须同账号/应用/Session/身份/连接，仅更新有效授权，不重启轮次。

新增GET /api/v1/runtime/session（S、session:read），返回sessionId/applicationId/configVersionId/status/expiresAt/connectionEpoch/effectiveScopes/capabilities/effectiveLimits、activeTurn（无则null；有则turnId及C4.3四项状态）。重连先查询，不重发旧speech。20秒心跳、60秒无响应关闭；普通网络重连最多3次，1/2/4秒加抖动。关闭码4001过期、4003撤销、4009被替换、4010删除、4400格式错误。被替换连接不得自动抢回。

### C4.2. WSS字段与播报命令

| 字段 | 类型/必填/来源和消费 |
|---|---|
| v/type/requestId/data | v固定整数1；type为下表枚举；requestId客户端生成1～64 ASCII稳定串；data必填对象，只含命令已定义字段 |
| connectionEpoch | 首帧之外必填十进制字符串；connection.ready返回→客户端原样带回→服务端验证当前连接 |
| turnId | stop和播放回执必填ID；新speech省略；服务端轮次事件必填，连接事件为null |
| sessionId/seq/occurredAt | 服务端信封必填；sessionId来自连接；seq为十进制字符串；occurredAt UTC；客户端不能指定Session身份 |

服务端事件完整例：{"v":1,"type":"audio.segment","requestId":"speak-1","sessionId":"4201","connectionEpoch":"3","turnId":"5201","seq":"2","occurredAt":"2026-09-20T08:00:00.000Z","data":{"segmentId":"5301","ordinal":0,"mediaId":"5401","mimeType":"audio/wav","durationMs":1200,"expiresAt":"2026-09-20T08:01:00.000Z"}}。

seq在(connectionEpoch,turnId)内递增；连接控制事件使用独立序列。客户端丢弃旧epoch、重复seq、停止轮的迟到事件；seq不承诺跨连接重放。一个Session中speech requestId在会话保留期唯一：同ID同参数返回原事实，不新建轮；异参409语义。无效身份/参数先拒绝，不打断旧轮；有效新轮在Session锁内替换旧轮。

| 客户端type | data与约束 | 返回/消费 |
|---|---|---|
| speech.create | text必填1～8000 Unicode码点；S需speak:write | request.ack给requestId/turnId/status，随后turn.started；不调用LLM/ASR，不存AI历史 |
| turn.stop | reason必填1～100安全字符串；顶层turnId必填 | 与HTTP stop同转换，旧轮stop不能停止新轮 |
| playback.report | segmentId必填ID；state必填STARTED/ENDED/FAILED/SKIPPED；reason可省略，失败时必填1～100 | 当前连接/轮/段校验后幂等更新播放事实，不能影响厂商计费事实 |
| ping | clientTime必填UTC | pong回clientTime/serverTime，不延长Session保存期限 |

本轮其余业务命令统一拒绝CAPABILITY_NOT_ALLOWED，尤其chat/asr/context/guidance。不把未实现事件当成功空响应。

### C4.3. 服务端事件和状态汇总

| 服务端type | data必需字段 |
|---|---|
| connection.ready | connectionEpoch、effectiveScopes字符串数组、capabilities字符串数组、effectiveLimits对象、expiresAt |
| request.ack | requestId、turnId（非轮命令可null）、status |
| turn.started | mode=SPEAK |
| audio.segment | segmentId ID、ordinal从0递增整数、mediaId ID、mimeType=audio/wav、durationMs非负整数、expiresAt UTC |
| audio.failed | segmentId、ordinal、error{code,message,retryable}；停止后续语音，不跳过坏段 |
| turn.completed | status、textStatus、audioStatus、playbackStatus；表示非主动中止收尾，不保证全部成功 |
| turn.stopped | 上述四状态、reason；纯播报lastMessageId=null |
| connection.replaced/revoked | reason安全字符串；客户端停本地并清队列关闭 |
| request.error | code、message、retryable布尔、details安全对象；不含上游正文 |

四项状态独立持久化：status=RUNNING/COMPLETED/INTERRUPTED/FAILED；纯播报textStatus固定NOT_REQUESTED；audioStatus=RUNNING/COMPLETED/FAILED/INTERRUPTED/UNKNOWN；playbackStatus=WAITING/PLAYING/COMPLETED/FAILED/STOPPED/UNKNOWN。创建为RUNNING/NOT_REQUESTED/RUNNING/WAITING。

段回执保存于s_operation.playback_status：WAITING→STARTED→ENDED，失败FAILED、跳过SKIPPED；后台中止未终态段为STOPPED，超时无法确认UNKNOWN。重复幂等，迟到STARTED不覆盖终态；任何FAILED/SKIPPED停止后续语音，全部段ENDED才汇总播放COMPLETED。不得将合成status当播放状态。

合成全部成功且无待提交段才audioStatus=COMPLETED；停止时已完成事实保留，明确取消INTERRUPTED，已发出不明UNKNOWN。播放未终止时stop→STOPPED。主动停止/替换/断线/撤销令整体INTERRUPTED，发一次turn.stopped；其他收尾中有FAILED/UNKNOWN则整体FAILED，仅播放STOPPED则INTERRUPTED，全部完成则COMPLETED，发一次turn.completed。总时限结束仍未知，前台以FAILED收尾，后台继续核对，不无界等待。后续核对可修正合成/调用事实，不能复活整体终态、补发终止事件或播放。

### C4.4. 音频、有效限制和临时数据

按完整句优先分段，超长句在Unicode安全边界截断；同轮稳定segmentId/ordinal，最大2段并发且领先播放不超过2段。后段先到也需顺序播放。GET media需有效S、speak:write、本Session/当前轮及段归属，HTTP200二进制audio/wav，Cache-Control:no-store，不跳到无鉴权URL。标准PCM16 little-endian、单声道24kHz。实际playing驱动speaking；pause/waiting/ended/error回idle。自动播放阻止时显示“点击继续播放”，不重新合成。

runtimeLimits本轮仅允许省略或{}，不接受任意参数；effectiveLimits由同一后端配置源计算并冻结到配置，运行时与当前限制取更严格值。以下字段均必填正整数，时间单位写入字段名，服务端/前端使用同一返回值：

| 字段 | 初始值 | 落点/含义 |
|---|---|---|
| maxTextCodePoints | 8000 | speech输入约束 |
| maxCodePointsPerSegment | 200 | VoiceRuntimeProperties同名配置 |
| maxConcurrentSegments/maxBufferedSegments | 2/2 | 最大并发/领先播放段数，后者复用既有配置 |
| maxAudioBytes | 5242880 | 既有配置；每段上限 |
| ttsTimeoutSeconds/turnTimeoutSeconds | 30/300 | 单段/整体前台时限，不能把超时等同无费用 |
| playbackWaitSeconds/temporaryAudioTtlSeconds | 60/900 | 手势/播放等待；异常音频存储兜底，正常结束尽早删 |

S、票据期限及连接心跳为C4.1服务设置，不是费用额度。不复活已取消的制作次数限制。音频正常段终止后清理；停止/断线立即禁读、后台物理删除，s_temp_object持久记录确保重启扫描。创建本地播放URL后由浏览器结束/退出时revoke；服务端不能因合成完成而提前删尚待播放音频。

### C4.5. 正式角色包与现有预览的适配

GET /api/v1/runtime/avatar-package（S、avatar:read）返回C2外壳，其data直接为已授权冻结版本的正式manifest，由前端解包传AvatarPlayer.loadPackage。不能把现有AvatarPreviewResponse（URL/尺寸摘要）直接冒充manifest；system复用现有正式包构建和签名逻辑，经内部授权返回session。URL更新只替换签名，不改文件hash/版本；失败禁止开始收费合成。

字段与已实现[manifest.ts](../../../avatar-sdk/src/manifest.ts)保持一致：packageType=LN_AVATAR、schemaVersion=1、framing=FULL_BODY、lipSyncMode=BASIC_SPEAKING、avatarId/versionId字符串、width=512、height=768、anchor{x,y}整数像素；preview/baseImage为Asset；actions恰好八项idle/speaking/listening/thinking/nod/shake_head/wave/happy，不重复。Asset含fileId字符串、sha256 64位hex、url绝对签名URL、expiresAt UTC。Action含code、frameCount=6、fps=6、loop（前四为true其余false）、atlas Asset、frames六项{x,y,width,height}，固定3×2布局：x=0/512/1024、y=0/768，单帧512×768。布局、锚点和hash沿已完成制作产物，不重新生成或另写解析器。

正式图片与图集按资产生命周期长期存COS；签名过期重新授权取得，不改公开桶。试听音频不是长期资产包的一部分。实际预览/正式包差异以已完成制作源码为准，适配若发现不一致在播报D-A解决，不默默改制作格式。

## C5. 调用事实、异常和静态验收边界

p_call_record为platform调用事实；session用s_operation及s_outbox把分段结果传递给system，不跨库写。目标operationKey为generation:{attemptId}或tts:{turnId}:{segmentId}，均由后端关联实际持久ID生成，保留已有attempt本身的请求幂等标识不改写。存量若已有不同计量键先查实际关联并兼容，不将其当新调用再入账。唯一键operation_key与inbox去重一起防止重复记账，日汇总按差额修正；UNKNOWN不得当零费用或清除证据。

目标 POST /internal/v1/call-records/events（I，仅media/session）：eventId、operationKey、accountId、capability、status 必填；applicationId/sessionId/turnId/providerRequestId、usage、costAmount/currency/costSource、errorCode按可得结果可空。返回 accepted/duplicate，重复同事件不重复累计，矛盾结果转核对而不回退已确认成功。原始提示词/语音正文/密钥/签名URL禁止入此接口。后台调用管理计划负责查询及有审计核对，提供方负责先落本地可靠证据再发事件。

| 故障 | 已发生/保存证据 | 恢复及费用 | 上限/用户反馈 |
|---|---|---|---|
| 配置保存响应丢失 | 幂等记录与实体事务 | 查同一操作；无厂商调用 | 同键重试；冲突刷新 |
| Session跨库创建中断 | RESERVED+referenceOperationId | 核对同一操作，不再建另一会话 | 重试有退避；核对不成显示准备中并禁止删除 |
| TTS提交后失联 | s_operation及调用STARTED/UNKNOWN | 有厂商查询依据才查原请求；否则人工核对，不自动再合成 | 前台有界结束，后台保留UNKNOWN |
| 音频已生成但交付失败 | mediaId、临时登记、调用成功 | 有效期内重新读取同段；不重新合成 | 过期提示重新操作将有新调用 |
| 用户停止/登出/停用 | 轮次终态、撤销epoch/outbox | 不播放迟到结果；已提交费用仍核对 | 本地立即停止；远端取消仅尽力 |
| COS/本地删除失败 | DELETE_PENDING/清理登记 | 重试删除，不重制资产 | 首批最多5次指数退避，之后保留待处理，管理员可再次重试 |

本轮编写不进行服务启停、数据库/迁移、登录、真实API/COS或收费调用。编码阶段主代理静态走查生产方到消费方，必要时仅离线编译/类型检查；未实现的路径标“证据不足”，不预填通过。真实体验由用户执行；代理协助需另有对应授权。

## C6. 计划与贯通责任

| 流程 | 承接计划 | 负责的连接 |
|---|---|---|
| 官方服务 | [服务管理](2026-09-19-admin-official-services.md) | 可用服务/秘密引用→制作及官方TTS |
| 官方声音 | [声音管理](2026-09-19-admin-official-voices.md) | 官方版本→试听/公共选择器 |
| 平台异常 | [任务与调用](2026-09-19-admin-task-operations.md) | 实际调用→用户可理解的状态与核对 |
| 公共下架/删除 | [公共生命周期](2026-09-19-admin-public-asset-lifecycle.md) | 管理命令→引用保护/运行时停用/清理 |
| 用户资产 | [个人资产库](2026-09-19-user-asset-library.md) | 已完成角色→列表/版本/引用/删除 |
| 应用配置 | [应用管理](2026-09-19-user-application-config.md) | 可选资源→不可变配置→引用及DEBUG创建 |
| 平台播报 | [播报调试](2026-09-19-user-console-speech.md) | DEBUG授权→真实播报→停止/清理 |

最终由用户体验：管理员配置服务和声音并试听发布→用户选已有角色及官方声音发布应用→平台播报和停止→管理员下架后旧绑定可用、新绑定不可用→紧急停用阻断旧使用→解除引用后安全删除。各计划阶段完成不能代替此贯通结果。私有声音/Relay和外部接入待用户重新启动对应规划，不写成已支持。
