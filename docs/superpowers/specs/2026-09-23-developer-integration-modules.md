# 开发者接入平台功能模块执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [平台内共享契约](platform-console-shared-contract.md) · [开发者接入接口草案](developer-integration-interface-contract.md)

日期：2026-09-23。版本：0.7。确认状态：用户于 2026-09-25 分别授权实施 DEV-01、DEV-02、DEV-03；其余 DEV 模块仍须按各自接口契约与计划确认后实施。

## 1. 当前状态

| 项目 | 当前事实 |
|---|---|
| 平台内能力 | 账号、角色资产、官方声音、SPEAK_ONLY 应用、DEBUG Session、正式角色包读取、WSS、分段播报、停止和临时音频清理已有实现，可作为开发者接入的基础内核。平台内能力的真实验收结论以各原计划为准。 |
| Application | 当前 `ApplicationServiceImpl` 只接受关闭 Context 的 `SPEAK_ONLY` 配置和已发布官方 Voice；LLM、ASR、Skills、Context 仍被明确拒绝。 |
| Session | `session_db` 已有 BUSINESS/DEBUG、BUSINESS_KEY/CONSOLE_DEBUG、JTI、epoch、消息、Context、operation、幂等和清理等基础表；当前 Java 运行链主要实现 CONSOLE_DEBUG。 |
| SDK | `avatar-sdk` 当前提供 manifest 解析、Canvas 角色播放、动作、音频与 speaking 状态；尚无正式的业务 Session 客户端、聊天、录音、Context 和高亮模块。 |
| Relay、Skills、凭证、Webhook、用量 | V1 数据库已有对应基础表；DEV-01 凭证和 DEV-03 LLM/ASR Relay 已静态实现，本机 `platform_db` 已执行 Flyway V16～V19，真实接口仍待验收。Skills、Webhook 和完整用量仍属后续模块。 |
| 外部示例 | `examples/integration-demo` 无可运行内容；按用户最新决定，本计划不建设示例。 |
| 工作树 | 本轮开始时 `main` 比 `origin/main` 已领先一个既有本地提交，另有未跟踪 `logs/`。DEV-01 代码与文档纳入本轮本地提交，不包含日志。 |
| 下一步 | DEV-01～03 已通过静态验证；用户于 2026-09-25 废弃视频制作虚拟人需求，DEV-02 仅保留图片输入。本机 `platform_db` 的 V16～V19 已迁移并核对结构；服务重启、真实后台与管理 Key、Relay 网络/TLS/协议、COS/生成和用户终点仍待验收。DEV-04～11 不因前三模块实施而自动进入实施。 |

## 2. 目标与边界

本计划交付开发者接入平台所需的平台功能：开发者在统一后台管理接入凭证、资产开放访问、LLM/ASR Relay、Skills 和完整 Application；每个 Application 必须绑定平台官方 Voice/TTS，不接受开发者自有 TTS。其可信后端使用 Application Secret 为已登录业务用户创建当前运行 Session；浏览器 SDK 使用短期授权建立实时连接，完成角色渲染、对话、语音、页面感知和停止。每轮脱敏摘要及每次调用事实在动作发生时分别持久化并长期保留，不等待 Session 结束；Avatar 任务与 Webhook 投递及逐次尝试记录也长期保留。平台不保存聊天正文、不恢复旧 Session；多轮记忆由开发者 Relay 负责。

本次按**功能模块**划分开发任务。每个模块覆盖该能力所需的前端、Controller、Service、Mapper/SQL、跨服务调用、状态恢复和最小验证。整体接入流程只用于核对模块之间能否贯通，不作为开发任务拆分方式。

### 2.1 本计划范围

- 账号级管理 Key、Application Secret、Session Token 三层接入凭证及撤销。
- 使用管理 Key 的资产、任务、Voice、Relay、Skills、Application 与用量开放 API。
- 开发者 LLM/ASR Relay 服务与连接测试；Application 的 TTS 只用平台官方 Voice/TTS。
- CHAT/SPEAK_ONLY 完整 Application 配置、不可变版本、Skills、Context 和实时有效权限。
- BUSINESS 当前运行 Session 创建、状态查询、结束、短期 Token、单活连接和退出撤销；不提供旧 Session 恢复、历史查询或平台持久 Session Context。
- SDK core 与 context 模块：连接、渲染、动作、对话、录音、播报、停止、采集、高亮和事件。
- LLM 流式对话、Prompt/Tool Skills、手动 ASR、分句 TTS、独立播报与失败降级。
- 账号/应用用量、额度、并发限制、调用状态，以及 Avatar 成功/最终失败 Webhook。
- 统一后台中与上述能力对应的开发者管理页面和调试入口；页面随所属功能模块实施。

### 2.2 明确延期

- 在线开发文档、VitePress 站点及后台文档入口。
- `examples/integration-demo` 原生 TypeScript 前端/后端示例和开发者 Relay 示例。
- npm 正式发布、CDN 构建、完整浏览器兼容矩阵和对外版本支持承诺。

以上内容在平台核心模块稳定后另立最终交付计划。本计划的模块完成与验收不以文档站或示例工程为前置条件。

### 2.3 不在第一版范围

沿用需求基线：匿名 Session、团队与成员权限、第三方登录、React/Vue UI 组件、持续监听、唤醒词、自动点击/填表/提交、跨会话长期记忆、写入型 Tool、任意厂商协议可视化映射、自动切换厂商/Key、充值购买、RAG 与声音克隆不进入本计划。

## 3. 已确认决策与待决事项

### 3.1 已确认决策

| 决策 | 落地规则 |
|---|---|
| 开发任务按功能模块划分 | 第 7 节每个 `DEV-*` 是独立模块，包含完整纵向实现和退出条件；不以接入步骤拆成多个流程计划。 |
| 代码边界与部署 | 开发者接入继续使用现有 `ruoyi-system`、`ruoyi-session` 和 SDK，不新增 Spring Boot 部署进程、Nacos 服务或业务数据库；先按本计划第 4 节的领域所有权组织代码，不能因开放 API 新增第二套资产、Application 或会话状态机。 |
| 官方 TTS | 每个 Application（CHAT 和 SPEAK_ONLY）必须绑定已发布、当前可用的平台官方 Voice；开发者不能为 Application 配置自有 TTS、Relay Voice 或 TTS 厂商 Key。已有 Relay TTS 适配保留为存量代码事实，不作为新接入能力。官方 TTS 调用仍按平台额度预占、结算与逐段记录。 |
| 业务记录长期保留 | 每轮创建时写不含正文的 `s_turn`，每次外部调用提交前在所属库写独立操作/调用事实及可靠转发事件，结果到达后更新状态；`p_call_record` 可经跨库 Outbox 异步到达，但不得等 Session 结束才产生。脱敏轮次、操作、`p_call_record`、`p_usage_daily`、Avatar 任务/步骤/厂商尝试和 Webhook 投递/尝试记录长期保留，不设置自动删除期限；临时内容和已完成技术队列另按其生命周期清理。 |
| 文档与示例最后实施 | 在线文档和原生 TypeScript 前后端示例不在本计划任务表中，只在第 2.2 节登记延期。 |
| 应用级接入配置 | 一个开发者可发布多个 Application，每个 Application 各有自己的一个有效 Application Secret，不在账号内共用；Secret 绑定应用而非每次发布的配置版本，只保存在开发者后端。开发者主动重置后旧 Secret 立即不能发起新的后端请求，已签发的浏览器授权不因重置而撤销，按原到期时间失效；管理员禁用该应用时拒绝新业务动作和重置 Secret，不强断既有连接或已交付音频。账号级停用另覆盖该账号全部应用。 |
| 运行 Session 与浏览器授权 | BUSINESS Session 闲置 2 小时或创建满 24 小时结束，先到者为准；浏览器授权有效 15 分钟。SDK 经开发者后端在到期前续签，新旧授权短暂并存，旧授权不延期、到期自然失效；同一 Session 仍只允许一条活跃 WSS 连接。退出/显式撤销及 Session 结束按各自规则失效，不把普通续签当 Secret 重置。 |
| 厂商 Key 边界 | LLM/ASR 厂商 Key 只存在于开发者后端；平台只保存访问开发者 Relay 的独立接入凭证。官方 TTS 凭证由平台管理员配置。 |
| 无匿名业务用户 | BUSINESS Session 的 `externalUserId` 只能由已通过 Application Secret 鉴权的开发者后端声明；浏览器不能创建身份。 |
| 聊天记忆边界 | 平台不保存聊天正文，不恢复已结束 Session，也不提供历史查询或跨轮 Session Context；多轮记忆由开发者 Relay 负责。每轮一条脱敏状态摘要，逐次外部调用另保留计额事实。 |
| 配置版本固定 | 当前运行 Session 固定创建时的 `app_config_id`；结束后不可恢复。账号、Key、应用、Relay、Skill 或紧急资产停用等当前限制实时优先。 |
| 后台与业务授权分离 | 平台 DEBUG 继续绑定后台登录；外部 BUSINESS 使用 Application Secret。两者可复用运行内核，不混用授权来源。 |
| Tool 只允许查询 | Tool Skill 固定 URL/方法/schema/限制；模型不能修改目标地址、读取凭证或声明业务身份。 |
| 页面采集边界 | DOM 在浏览器上传前执行 allow/deny 与密码排除；截图按选定范围直传，不声称与 DOM 使用相同遮蔽。 |
| Webhook 范围 | 只通知 Avatar 任务成功或最终失败；任务查询结果是事实来源，投递失败不改变任务状态。 |

### 3.2 本计划采用的技术契约

以下为基于现有 `/api/v1` 页面接口、网关路由和服务职责形成的目标命名；旧[接口设计说明书01](../../../接口设计说明书01.md)的自有 TTS、开放路径、业务用户字段、30 天调用留存及内部引用路径与本计划不一致，不能直接照搬。开发者接入的跨模块细节由[接口草案](developer-integration-interface-contract.md)统一收敛，用户确认后才成为实施契约：

| 调用方 | 目标路径族 | 鉴权与服务落点 |
|---|---|---|
| 统一后台 | `/api/v1/developer/**` 及既有 `/api/v1/applications/**` | 若依后台登录；system 管理开发者资源，session 管理后台调试。 |
| 开发者后端管理调用 | `/openapi/v1/management/**` | 账号级管理 Key；gateway → system。 |
| 开发者后端业务会话 | `/openapi/v1/sessions/**` | Application Secret；gateway → session，session 通过受保护内部接口核对 system 当前授权。 |
| 浏览器运行时 | `/api/v1/runtime/**`、`/api/v1/realtime` | Session Token 或一次性连接票据；gateway → session。 |
| 平台调用开发者 Relay | 开发者登记的 HTTPS base URL + 固定 LN Relay 相对路径 | 平台保存的 Relay Bearer Token；只在服务端使用。 |

已定：开发者只人工管理每个 Application 自己的一把 Secret；浏览器直连使用绑定业务用户和当前 Session 的 15 分钟授权。Session 闲置 2 小时或创建满 24 小时结束，SDK 自动请求开发者后端续签，正常续签的新旧授权仅重叠到旧授权原到期时刻。Secret 重置立即拒绝旧 Secret 的后端请求，但不撤销此前签发的浏览器授权或中断现有连接；管理员禁用拒绝新业务动作，但不强断连接或已交付音频。管理员禁用字段及权限按数据库设计中的 `admin_disabled` 增量方案实施。若网关不能在不破坏既有 `/api/v1` 的情况下承载目标路径，先更新本节及接口草案，再编码。

实施前必须闭合的工程契约：当前 Gateway `AuthFilter` 默认把非白名单凭证当后台登录 JWT，新增 `/openapi/v1/**` 路由不能靠整段匿名白名单放行；DEV-01 须定义路径分流、身份头清理、下游独立鉴权和拒绝语义。`p_access_key` 现只有 `public_id` 唯一约束，须用应用级串行化及可验证的数据库约束保证并发重置后单活。BUSINESS/DEBUG 新授权统一使用接口草案第 3 节的版本化 HMAC `v2` + 持久 JTI/grant，旧 DEBUG `v1` 只在原到期前兼容读取；签名密钥版本独立于 Application Secret epoch，丢响应可从持久元数据重建。接口草案的路径族须在各模块编码前补齐方法、DTO、Scope、错误和事件 Schema，不能各模块自行发明。

## 4. 现有链路与强制复用

| 模块来源 | 已有事实 | 本计划复用和适配 |
|---|---|---|
| 若依认证、网关和权限 | 后台登录、角色菜单、Gateway 路由、Sa-Token 鉴权已存在 | 继续用于统一后台；外部 Key 使用独立过滤器，不伪装成后台用户 Token。 |
| `p_access_key`、`p_secret` | 已定义 MANAGEMENT/APPLICATION、哈希、状态、epoch 和用途隔离 | 新增凭证服务与页面；原始 Secret 只在创建/轮换成功时返回一次。 |
| Application 服务 | 已有列表、详情、创建、SPEAK_ONLY 发布、APP_CURRENT 引用和停用撤销 | 扩展同一领域服务支持 CHAT、Relay、Skills 和 Context；不创建第二套 Application 表或服务。 |
| Voice/Relay TTS | Voice 已能区分 OFFICIAL/RELAY，session 有 `RelayTtsRuntimeAdapter` | 新 Application 发布和运行只接受 OFFICIAL Voice；保留已有 Relay TTS 代码事实，但开发者接入不得走该分支。 |
| DEBUG Session 与运行时 | `DebugSessionService`、`ConsoleDebugGrantService`、ticket、WSS、角色包、分段播报、stop 已存在 | 抽取共同运行内核并新增 BUSINESS grant/authenticator；DEBUG 行为保持后台登录来源。 |
| `session_db` V1 | BUSINESS principal、grant、message、turn、context、operation、temp object、idempotency、outbox 已建模 | 优先补 Service/Mapper 和必要增量迁移，不复制同义表。 |
| `avatar-sdk` | manifest、Canvas 播放、动作、音频和 speaking 已实现 | 在同包新增连接、会话、录音、聊天与事件；context 使用独立导出和按需依赖。 |
| 制作与资产服务 | 上传、生成任务、资产目录、版本、引用和删除内核已有 | 开放 API 只复用现有 Service；不得复制制作状态机或绕过引用保护。 |
| 额度与调用事实 | 制作额度、官方 TTS、调用记录和日汇总已有局部实现 | 各调用发生时写逐次事实，状态变化更新；长期保留脱敏记录。LLM/ASR 自带 Key 不扣平台语音/生成额度，官方 TTS 必须扣平台语音额度。 |
| Outbox/Inbox/job lease | 平台与会话库已有可靠事件结构 | 撤销、引用、结算、Webhook 与清理均沿用；不以进程内事件替代持久事实。 |

### 4.1 代码归属与调用方向

| 代码位置 | 唯一业务所有权 | 开发者接入时的做法 |
|---|---|---|
| `ruoyi-system` 既有 `application` | `p_application`、配置版本、发布、状态与 APP_CURRENT 引用 | DEV-05 扩展现有 `IApplicationService`/Mapper；接入密钥服务只核对应用归属和禁用状态，不复制 Application CRUD。 |
| `ruoyi-system` 既有 `asset`、`voice`、`operations` | Avatar/制作任务、官方 Voice/试听、调用事实与额度 | DEV-02 的开放 Controller 调用资产/任务 Service；官方 Voice 复用既有 Voice Service；DEV-11 查询与限制复用 Operations/额度入口，不另写同义表或状态机。 |
| `ruoyi-system` 新增 `developer.access`、`developer.relay`、`developer.skill`、`developer.webhook` | 接入 Key、Relay、Skill、Webhook 各自的配置与状态 | 各子包有自己的 Controller/Service/Mapper；共用受保护的秘密存储、账号授权与引用能力，不从别的领域 Mapper 直接写表。 |
| `ruoyi-system` 新增 `developer.openapi` | Management Key 的 HTTP 入口和外部 DTO | 只做身份、Scope、参数和响应映射；与后台 Controller 调用同一领域 Service。业务会话与浏览器运行路由不落在此包。 |
| `ruoyi-session` 既有 runtime 及新增 BUSINESS 代码 | `session_db`、当前 Session/grant/turn/operation、WSS、运行编排 | 复用 DEBUG 内核；通过受保护内部接口查询 system 当前授权/固定配置并预留或释放资源引用，不直接写 `platform_db`。 |
| `avatar-sdk` | 浏览器连接、播放、录音、采集和 UI 事件 | 不导入后端领域代码；长期应用 Secret 不进入浏览器包。 |

这些是同一进程内的代码责任边界，不要求新 Maven 子模块。新后台与开放 Controller 不能各自实现发布、制作或删除事务；同一数据库事务仍留在拥有该业务事实的 Service。若实施中必须跨领域写表，先补领域方法或内部接口及失败恢复契约，再编码，不以共享数据库为由绕过所有权。

## 5. 完整接入流程核对

本节用于验证模块接口闭合，不作为开发切片顺序。

| 步骤 | 输入与鉴权 | 模块处理与持久化 | 输出与失败恢复 |
|---|---|---|---|
| 1. 配置账号资源 | 开发者后台登录 | 管理 Key、LLM/ASR Relay、Skills、Webhook 分别写各自模块；从平台目录选官方 Voice；Secret 一次展示 | 返回脱敏列表；失败不留下可用的半配置。 |
| 2. 发布 Application | 后台登录或管理 Key | 校验 Avatar/Voice/Relay/Skills/Context，写不可变 config、current 指针、引用和幂等 | 返回配置版本和能力；失败保留旧版本。 |
| 3. 业务用户登录 | 开发者自己的登录系统 | 开发者后端验证身份，平台不参与密码验证 | 平台只接收经过 Application Secret 鉴权的稳定 `externalUserId`。 |
| 4. 创建运行 Session | Application Secret + 幂等键 | session 核对 system 应用/配置/Key 状态，写 BUSINESS principal/session 和跨库引用预留 | 返回当前 Session 摘要；跨账号、跨应用访问及已结束 Session 重用均拒绝。 |
| 5. 签发 Session Token | Application Secret + 当前 Session + requestedScopes | scopes 与固定配置、请求和当前授权求交；写 15 分钟 grant/JTI | 响应丢失时同键从原有效 grant 重建同一授权；临近到期经开发者后端续签，新旧 grant 仅并存到旧 grant 原到期时刻；已结束 Session 不能再签发。 |
| 6. SDK 连接 | Session Token 换一次性 ticket，WSS 首帧消费 | 分配递增 connection epoch，替换旧连接，读取固定角色包 | 旧连接收到替换并关闭；重连不自动重发消息或重播音频。 |
| 7. 运行能力 | Session Token | chat/speak/ASR/Tool/Context 按 scope 和当前授权执行，写不含正文的 turn 摘要、operation 和逐次调用事实 | 文本、音频、工具和采集事件关联 request/turn/sequence；平台不提供旧正文，错误可诊断。 |
| 8. 停止与撤销 | SDK stop 或开发者后端 revoke | 先持久终止/递增 epoch，再断连、停止后续处理、清理临时数据 | 迟到结果不播放、不触发工具；已发生调用和计额继续核对。 |
| 9. 查询与通知 | 管理 Key 或后台登录 | 查询 Session/用量/调用状态；Avatar 终态写 Webhook delivery | Webhook 重试不改变任务状态；接收端按 event ID 去重。 |

## 6. 共享契约、状态与不变量

### 6.1 身份与凭证

| 名称 | 来源与保存 | 允许能力 | 撤销条件 |
|---|---|---|---|
| 后台登录 Token | 若依登录 | 统一后台页面及 DEBUG | 登出、账号停用、登录失效。 |
| Management Key | `p_access_key.key_type=MANAGEMENT`；只存摘要 | 本账号开放管理 API；无管理员权限 | Key 停用/删除、账号停用、auth epoch 变化。 |
| Application Secret | `p_access_key.key_type=APPLICATION` 且绑定 application | 对应应用当前 Session 创建、Token 签发和撤销 | Secret 停用/重置后立即拒绝其新的后端请求；不追溯撤销已有浏览器 grant。 |
| Session Token | 版本化 HMAC `v2` + `s_session_grant` JTI | 浏览器运行 Scope | 15 分钟到期、显式撤销、Session/业务用户失效；管理员禁用立即拒绝新动作。普通 Secret 重置不使已签发 Token 提前到期。 |
| Relay Token | `p_secret.purpose=RELAY_ACCESS` 加密保存 | 平台服务端调用指定 Relay | Relay 停用、Token 轮换或账号停用。 |
| Tool Token | `p_secret.purpose=TOOL_ACCESS` 加密保存或 Session 短期业务凭证 | 平台服务端调用绑定 Tool | Skill/授权停用、Session 撤销或短期凭证到期。 |

原始 Key/Secret 不进入浏览器、Prompt、模型上下文、SDK 事件、Webhook 或普通日志；列表只显示名称、前缀、创建/最后使用时间和状态。

### 6.2 配置与实时授权

- `p_app_config` 发布后不可修改；新 Session 使用当前配置，仍有效的 Session 保持创建时的 `app_config_id`，结束后不可恢复。Application Secret 绑定应用，不因配置版本发布自动轮换。
- CHAT 必须绑定一个 LLM Relay 版本；可选 ASR Relay 和 Skills。CHAT 与 SPEAK_ONLY 均必须绑定有效的官方 Voice；SPEAK_ONLY 不绑定 LLM/ASR/Skills/Context。
- 发布时展开 Avatar、Voice、Relay、Skill 的资源引用闭包；APP_CURRENT 与 SESSION 分开持有引用。
- Session 有效能力是“固定配置允许范围、Token scopes、当前账号/应用/Key/Relay/Skill/资产状态”的交集。
- Relay/Skill/Key/应用停用和官方资产紧急停用立即限制旧 Session；普通下架只限制新绑定。

### 6.3 幂等、状态与错误

- 平台管理写操作使用 `p_api_idempotency`；Session HTTP 写操作使用 `s_api_idempotency`。同键不同请求摘要返回冲突。
- 凭证创建、配置发布、Session 创建、Token 签发、撤销、turn 创建、额度预占/结算和 Webhook event 均有稳定业务唯一键。
- HTTP 错误统一包含 `code`、适合显示的 `message`、`retryable` 和 `requestId`；不得包含秘密、Prompt、录音、截图或工具原始结果。
- 无凭证/无效凭证为 401；凭证有效但 scope/归属不足为 403；状态或 ETag 冲突为 409/412；配置不可运行为 422；限流为 429；上游失败用稳定平台错误码映射。
- LLM/ASR/TTS/Tool 失败不自动换 Relay、模型、Voice 或 Key；UNKNOWN 调用先核对，不能盲目重试付费操作。

### 6.4 实时事件与数据保存

- WSS 延续 `ln-avatar.v1`、一次性 ticket、首帧认证和持久递增 connection epoch；BUSINESS 与 DEBUG 共用信封格式。
- 每轮使用 `turnId` 和单调事件序号；SDK 忽略旧 connection/turn 的迟到事件。
- 文本生成、语音合成和实际播放分别记录；文字完成不等于合成或播放完成。
- 平台不保存 Session 对话正文或跨轮业务 Context；一个 turn 保留一条脱敏运行摘要，外部 LLM/Tool/TTS 调用各自保留必要计额事实。截图、DOM、本轮 Context、录音、临时音频和工具原始结果按请求/轮次清理并有异常扫描兜底。
- 脱敏 `s_turn`、逐次 `s_operation`/`p_call_record`、`p_usage_daily`、Avatar 任务/步骤/厂商尝试及 Webhook 投递/尝试记录长期保留，调用开始即在所属库写状态事实并可靠转发跨库记录，状态变化持续修正，不依赖 Session 结束才落库；不得保存聊天正文。业务记录不按终态 30 天删除；临时录音、截图、DOM、音频和原始工具结果仍须及时清理。

### 6.5 Relay、Tool 与页面安全

- Relay/Tool/Webhook URL 只允许 HTTPS，拦截本机、环回、链路本地、私网及平台内部地址；每次连接重新解析并验证实际连接目标，禁止重定向，防止校验后 DNS 改变或跳转进入内网。
- Relay 采用固定 LN Relay 版本和相对端点，不提供任意字段映射脚本；连接测试验证鉴权、协议和声明能力，不触发无关付费模型调用。
- Tool 地址、方法和 schema 固定；第一版只允许经开发者承诺的查询用途。平台校验绑定、参数、次数、超时和结果大小，但仅凭 GET/POST 与 Schema 无法证明开发者端点没有写入副作用；开发者端点必须自行执行只读操作和业务访问控制。
- DOM allow/deny 过滤在 SDK 上传前完成，deny 优先且默认排除密码输入；截图行为按需求明确告知调用方。

## 7. 功能模块与开发任务

### 7.1 模块总览与依赖

| 模块 | 功能边界 | 主要依赖 | 可独立退出的结果 |
|---|---|---|---|
| DEV-01 接入凭证与开放 API 鉴权 | Management Key、Application Secret、哈希、一次展示、轮换/停用/删除、外部请求身份 | 若依账号、`p_access_key`、秘密主密钥配置 | 外部请求能稳定映射账号/应用；撤销即时生效；后台可安全管理凭证。 |
| DEV-02 资产与任务开放 API | Avatar 上传/生成/查询/删除、Voice/任务查询的管理 Key 入口 | DEV-01、既有资产/制作/引用/额度服务 | 外部 API 与后台操作产生同一业务事实，不复制状态机。 |
| DEV-03 LLM/ASR Relay | Relay CRUD/版本/授权/连接测试/停用，仅开放 LLM/ASR 能力 | DEV-01、`p_secret` | 平台能通过固定协议调用开发者 LLM/ASR Relay，厂商 Key 不进入平台。 |
| DEV-04 Skills | 官方/私有 Skill、Prompt/HTTP Tool、版本、凭证、授权、停用和绑定候选 | DEV-01、`p_skill*`、`p_secret` | Skill 可独立管理和版本化，Tool 地址/身份/结果字段受控。 |
| DEV-05 Application 完整配置 | CHAT/SPEAK_ONLY、LLM/ASR、必选官方 Voice、Skills、Context、版本和 Secret 关联 | DEV-01、DEV-03、DEV-04、既有 Application/引用服务 | 发布不可变完整配置；拒绝无 Voice 或 Relay Voice；新旧 Session 版本规则与实时撤权闭合。 |
| DEV-06 BUSINESS Session | 业务身份、当前 Session 创建/状态/结束、Token/grant、撤销、清理 | DEV-01、DEV-05、session 基础表、内部授权接口 | 开发者后端能安全创建当前运行 Session 并取得短期授权；跨身份拒绝，结束后不可恢复。 |
| DEV-07 SDK 与实时连接 | SDK SessionClient、ticket/WSS、单活、重连、角色包、动作、事件和 stop | DEV-06、现有 SDK/DEBUG 运行内核 | 外部网页仅凭 Session Token 加载角色并可靠连接；旧连接和迟到事件失效。 |
| DEV-08 AI 对话与 Tools | LLM 流式输出、脱敏轮次日志、Prompt Skills、Tool 调用、打断与状态 | DEV-03～DEV-07 | CHAT 从文本输入到流式结果可用；多轮记忆归开发者 Relay，工具权限、次数、身份与失败降级闭合。 |
| DEV-09 ASR、TTS 与独立播报 | 手动录音 ASR、分句官方 TTS、官方 Voice、顺序播放、播放回执 | DEV-03、DEV-05～DEV-08、现有播报内核 | 录音识别、对话播报和 speak 可用；stop 后无迟到声音，计额事实正确。 |
| DEV-10 页面感知与高亮 | context SDK、Element/Page/Hybrid、显式/AI 按需、业务 Context、高亮 | DEV-05～DEV-08 | 授权范围内采集和高亮可用；过滤、部分结果、超时和引用失效语义明确。 |
| DEV-11 用量、限额与 Webhook | 用量/额度/并发查询，调用状态，Avatar 终态 Webhook、重试和投递记录 | DEV-01、DEV-02、DEV-06～DEV-10、Outbox/job lease | 用量可核对、限制可执行；Webhook 可验签、去重和查看投递事实。 |

### 7.2 DEV-01 接入凭证与开放 API 鉴权

实施内容：

- 在 system 增加 AccessKey Controller/Service/Mapper 和外部 Key 鉴权过滤器；继续使用 `p_access_key`，不保存原始值。
- 在 Gateway 为管理 Key、Application Secret、后台 Token 和浏览器运行授权配置互不混用的路由与鉴权；清除外来身份/内部来源头，下游按凭证类型再校验。不得将 `/openapi/v1/**` 或全部 `/api/v1/**` 直接当匿名路径。
- 后台页面支持 Management Key 和 Application Secret 创建、命名、一次复制、脱敏列表、轮换、停用和删除；Application Secret 只能在所属应用详情管理。
- Management Key 只产生账号身份；Application Secret 同时固定 account/application，并要求 `sessions:grant` 资格。外部接口禁止请求体传 `accountId` 覆盖鉴权身份。
- 每个 Application 同时只允许一个有效 Secret；开发者经后台登录或有权 Management Key 主动重置，应用级锁及 `active_application_id` 生成列唯一键共同防止并发创建两把有效 Secret，旧 Secret 先持久失效并递增 epoch，新 Secret 仅展示一次。重置不递增 Application/Session 授权 epoch，也不撤销此前签发的浏览器 grant；旧 Secret 的新后端请求立即拒绝。管理员禁止应用时，开发者不能重置或重新启用；本模块先在 Application 领域补 `admin_disabled` 独立事实、管理员权限/审计和增量迁移，再开放 Secret 重置。
- Key 状态变化写审计与必要的可靠事件；不能把普通 Secret 重置事件解释为断开已有 BUSINESS 连接。后端每次请求核对当前 Secret 状态；管理员禁用后新业务动作查当前应用状态并拒绝，不能仅等待消息到达。缓存缺失或无法确认时回查或拒绝新动作。
- 完成 `/openapi/v1/management/**` 和 `/openapi/v1/sessions/**` 目标路由及不同凭证的负向校验；管理接口先可用，业务 Session 授权链在 DEV-06/07 完成后联动复验。同步检查现有到期/清理任务不会删除长期轮次、调用、日汇总、Avatar 任务及 Webhook 投递/尝试记录；所需平台库迁移不等待 DEV-11。

静态退出：创建响应的 Secret 不进入数据库/日志/幂等结果；哈希校验、账号/应用归属、状态/epoch、跨账号拒绝、重置后仅旧 Secret 的新请求失败及管理员禁用后的新动作拒绝均可追踪；system 编译、迁移结构和前端类型检查通过。

本模块用户终点：创建后只显示一次；脱敏列表可识别；有效管理 Key 能访问对应路径；错误类型、他人资源、停用/重置旧 Key 均被拒绝，并发重置最终只有一把有效 Secret。既有 BUSINESS 浏览器授权保留到自身到期、管理员禁用拒绝新动作由 DEV-06/07 联动验收，不作为 DEV-01 单独完成的前置。

### 7.3 DEV-02 资产与任务开放 API

实施内容：

- 以 Management Key 暴露 Avatar 图片上传与制作提交、任务/步骤/结果查询、采用版本、资产查询和删除；Voice 目录只返回开发者可选的官方 Voice，不开放私有 Voice 写入。视频制作虚拟人已废弃，不在本模块或后续接入范围。
- 外部 DTO 只接收业务字段，账号、额度、状态和引用由服务端取得；上传权利确认、格式/大小限制沿用资产服务，制作提交复用同账号幂等键和真实任务 ID。账号文件上限及存储额度由上传与生成文件写入共用账本检查；未完成上传先保留预占，确认后结算，删除对象后才释放容量。
- Controller 作为外部 HTTP 边界调用既有 Service；不得复制生成、重试、COS、Outbox、引用保护或删除状态机。
- 开放查询返回公开稳定字段，隐藏内部 secret、object key、租约、异常栈和管理员核对字段；素材访问仍使用归属校验后的短期 URL。
- 同步停止 Avatar 任务/步骤/厂商尝试的到期删除，迁移清空存量 `p_generation_task.expires_at`，核对素材/Avatar 删除不会级联删掉业务记录；任务查询分页并保留脱敏结果，不延长临时素材保存。

静态退出：后台与开放 API 落到同一资产/任务/引用/额度方法；同键提交不重复生成或扣额；跨账号和被引用删除路径拒绝。

用户终点：开发者后端可完整提交和查询任务、采用资产及执行受保护删除；页面刷新或 API 重试不重复生成；真实生成仍按预算单独授权。

### 7.4 DEV-03 LLM/ASR Relay

实施内容：

- 实现 LLM/ASR Relay 列表/详情/创建、不可变版本、能力与相对端点、ALL_ACCOUNT_APPS/EXPLICIT_APPS 授权、连接测试、轮换 Token、停用和引用保护删除；不允许新建 TTS Relay 用于 Application。
- Relay Token 使用 `p_secret(RELAY_ACCESS)` 加密保存；页面仅输入平台访问 Relay 的 Token，不出现厂商 Key 字段。
- 现有 `p_secret` AES-GCM 实现要求目标环境设置 `LN_OFFICIAL_SERVICE_MASTER_KEY`（Base64 解码后 32 字节）及按需设置 `LN_OFFICIAL_SERVICE_MASTER_KEY_VERSION`；同一系统实例组必须使用相同值，不能把主密钥写入源码、数据库或页面。
- 固定 LN Relay/1 的 LLM 流式文本/工具事件与 ASR multipart 完整录音、尽力取消、统一错误和可获取用量；TTS 不走开发者 Relay。
- 从已鉴权 BUSINESS principal 向所属开发者 Relay 传递稳定 `externalUserId` 和 `applicationId/sessionId/turnId`，使其能自行关联跨 Session 的多轮记忆；浏览器或模型内容不能伪造这些身份字段。
- 连接测试分别验证网络目标、TLS、鉴权、版本和所选能力；无测试专用端点时只做无计费能力探测，不能用真实 LLM/ASR 请求冒充连接测试。
- 官方 Voice 目录与试听复用既有 Voice/DEBUG 能力；不新增私有 Voice 创建或绑定入口。

静态退出：秘密无回显；URL 安全校验覆盖解析与重定向；Relay 版本不可变；停用对旧 Session 实时生效；TTS Relay 或私有 Voice 不能进入 Application 发布与运行路径。

用户终点：LLM/ASR Relay 连接测试能区分网络、TLS、401、协议和能力错误；开发者厂商 Key 仅在其后端配置；官方 Voice 可在应用配置中选择并试听。

### 7.5 DEV-04 Skills

实施内容：

- 实现官方/私有 Skill 列表、创建、导入指令内容、不可变版本、启停、排序候选和引用保护；导入只解析配置，不运行脚本。
- Prompt Skill 保存名称、说明、instructions 和 Context 权限需求；应用总 System Prompt 优先。
- HTTP Tool 保存唯一工具名、固定 GET/POST URL、有限 JSON Schema、身份绑定、可返前端字段、超时、结果上限和每轮次数约束；接入秘密使用 `p_secret(TOOL_ACCESS)`。
- Tool 连接检查不使用模型；URL 安全、参数校验、结果裁剪和秘密注入在平台后端完成。GET/POST 与 Schema 无法证明远端无副作用，平台明确限定可信端点和开发者端只读/业务权限责任。
- Skill 停用即时阻止旧 Session 后续调用；普通下架只阻止新绑定；已固定版本仍用于历史展示。

静态退出：Prompt/Tool 分支校验、版本不可变、Tool 名冲突、跨账号、SSRF、秘密回显和结果字段白名单均有服务端守卫；只读语义由可信端点契约及开发者服务执行，平台不声称可从 HTTP 方法证明。

用户终点：创建 Prompt/Tool、发布版本、绑定候选、停用与引用提示可用；无权工具和越权业务数据不能返回。

### 7.6 DEV-05 Application 完整配置

实施内容：

- 扩展现有 Application DTO/Service/Mapper/UI 支持 CHAT：一个 LLM Relay、可选 ASR Relay、必选官方 Voice、System Prompt、模型参数、Skills 和 Context policy；SPEAK_ONLY 也必须绑定官方 Voice。
- 发布时锁应用与依赖，核对 Relay grant/capability、Voice 的 OFFICIAL 类型及发布状态、Skill Context 需求、工具名唯一性、当前授权和资源状态；拒绝无 Voice 或 RELAY Voice，写 `p_app_config`、`p_app_skill`、current policy、APP_CURRENT 引用、revision 和幂等同一事务。
- 配置规范化后计算 hash；模型 ID 可手填，只有声明图片/工具能力时才能启用视觉或 Tool/AI 按需采集。
- Application Secret 管理由 DEV-01 提供；开发者自行停用与管理员禁止应用的权限来源分开记录。停用应用递增 epoch 并撤销业务和调试授权，新启用不复活旧 grant。
- 本模块交付完整配置编辑、发布和调试入口的授权/能力展示；CHAT、ASR、Skills 和 Context 的实际调试操作随 DEV-08～10 的运行能力接入，继续由后台登录创建 DEBUG 授权。

静态退出：SPEAK_ONLY/CHAT 均绑定官方 Voice、拒绝开发者自有 TTS、依赖固定版本、APP_CURRENT/SESSION 引用、旧配置不可变、实时撤权和发布不触发真实模型调用全部闭合。

用户终点：发布纯播报和对话应用；非法能力组合提示明确；更新配置后新 Session 使用新版本，仍有效的旧 Session 使用创建时版本，结束后不可恢复；撤权立即生效。

### 7.7 DEV-06 BUSINESS Session

实施内容：

- 在 session 实现 Application Secret 鉴权入口、BUSINESS principal、当前 Session 创建/状态查询/结束、grant/JTI/Token 签发和撤销；不新增旧 Session 恢复、历史正文查询或 Session Context 持久接口。
- system 提供受保护内部授权快照与当前状态核对接口；session 不直接写 platform_db，system 不直接写 session_db。
- `externalUserId` 和可选查询用短期业务凭证只来自已鉴权开发者后端；短期业务凭证加密存 Redis，到期/撤销/删除移除，不进入 Prompt。
- 当前 Session 操作必须同时匹配 account、application、principal；仅知道 Session ID 不足以访问，结束后不能重新授权。Session 固定 `app_config_id` 并可靠预留/确认/释放 SESSION 引用。结束时保留长期轮次/操作日志所依赖的不可运行 Session/principal 元数据墓碑，清理任务不得级联删除日志。
- BUSINESS Session 的到期为 `min(最后一次成功业务活动+2小时, 创建时间+24小时)`，心跳不延长；到期或退出后不可恢复，继续使用须由开发者后端新建 Session。浏览器授权为 15 分钟；SDK 通过开发者后端在到期前续签，旧 grant 保持到原到期时刻，不因新 grant 创建而提前撤销；一个 Session 可短暂有多个有效 grant，但 WSS 仍单活。Token 到期不等于 Session 到期。
- 浏览器授权 Scope 从请求、固定配置和当前授权计算；同幂等键丢响应从原有效 grant 元数据重建同一授权，不新建 grant。现有 DEBUG `v1` 签名 Token 升级为 BUSINESS/DEBUG 共用 `v2`，增量保存 `signing_key_version`，旧 `v1` 仅在原到期前兼容读取，按接口草案第 3 节验签并对照持久 grant。普通 Secret 重置仅阻止旧 Secret 的新后端请求，不按签发 Key epoch 追溯撤销已签发 grant；退出/显式撤销及 Session 结束仍先持久化 epoch/grant/Outbox，再终止对应运行。管理员禁用不强断连接或已交付音频，但拒绝禁用后新发起的业务动作和外部副作用。结束后异步清理本轮临时内容、对象和引用；长期保留脱敏日志及必要父级墓碑。

目标接口族：`POST /openapi/v1/sessions`、`GET/DELETE /openapi/v1/sessions/{id}`、`POST .../{id}/tokens`、`POST .../{id}/revocations`；不提供历史或 Session Context 接口。写接口必须使用幂等键或 If-Match。

静态退出：BUSINESS_KEY 与 CONSOLE_DEBUG 两条授权来源不混用；跨账号/应用/业务用户拒绝；浏览器凭证明文不落库；2 小时闲置/24 小时上限、15 分钟授权、`v2` 验签/持久 JTI、刷新重叠/丢响应规则、Secret 重置与管理员禁用的差异、显式撤销、引用和长期日志清理可在重启后继续。完整接口 DTO/事件 Schema 未固定前不进入本模块编码。

用户终点：开发者后端为两个业务用户分别创建当前 Session；跨用户访问失败；正常 Token 续签不打断正在播放的内容，旧 Token 只到原到期时刻；重置 Secret 后旧 Secret 新请求失败、旧浏览器授权到期前仍可运行；管理员禁用拒绝新动作但不强断既有连接；退出撤销和 Session 结束使对应授权失效，已结束 Session 不可恢复。

### 7.8 DEV-07 SDK 与实时连接

实施内容：

- 在 `avatar-sdk` 新增不依赖 Vue 的 `SessionClient`：经开发者后端自动获取/续签 15 分钟 Token 的回调、ticket、WSS 首帧认证、reauthorize、有限重连、单活替换、事件订阅和销毁；续签不把 Application Secret 交给浏览器。
- 复用 `AvatarPlayer` 完成正式包加载、动作、音频和 speaking；补 `connect/chat/speak/stop/playAction` 的核心 API 与类型声明。
- BUSINESS 和 DEBUG 进入同一 runtime principal/connection 内核；所有命令检查 scopes、session/config/epoch 和 connection ownership。
- 新连接持久递增 epoch，Redis 只接受更高代；旧连接收到 replacement 后关闭。仍有效 Session 重连仅取得运行状态，不恢复聊天正文、不自动重发 turn 或重播音频。
- SDK 错误携带稳定 code、requestId/turnId；自动播放限制、网络断开、资源过期和不支持动作通过事件交给开发者 UI。

静态退出：浏览器 bundle 无长期 Secret；旧 connection/turn 事件被忽略；销毁释放 WSS、Audio、Object URL、动画帧和监听器；SDK typecheck/build 通过。

用户终点：两个页面竞争同一 Session 时后连接替换前连接；断网有限重连；Token 刷新不复活旧轮；角色完整显示、动作和 stop 正常。

### 7.9 DEV-08 AI 对话与 Tools

实施内容：

- 创建 CHAT turn 时立即写脱敏 `s_turn` 状态摘要；每次 LLM/Tool/Context 调用在提交前建立独立 operation/调用事实，结果到达后更新，Session 结束不负责补写历史调用。本轮用户/助手增量只在有界内存和当前连接中处理，不写平台聊天正文。每轮组装固定 System Prompt、启用 Prompt Skills 与本轮 Context；旧轮记忆由开发者 Relay 负责。固定指令超限明确失败，本轮工具调用/结果不能拆断。
- 追加 session_db 版本化迁移，解除 V1 `ck_s_turn_history` 对 CHAT `include_in_history=1` 的强制约束；新 BUSINESS CHAT 固定写 0，迁移和写入逻辑须一并验证，旧 V1 文件不改。
- LLM Relay 使用固定配置版本的 model/参数/能力；流式增量归一化为 WSS 事件，保存完成/中断文本状态和可获取用量。
- Tool 流程为模型提议 → 平台校验绑定/参数/次数/身份 → 调用固定端点 → 白名单结果回模型 → 继续生成；原始结果默认不持久化。
- 新消息默认停止旧轮；stop 取消读取、未开始的工具和后续分段。无法取消的迟到 LLM/Tool 结果不进入新轮。
- LLM、Tool、Context 请求分别记录 operation，并在调用开始时建立可持久核对的记录；失败返回模型可理解的失败状态，不编造业务结果、不自动换 Relay。

静态退出：一 Session 一活跃 turn、事件序号、平台正文不落库、开发者 Relay 负责旧轮记忆、Tool 权限/身份/结果、停止与迟到隔离、调用事实和临时数据清理均可追踪。

用户终点：真实 Relay 自行维护的多轮文本对话、Prompt Skill 和查询 Tool 可用；平台不提供旧聊天历史，断线只返回当前运行状态；越权/超时/停用时行为符合错误契约。真实模型调用需另行确认费用上限。

### 7.10 DEV-09 ASR、TTS 与独立播报

实施内容：

- SDK 提供录音开始/结束/取消、浏览器权限和格式事件；完整录音上传 session，由固定 ASR Relay 识别。
- ASR 返回文本后支持开发者确认再 chat 或直接提交；独立识别 operation 可不绑定 turn，临时录音在成功/失败/取消后清理。
- LLM 文本按完整句子分段 TTS，生成结束处理尾段；所有 Application 只通过绑定的官方 Voice 走平台官方适配和额度，段按 ordinal 播放。
- 独立 speak 使用 BUSINESS Session 和应用绑定的官方 Voice，不调用 LLM/Skills/Context，不写平台聊天正文；复用现有 SpeakOnly 状态、音频读取、回执与清理内核。
- stop 先停本地音频/动作，再终止服务端；已成功合成不退额，未提交段取消，UNKNOWN 继续核对；后段不能越过前段。

静态退出：录音/音频不进日志和长期库；TTS 只使用固定的官方 Voice，Relay TTS 分支不可达；实际播放驱动 speaking；迟到段不可读/不可播；额度与逐段调用事实幂等且长期保留。

用户终点：手动录音识别、文本确认、对话分句播报、纯 speak、自动播放恢复和 stop 可用；合成失败时当前 SDK 仍显示已收到文本并给出事件，平台不保存正文。真实 ASR/TTS 调用需另行确认费用上限。

### 7.11 DEV-10 页面感知与高亮

实施内容：

- `avatar-sdk/context` 独立导出，按需加载截图依赖；支持 Element、Page 和 Hybrid，Page 默认视口，全页显式开启并受大小/分片限制。
- 显式采集由开发者调用；AI 按需仅在当前用户 turn 内由服务端发起请求，限制每轮次数、范围和超时，不后台持续监控。
- DOM/text 在浏览器端按 allow/deny 过滤，deny 优先、默认排除密码输入；过滤失败不上传受影响 DOM。截图按选定范围处理并单独报告状态。
- 采集响应分别记录 screenshot/text 成功、实际范围、排除和缺失原因；partial 可继续，strict 要求两者成功。
- 元素引用只在本次采集/页面状态内有效；SDK 高亮前复核存在、授权范围和引用，支持清除/可选滚动，不执行点击、填写或提交。
- 本轮业务 Context 随 turn 并在本轮结束后清理；跨轮业务记忆由开发者 Relay 管理，平台不提供 Session Context 接口。所有 Context 作为数据，不能提升为系统指令或身份来源。

静态退出：跨域失败、DOM 过滤失败、截图差异、连接不可用、超时、部分结果、引用失效和停止清理都有明确状态；context 模块不增加 core 默认体积。

用户终点：平台调试页验证 Element/Page/Hybrid、显式/AI 按需、partial/strict 和高亮；开发者自己网页上的最终感知体验在后续真实接入时验收。

### 7.12 DEV-11 用量、限额与 Webhook

实施内容：

- 汇总各运行模块逐次写入或经可靠跨库事件同步的 `p_call_record`，完成 `p_usage_daily` 的 LLM/ASR/TTS/TOOL/CONTEXT 状态修正和日汇总差额；无法取得厂商用量标为不可获取。不将一次轮次的多次外部调用合并为无法逐次结算的记录；调用开始时已有所属库事实，平台汇总最终一致，均不等 Session 结束或按固定年限删除。
- 开发者后台和 Management Key 提供按账号/应用/日期/能力查询；不返回对话正文、业务用户身份或原始工具/页面数据。
- 核对各入口已实施的 `p_account_limit`、并发与存储限制，以及官方 TTS/生成额度预占/结算；DEV-11 补查询、差额汇总和补偿。LLM/ASR 开发者自带 Key 只记可获取用量；Application 的官方 TTS 按平台额度预占/结算。
- 实现 Webhook endpoint 创建、签名 Secret 一次展示/轮换/停用和投递记录；生成任务提交可选一个启用 endpoint。
- Avatar 成功/最终失败事务写 Outbox → delivery → lease worker；使用 Standard Webhooks `webhook-id/timestamp/signature` HMAC-SHA256，对原始 body 签名，有限重试并记录 attempt。Avatar 任务/步骤/厂商尝试及 Webhook delivery/attempt 业务记录长期保留，不设置终态 30 天删除；Outbox/Inbox 等完成的技术队列仍按可靠重试窗口清理。
- 迁移清空存量 `p_webhook_delivery.expires_at`，移除 delivery/attempt 的到期删除；长期日志不存接收端响应正文、签名头、秘密或素材，后台/API 查询按账号与时间分页。
- 投递前复查 endpoint 状态及 URL 实际连接目标，禁止内网/元数据地址和重定向；失败/耗尽不改变生成任务事实，不保存响应正文或签名请求头。

静态退出：调用状态重复事件不重复累计；并发额度不超扣；Webhook event ID 稳定、签名原文一致、重试去重、停用和重启恢复闭合；清理不删除未结算/待投递事实。

用户终点：后台/API 查询用量和剩余额度；超限请求在外部调用前失败；本地接收端验证签名、重复 event 去重、失败重试和投递记录，任务查询始终返回真实状态。

### 7.13 推荐实施批次

批次只表达技术依赖，不改变按功能模块验收：

1. 基础接入：DEV-01。
2. 可并行的账号资源模块：DEV-02、DEV-03、DEV-04。
3. 配置与授权：DEV-05、DEV-06；按已确认的运行期限与续权语义补齐 Token 编码、接口和事件 Schema 后实施 DEV-06。
4. 浏览器运行内核：DEV-07。
5. 运行能力：DEV-08、DEV-09、DEV-10。
6. 治理与通知：DEV-11；Session/turn/存储并发准入、外部调用事实、官方额度预占/结算分别随产生该副作用的 DEV-02/06/08/09/10 同步实现，DEV-11 完成查询、汇总和补偿闭环。

任何模块如果需要修改前置模块契约，先更新本计划第 6 节及受影响模块，再实施；不通过临时兼容层形成第二套身份、配置或状态模型。

## 8. 验证与验收

### 8.1 主代理最小逻辑验证

每完成一个模块，只运行覆盖其真实风险的最小检查，并在第 9 节记录实际结果：

| 变更范围 | 最小验证 |
|---|---|
| system Java/迁移 | 从 `RuoYi-Cloud` 编译受影响模块；检查 Controller→领域 Service→Mapper/XML→表/Outbox→消费者完整链，并核对后台与开放入口是否复用同一 Service、是否跨领域直写 Mapper；迁移只追加、不改历史版本。 |
| session Java/迁移 | 编译 session 及必要依赖；检查授权来源、JTI/epoch、幂等、单活、stop、清理和跨库内部接口。 |
| Vue 后台 | `npm run typecheck`；检查所有 ID 来自可读选择器或上下文，不要求用户手填内部数据库 ID。 |
| SDK | `npm run typecheck`/`npm run build`；聚焦连接代数、事件序号、播放状态和资源释放。 |
| Relay/Tool/Webhook 网络代码 | 离线检查 URL 限制、秘密注入、重定向、超时、取消、签名原文和重试条件；不默认访问真实外部服务。 |
| 全模块 | `git diff --check`；只检查/暂存计划覆盖路径，排除 `logs/`、密钥、缓存、构建物和诊断文件。 |

静态检查不能替代数据库迁移、服务启动、真实登录、WSS、Relay、浏览器、外部厂商或用户体验验收。主代理在用户未另行授权时不启动服务、不执行迁移、不发起真实或付费调用。

### 8.2 模块验收门槛

每个 DEV 模块分别记录四级证据，并区分本模块已经具备的终点与依赖后续模块的联动终点：

1. 静态代码逻辑：调用链、权限、状态、幂等、失败与清理走查，必要编译/类型检查通过。
2. 迁移与服务：用户在目标环境执行迁移并确认相关服务/依赖就绪。
3. 接口联通：使用真实后台登录或对应 Key 调用正向与拒绝路径。
4. 用户终点：在统一后台、开发者后端或浏览器 SDK 完成模块列出的真实操作。

一个模块在自身可独立运行的对应终点通过后可标为“模块完成”；依赖后续模块的联动项必须单列为“待联动验收”，不得提前宣称整体闭环。DEV-01 的 Secret 重置后旧浏览器授权自然到期、管理员禁用拒绝新动作随 DEV-06/07 复验，DEV-04 的 Application 绑定随 DEV-05 复验，DEV-05 的 CHAT/ASR/Context 调试随 DEV-08～10 复验。全部联动项通过后才可标记整体贯通完成。外部厂商调用需先明确本轮 Key 来源和费用上限。

### 8.3 整体贯通验收

全部模块完成后，以一个 CHAT Application 和一个 SPEAK_ONLY Application 核对：凭证管理 → Relay/Voice/Skill → 配置发布 → BUSINESS Session/Token → SDK 连接 → 对话/ASR/TTS/页面能力 → stop/revoke → 用量/Webhook。整体贯通只验证模块组合，不重新定义或掩盖各模块验收结论。

在线文档和原生 TypeScript 前后端示例不属于本次整体贯通门槛，后续另立最终交付计划。

## 9. 执行记录

| 日期 | 模块 | 状态 | 证据 | 遗留与下一步 |
|---|---|---|---|---|
| 2026-09-23 | 计划编写 | 待确认 | 已读取需求、架构、数据库、计划规范及现有 Application/Session/SDK/网关实现；按功能模块形成 DEV-01～DEV-11。未编码、未启动服务、未执行迁移或外部调用。 | 用户确认后从 DEV-01 开始；在线文档和原生 TypeScript 前后端示例另立最终任务。 |
| 2026-09-23 | 需求变更与复审修订 | 待确认 | CHAT/SPEAK_ONLY 均改为必选官方 Voice/TTS；脱敏轮次、逐次调用和日汇总改为调用发生时记录并长期保留，任务/Webhook 仍终态 30 天；补 Gateway 分流、单活 Secret 约束、管理员禁用、跨库记录、清理墓碑及联动验收边界。仅修改文档，未实施代码或迁移。 | 与用户讨论运行 Session 时限及浏览器授权方式后，定稿 DEV-06/07 契约；模块接口 Schema 逐项补足。 |
| 2026-09-24 | 产品规则确认及文档修订 | 待确认完整计划 | 用户确认每个 Application 一把 Secret、15 分钟浏览器授权、Session 闲置 2 小时/最长 24 小时、普通续签新旧授权短暂并存；开发者重置仅立即阻止旧 Secret 的新后端请求，不强断现有连接；管理员禁用拒绝新业务动作，不强断连接/已交付音频；Avatar 任务与 Webhook 投递/尝试记录长期保留。技术上选用现有 HMAC 运行 Token 的 `v2` 结构化升级，保留持久 JTI。仅修改文档，未实施代码或迁移。 | 补齐模块接口 DTO/事件 Schema；完整计划经用户确认后编码。 |
| 2026-09-25 | DEV-01 接入凭证与开放 API 鉴权 | 静态实现、V17 已迁移；接口/用户终点待验收 | 本轮用户明确授权 DEV-01。新增 Gateway `/openapi/v1/management/**` 与 `/sessions/**` 路由、按 `lnm_`/`lna_` 分流并清除外来身份头，通用后台 JWT 拦截器不解析开放 Key；system 实现同一 AccessKey Service、MyBatis/XML、后台与 Management Key 开放 Controller、内部 Application Secret 校验入口、一次性明文与 HMAC 摘要、账号/应用/Scope/状态/epoch 检查、应用行锁加生成列唯一约束、管理 Key 与应用 Secret 的创建/轮换/停用/删除、管理员独立禁用与审计事件、后台页面。V17 清空长期业务记录的到期值，OperationsService 停止为调用/日汇总设置到期时间。`ruoyi-system` 与 Gateway Maven 编译、Vue `npm run typecheck` 及 `AccessKeyServiceTest` 单项回归均通过；静态交付时未启动服务、执行迁移或访问真实凭证。 | 本机 V17 已执行；运行环境仍需配置至少 32 字节 `LN_ACCESS_KEY_PEPPER`，用真实后台和管理 Key 核对创建、脱敏、一次展示、跨账号/错误类型/旧 Key 拒绝及并发重置；DEV-06/07 再复验浏览器 grant 自然到期和管理员禁用后的新业务动作。 |
| 2026-09-25 | DEV-02 资产与任务开放 API | 静态实现、V18 已迁移；接口/用户终点待验收 | Management Key 入口按 `assets:read/write`、`generation:read/write`、`config:read` 分流；开放参考图上传、官方生成服务、任务分页/详情/步骤、版本制作与动作结果、私有/官方 Avatar 目录、版本采用、引用保护删除及仅官方有效 Voice 目录。开放 Controller 复用原资产、制作、发布、Voice Service，公开结果使用字段白名单。补齐原生成账本零单位占位：任务及恢复按账号并发上限预占 1 次 `AVATAR_COUNT`，成功结算、明确失败释放、UNKNOWN 保留待核对；上传和生成文件按账号单文件/`STORAGE_BYTE` 限额预占与结算，上传中断清理先删对象再释放，资产物理清理后归还容量。新增任务分页/步骤查询、V18 长期任务约束；V17 清空存量到期值，删除不级联任务事实。用户于同日废弃视频制作虚拟人需求，图片输入是本模块唯一制作入口。system Maven 编译与 9 项聚焦测试通过；静态交付时未启动服务、执行迁移或调用真实 COS/厂商。 | 管理员在运行环境配置 `p_account_limit` 及账号 `AVATAR_COUNT`、`STORAGE_BYTE` 授予余额，未配置时入口拒绝新任务/上传。本机 V17/V18 已执行；待服务就绪后用真实管理 Key 验证 Scope、短期 URL、同键重试、任务状态、版本发布和跨账号/引用删除；真实生成费用须单独明确预算。DEV-11 再联动验收用量查询与 Avatar 终态 Webhook。 |
| 2026-09-25 | DEV-03 LLM/ASR Relay | 静态实现、V19 已迁移；真实连接/用户终点待验收 | 以既有 `p_relay_service/version/grant` 与 `p_secret(RELAY_ACCESS)` 为单一事实源，新增后台与 Management Key 同一 Service 的列表/详情/创建、不可变版本、显式应用授权、Token 密文轮换、状态及引用保护删除；管理写入在账号行锁下以 `p_api_idempotency` 防同键重复。内部 Session resolver 每次核对账号、应用、确认引用、能力、授权和当前停用/管理员限制。V19 增加管理员限制和测试版本字段及页面菜单。固定 LN_RELAY/1 的 LLM/ASR/取消相对路径；连接测试仅 GET `/capabilities`，连接前重新解析并固定公网 IP、验证 TLS 原主机且拒绝跳转，按 TARGET/NETWORK/TLS/AUTH/PROTOCOL/CAPABILITY 等安全错误码记录。未添加 TTS Relay 或私有 Voice 入口。system 编译、Vue 类型检查与 8 项聚焦测试通过；静态交付时未启动服务、执行迁移或连接真实开发者后端。 | 本机 V17～V19 已执行；运行环境仍需配置现有 `p_secret` AES-GCM 主密钥；用真实后台和管理 Key 检查 Scope、跨账号、版本、Token 不回显、授权、禁用、删除引用及连接测试错误分类。DEV-05 在 Application 发布时消费 Relay grant/版本；DEV-06 建立 BUSINESS Session 引用，DEV-08/09 接入实际 LLM/ASR 调用与逐次事实。 |
| 2026-09-25 | DEV-01～03 本机数据库迁移 | V16～V19 已执行；运行接口待验收 | 用户授权执行相关迁移。本机 MySQL 8.0 `platform_db` 原 Flyway 最高 V15，依序执行 V16～V19；Flyway 11.7.2 报告执行 4 个版本并在执行后通过校验，`flyway_schema_history` 四行均成功、无失败行。查库确认单活 Secret 唯一键、管理员限制和 Relay 测试版本字段、任务长期保留约束、审计表、权限菜单及角色授权均存在；原有 1 条日汇总和 8 条调用记录的 `expires_at` 已清空，任务与 Webhook 的非空到期记录也为 0。受影响的 system 凭证、额度、开放接口及 Relay 聚焦测试 14 项通过。 | 服务重启日志、真实后台/Management Key 写链路、Relay 连接、COS/厂商及用户终点尚未验收；管理员仍需配置账号额度，运行时秘密只在目标环境配置。 |
