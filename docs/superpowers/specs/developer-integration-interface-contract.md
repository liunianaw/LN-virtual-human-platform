# 开发者接入接口与授权过程协议（待确认草案）

日期：2026-09-27。状态：DEV-01～08 接口族已随各模块实施固定；DEV-09 契约已按本节细化、静态实现待最终复核；DEV-10～11 仍待定稿。本文约定开发者接入模块的接口和跨服务行为。

依据：[开发者接入执行计划](2026-09-23-developer-integration-modules.md)、[项目需求](../../../项目需求说明书.md)、[数据库设计](../../../数据库设计说明书.md)、[项目架构](../../../项目架构说明书.md)、[平台内共享契约](platform-console-shared-contract.md)、[接口设计说明书01](../../../接口设计说明书01.md)。

## 1. 使用边界与待确认点

- 《接口设计说明书01》保留为早期设计资料，其中 LLM/ASR Relay 能力握手、WSS 信封、Webhook 签名和不冲突的默认限值可作为细化依据；其开发者自有 TTS/私有 Voice、`/api/v1/**` 开放管理/业务 Session 路由、`businessUserId` 字段、`/internal/v1/session-references/**` 路由，以及旧 Session 恢复/历史查询/Session Context 契约不用于本计划。
- 一个开发者账号可以发布多个 Application；**每个 Application 分别对应自己的一个有效密钥**，例如应用 A 和 B 各有一把，绝非一个账号共用一把。开发者可分别重置各应用密钥；平台禁止 A 后拒绝 A 新发起的业务动作并禁止重置 A，不因此停用 B。本文将这把人工管理的应用密钥称为 Application Secret，绑定 `application_id` 而非每次发布的配置版本；浏览器另用 SDK 自动处理的短期 Session 授权。
- Application Secret 只保存在开发者后端；浏览器要直接访问平台 WSS、当前运行状态和音频时，仍须有绑定具体 Session/业务用户的短期授权，不能持有全应用共用 Secret。平台管理员的应用级禁用不能由开发者轮换 Secret 解除。
- 平台不保存聊天正文，不提供旧 Session 恢复、聊天历史查询或跨轮 Session Context；多轮记忆由开发者 Relay 负责。一次 turn 在创建时写一条脱敏运行摘要，记录 `turnId`、所属 Session/Application、开始结束时间、状态、耗时和错误码；每次 LLM/ASR/Tool/官方 TTS 等外部调用提交前在所属服务库写独立计额/补偿事实及可靠转发事件，跨库 `p_call_record` 可最终一致，结果到达后修正状态，不等 Session 结束。脱敏轮次、操作、调用记录、日汇总、Avatar 任务/步骤/厂商尝试、Webhook 投递/尝试记录长期保留，不按终态 30 天清理。日志不记录输入输出文本、Tool 参数/结果、DOM 或截图内容；用量汇总从逐次调用事实计算，不在轮次表中伪造精确值。V1 的 `ck_s_turn_history` 与新 CHAT 轮次的 `include_in_history=0` 冲突，DEV-08 须用增量迁移解除。
- 每个新 Application（CHAT 与 SPEAK_ONLY）必须绑定已发布、当前有效的官方 Voice；发布和运行都拒绝 RELAY Voice、私有 TTS 和无 Voice。LLM/ASR 可使用开发者 Relay；官方 TTS 走平台额度预占、结算与逐段调用记录。存量 Voice/Relay 表和代码的 TTS 字段不代表新 Application 可使用该分支。
- 产品范围以需求说明书为准；表和跨库事实以数据库设计为准；平台内 DEBUG 和资源引用接口沿用平台内共享契约。本文只覆盖两套接入面交界的新增约定。若这些来源有冲突，应先更新相应权威文档和本草案，再编码。

## 2. 路由、身份和字段

| 接入面 | 路由 | 凭证 | 处理服务 | 不变量 |
|---|---|---|---|---|
| 统一后台 | `/api/v1/developer/**`、既有 `/api/v1/applications/**` 等 | 若依登录；管理员功能另查按钮和角色 | system，DEBUG 经受保护内部接口调用 session | 不接受浏览器伪造的账号/内部身份头 |
| 开放管理 | `/openapi/v1/management/**` | Management Key | system | 只管理 Key 所属账号的资源，不获得管理员或业务用户身份 |
| 业务会话 | `/openapi/v1/sessions/**` | Application Secret | session，经 system 内部授权接口核对 | 仅操作 Secret 所属应用及已核对的业务用户 |
| 浏览器运行时 | `/api/v1/runtime/**`、`/api/v1/realtime` | Session Token，WSS 使用一次性票据 | session | 只访问 Token 绑定的 Session、配置版本、用户及 Scope |
| 服务内部 | `/internal/v1/**` | 固定服务身份及操作权限 | system/session | 网关不向公网转发；不能信任外部传入的身份头 |

上述 system 是现有 `ruoyi-system` 部署进程，不代表新增服务。`/api/v1/developer/**` 与 `/openapi/v1/management/**` 的 Controller 分别处理后台登录和 Management Key，但调用相同的 Application、资产、Voice、Relay、Skill、用量或 Webhook 领域 Service；开放 Controller 不直接写其他领域表。Session 与浏览器运行接口仍由现有 `ruoyi-session` 处理，通过受保护内部接口读取 system 授权/配置事实；不得把运行状态复制到 system。

当前 Gateway 只显式转发已有 `/api/v1/**` 路径，`AuthFilter` 默认把非白名单请求按后台登录 JWT 检查。DEV-01 须新增上述开放路径的明确转发和按路由分流鉴权，清除外来账号/内部身份头，并让目标服务按 Management Key 或 Application Secret 独立核验；不能把整段 `/openapi/v1/**` 或 `/api/v1/**` 加进匿名白名单。DEV-06/07 再完成浏览器运行授权路径和 WSS 负向校验。

统一使用 `externalUserId` 作为公开请求字段，与 `s_principal.external_user_id` 对应；旧接口01中的 `businessUserId` 仅作历史术语，不同时接受两个别名。`accountId` 从凭证推导；Application Secret 绑定的 `applicationId` 不由请求覆盖。ID、revision 和 epoch 对外用十进制字符串。管理接口延续平台内共享契约 C2 的响应、ETag、幂等、分页和错误外壳；SDK/WSS、SSE、二进制按各自协议。

Management Key 的资源 Scope 见下表；Application Secret 只允许 `sessions:create/read/grant/end/revoke`，不再有 `sessions:context`。浏览器短期授权只允许当前 Session 所需的 `session:read`、`avatar:read`、`chat:write`、`speak:write`、`asr:write`、`context:capture`、`guidance:receive`，不再有 `history:read`。Scope 由凭证类型、固定配置和当前授权共同约束，不能从请求体自行扩大。

### 2.1 开放管理 API 的模块归属

#### DEV-01 已固定接口与凭证格式（2026-09-25）

- 管理 Key 使用 `Authorization: Bearer lnm_<32位小写十六进制publicId>_<43位base64url随机值>`；Application Secret 使用 `lna_` 前缀和相同的随机格式。前缀仅用于网关路径类型拒绝，system 仍逐次查询 `p_access_key` 并以部署变量 `LN_ACCESS_KEY_PEPPER`（至少 32 字节）做 HMAC-SHA256 常量时间摘要核对，同时检查账号、Key 状态/到期、应用状态/管理员禁用和 Scope。明文只在首次创建/轮换响应的 `data.secret` 出现，不写数据库、幂等结果或日志。
- 后台登录：`GET/POST /api/v1/developer/access-keys`（POST `{name,scopes}`）；`POST /api/v1/developer/access-keys/{keyId}/rotations|disable|delete`；`GET /api/v1/applications/{applicationId}/secret`、`POST .../secret/reset`（`{name}`）、`POST .../secret/{keyId}/disable|delete`。管理员独立入口：`POST /api/v1/admin/applications/{applicationId}/restriction`，请求 `{disabled:boolean,reason:string}`，须管理员身份及 `platform:application:admin-disable` 权限。
- 管理 Key 开放入口：`GET/POST /openapi/v1/management/access-keys`、`POST .../access-keys/{keyId}/rotations|disable|delete`、`GET /openapi/v1/management/applications/{applicationId}/secrets`、`POST .../secrets/reset`（`{name}`）、`POST .../secrets/{keyId}/disable|delete`。这些接口均要求 `keys:write`，创建管理 Key 的 Scope 不得超过调用 Key 已有 Scope。所有写接口要求 `Idempotency-Key`（1～64 个可见 ASCII 字符）；同键重试仅返回脱敏元数据，不能恢复 `secret`。列表含名称、公开标识、末尾 8 位、Scope、状态和时间，不含摘要或明文。
- `ruoyi-session` 将在 DEV-06 通过受保护的 `POST /internal/developer/access-keys/application-principal` 读取 Application Secret 当前 account/application/key/epoch 身份；该接口须内部来源头，且每次调用重新验证 Secret 与 `sessions:grant`。`/openapi/v1/sessions/**` 已由网关限定 `lna_` 类型并路由到 session；业务 Session 方法、DTO 和授权仍由 DEV-06 实施，DEV-01 不宣称这些业务接口已可用。
- 缺失/错误凭证返回 401，类型不匹配返回 401，有效 Key 但 Scope 或归属不足返回 403，同键不同参数及状态冲突返回 409；开放接口错误体含稳定 `code/message/retryable/requestId`。账号或应用不可用时拒绝新请求。Application Secret 重置只使旧 Secret 的后端请求失效，不撤销此前签发的浏览器 grant；管理员禁用写独立事实并限制后续业务动作，运行侧在 DEV-06/07 接入实时核对。
- 每次 Key 创建、停用或删除同步写不含秘密的 `p_access_key_audit` 和 Outbox；后台 JWT 拦截器跳过开放 API 与内部 Secret 校验路径，避免将 Key 当 JWT 解析并写入异常日志。

#### DEV-02 资产与任务接口（2026-09-25）

以下路径均在 `/openapi/v1/management` 下，仅接收 Management Key。`accountId` 从 Key 推导，客户端不得指定；路径及响应 ID 使用十进制字符串。DEV-02 沿用既有资产 DTO 的 JSON 数值修订号（`expectedAvatarRevision`、`expectedActionRevision`、`expectedCandidateRevision` 等），`If-Match` 则为文本；本模块以此替代上文针对新增模块的统一字符串修订号约定。生成、版本、动作和组装请求沿用后台对应 DTO 的 `requestId`（1～64 位，作为同账号幂等键），同键不同参数返回 409；删除使用 `Idempotency-Key` 与 `If-Match`。上传要求 multipart `file`、`rightsNoticeVersion`、`rightsConfirmed=true`，原服务负责图片格式、尺寸、权利确认和归属校验；账号单文件上限与存储余额同时生效。生成任务按账号并发上限与 `AVATAR_COUNT` 余额准入。管理员须先在目标环境配置限额和授予余额，缺失配置时上传/生成返回限额错误。JSON 成功响应沿用 `AjaxResult.data`；错误沿用 `code/message/retryable/requestId`。不存在或不属于当前账号的私有资源拒绝访问；管理 Key 不获得官方制作、发布或后台核对权限。

| 方法与路径后缀 | Scope | 请求与公开结果 |
|---|---|---|
| `POST /avatar-reference-files`、`GET /avatar-reference-files/{fileId}` | `assets:write`、`assets:read` | 上传返回文件 ID、媒体类型、大小/尺寸与短期读取 URL；读取重新核对归属并签发短期 URL。 |
| `GET /avatar-generation-services` | `generation:read` | 可选的启用官方 Avatar 制作服务 ID、名称、模型和修订号，不含端点与秘密。 |
| `POST /avatar-generation-tasks` | `generation:write` | `{sourceFileId,officialServiceId,expectedServiceRevision,requestId,name}`；复用同账号幂等提交、额度预占及 Outbox，返回真实 `taskId/avatarId/avatarVersionId`、状态、进度、安全错误码和创建时间。仅创建私有 Avatar。 |
| `GET /avatar-generation-tasks?pageNum=&pageSize=`、`GET /avatar-generation-tasks/{taskId}`、`GET /avatar-generation-tasks/{taskId}/steps` | `generation:read` | 任务分页上限每页 100；步骤只暴露 ID、类型、动作、状态、安全错误码和时间，不暴露租约、厂商请求 ID、服务快照或内部状态。 |
| `GET /avatars/{avatarId}/versions/{versionId}/production` | `generation:read` | 复用制作服务的版本动作阶段、结果 ID、可执行操作和组装资格；任务历史状态与步骤使用上面的任务路径查询。 |
| `GET /avatars`、`GET /avatars/public`、`GET /avatars/{avatarId}`、`GET /avatars/{avatarId}/references` | `assets:read` | 私有目录/官方已发布目录、可访问版本与引用计数；目录分页上限每页 100，列表只保留稳定展示字段及短期预览 URL。 |
| `POST /avatars/{avatarId}/versions`、`GET /avatars/{avatarId}/versions/{versionId}/preview`、`POST /avatars/{avatarId}/versions/{versionId}/publish` | 写入 `assets:write`、预览 `assets:read` | 新版本请求沿用 `CreateAvatarVersionRequest`；发布要求 `{visualAccepted:true,reviewNote}`，私有版本仍执行原有人工验收、状态及引用规则；预览 URL 短期有效。 |
| `POST /avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/selection|generations`、`POST .../actions/{actionCode}/attempts/{attemptId}/recovery|discard`、`POST .../assemble` | `generation:write` | 请求沿用 `AvatarActionSelectionRequest`、`AvatarActionGenerationRequest`、`AvatarAttemptRequest`、`AvatarAttemptDiscardRequest`、`AvatarAssemblyRequest`，包含 `requestId` 与期望修订号；仅调用原制作服务。 |
| `GET /avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/results/{resultId}/preview` | `generation:read` | 归属校验后返回动作结果 ID、帧布局及短期 atlas URL，不返回 object key、原始 QA 报告或厂商元数据。 |
| `DELETE /avatars/{avatarId}` | `assets:write` | `If-Match` 与 `Idempotency-Key` 必填；引用或活动任务存在时 409，成功为 202 与 `DELETING`，原资产生命周期服务执行异步清理。 |
| `GET /voices?pageNum=&pageSize=` | `config:read` | 仅列出当前已发布且官方 TTS 服务有效的 Voice/版本及公开配置；无私有 Voice 写入或管理员发布入口。 |

任务、步骤和厂商尝试是长期业务事实。V17 已清空历史 `p_generation_task.expires_at`；V18 再清空过渡期写入并加 `expires_at IS NULL` 约束。现有制作代码不设置任务到期时间，资产删除保留任务/步骤/尝试记录及外键限制；临时素材仍按原生命周期清理。DEV-02 不启动真实生成、不投递 Webhook；任务终态 Webhook 由 DEV-11 完成。

#### DEV-03 LLM/ASR Relay 接口固定契约

后台登录路径为 `/api/v1/developer/relay-services`，分别要求 `platform:relay:read/write`；Management Key 对应 `/openapi/v1/management/relay-services`，读需 `config:read`、写需 `config:write`。两个入口调用同一 Relay Service，账号只从凭证取得，请求不得携带可覆盖身份的 `accountId`。所有 POST/PUT/DELETE 必须带 1～64 位可见 ASCII `Idempotency-Key`，同键异参 409；版本、授权、Token、状态、删除还须 `If-Match=authEpoch`，缺失 428、过期 412。无计费连接探测在网络请求外使用独立数据库事务，按测试时的版本和 epoch 条件写回。响应仅包含显示字段、Token 末尾、版本和授权，不包含 Token、密文或内部 `secretId`。

| 方法与相对路径 | 请求/结果 | 边界 |
|---|---|---|
| `GET /`、`GET /{relayId}` | 分页列表；详情含不可变 `versions` 和 `grants` | 跨账号返回 404；列表不包含已删除项。 |
| `POST /` | `name,description?,accessToken,grantMode,version`；返回停用的 Relay 详情 | Token 为平台访问开发者后端的至少 32 字节可打印 ASCII Bearer，不是厂商 Key；创建首版但不调用付费端点。 |
| `POST /{relayId}/versions` | `VersionInput`；返回详情 | `If-Match=authEpoch`；追加版本，保留旧版，切换 current 并清除旧测试结果。 |
| `PUT /{relayId}/grants` | `grantMode,grants[{applicationId,scopes:[LLM,ASR]}]` | `If-Match`；`ALL_ACCOUNT_APPS` 不带明细；`EXPLICIT_APPS` 仅允许本账号应用和已声明能力，变更递增 `authEpoch`。 |
| `POST /{relayId}/token` | `accessToken` | `If-Match`；覆盖同一 `p_secret(RELAY_ACCESS)` 的密文，递增 epoch 并清除连接测试；不回显明文。 |
| `POST /{relayId}/connection-test` | 无请求体；返回 `success,errorCode?` | 仅 GET 固定 `/capabilities`，不提交 LLM/ASR 工作；结果为 TARGET/NETWORK/TLS/AUTH/REDIRECT/HTTP/PROTOCOL/CAPABILITY。 |
| `POST /{relayId}/status`、`DELETE /{relayId}` | 状态需 `status=ACTIVE/DISABLED,reason`；删除无体 | `If-Match`；启用须当前版本测试成功，停用立即拒绝新操作；存在应用配置、声音版本或活跃引用时拒绝删除。 |

`VersionInput={baseUrl,protocolVersion:"1",capabilities:{llm,asr,image?,tool?,cancel?},endpoints?,timeoutMs,maxResponseBytes}`；至少一个 LLM/ASR 为 true，image/tool 只能附属于 LLM，绝不允许 TTS。端点若提供，必须与固定 `GET /capabilities`、`POST /chat/completions`、`POST /audio/transcriptions`、`POST /requests/{requestId}/cancel` 一致；未提供时服务端保存固定相对路径。baseUrl 仅 HTTPS 主机及可选安全路径，不允许账号密码、查询、片段或非 443 端口。连接时重新解析 DNS，拒绝所有非公网目标，连接固定到已验证 IP，验证原主机 TLS 证书且不跟随重定向。

LN_RELAY/1 调用头固定为 `Authorization: Bearer {Relay Token}`、`X-LN-Protocol-Version: 1`、`X-Request-Id: {稳定操作号}`；开发者后端在此身份下自行注入厂商 Key。LLM 的 `POST /chat/completions` JSON 为 `{requestId,applicationId,sessionId,turnId,externalUserId,model,messages,tools,parameters,stream:true}`，`externalUserId` 在 BUSINESS 中来自已鉴权 principal；CHAT DEBUG 使用 `__ln_debug__:{sessionId}` 保留身份，新的 BUSINESS Session 禁止使用该前缀，以隔离开发者 Relay 的跨轮记忆；响应为 SSE `text.delta`、`tool_call.delta`、`response.completed` 或 `error`。前两个事件只含本次请求的顺序片段，平台仅在终态 `response.completed` 的完整 Tool 参数经授权、Schema 检查后执行；终态可带 `usage:{inputTokens?,outputTokens?},providerRequestId?`，未知值为 null，不猜测为零。流提前断开时本次操作为失败或不确定，已生成文字不自动重放。

ASR 的 `POST /audio/transcriptions` 为完整录音 multipart，字段 `audio,requestId,applicationId,sessionId,externalUserId,language?`；返回 `{text,language?,durationMs?,usage?,providerRequestId?}`，不会隐式继续发起 LLM。尽力取消为 `POST /requests/{requestId}/cancel`，返回 `state=CANCELLED/ALREADY_FINISHED/NOT_SUPPORTED/UNKNOWN`；取消不能承诺上游免费。错误 JSON 仅安全 `code,message,retryable,requestId`，不得透出厂商响应正文或 Key。此协议供 DEV-08/09 的实际流式 LLM 与录音 ASR 编排使用；DEV-03 的连接测试只做无计费握手。

后台管理员独立使用 `POST /api/v1/admin/relay-services/{relayId}/restriction`，提交 `disabled,reason`，要求管理员身份和 `platform:relay:admin-disable`；此限制递增 epoch。内部 `POST /internal/v1/relay-services/resolve` 只允许 Session 服务身份，输入 `accountId,applicationId,sessionId,relayVersionId,capability,externalUserId,turnId?`；服务端核对当前 Relay 状态、管理员限制、授权和已确认的 Session 配置引用，重新验证 DNS 后才返回 Token、固定版本配置及 `pinnedAddress`。运行方须每次新外部操作前重新解析，以 `pinnedAddress` 建立连接并按原主机验证 TLS，不缓存授权为永久许可。DEV-06 建立 BUSINESS principal 与引用，DEV-08/09 分别消费 LLM/ASR 协议并记录逐次调用事实。

#### DEV-04 Skills 接口固定契约（2026-09-25）

后台 `/api/v1/developer/skills` 与 Management Key `/openapi/v1/management/skills` 调用同一 Skill Service；开放读取要求 `config:read`，创建、版本、状态、连接检查与删除要求 `config:write`。后台读取/写入分别要求 `platform:skill:read/write`。`GET /` 返回分页 `{items,total,pageNum,pageSize}`，`GET /candidates` 仅返回当前 `PUBLISHED` 且有版本的官方/本账号 Skill，`GET /{skillId}` 返回脱敏详情、不可变版本和引用数；跨账号私有资源按不存在处理。官方 Skill 只由管理员后台 `POST /api/v1/developer/skills/official` 创建，Management Key 没有官方写入路由。

`POST /` 请求 `{name,description?,version}`，成功返回已发布版本的详情；`POST /{skillId}/versions` 追加不可变版本并切换当前版本；`POST /{skillId}/status` 请求 `{status:PUBLISHED|UNLISTED|DISABLED,reason}`；`POST /{skillId}/connection-check` 仅对当前 HTTP Tool 验证公网解析、原主机 TLS 证书和连通，不发出 GET/POST 工具业务请求，返回 `{success,checked?:TARGET_TLS_ONLY,errorCode?:TARGET|TLS|NETWORK}`；`DELETE /{skillId}` 有配置或运行引用时返回 409。写操作用 1～64 位可打印 ASCII `Idempotency-Key`，版本、状态和删除还要十进制文本 `If-Match=revision`；同键不同参数 409，修订不匹配 412，缺失 `If-Match` 428。普通 `UNLISTED` 阻止新候选，但已绑定版本可继续用于历史展示和既有 Session；`DISABLED` 即时拒绝新 Tool 动作；软删除保留版本并禁用所关联秘密。

`VersionInput` 区分 `skillType=PROMPT|HTTP_TOOL`。Prompt 只接收 `instructions`（非空，最多 32768 字符）、`contextRequirements`（`page/hybrid` 布尔需求）和可选 `importFormat=JSON`；导入只解析指令 JSON，不执行脚本。HTTP Tool 接收唯一可见 `toolName`、固定 `toolUrl`、`httpMethod=GET|POST`、有限 `inputSchema/outputSchema`、可选 `accessToken`、`frontendFields`、`identityBinding`、`requiresUserCredential`、`timeoutMs`（1000～30000）、`maxResultBytes`（1～1048576）及 `maxCallsPerTurn`（1～10）。Schema 仅允许顶层 object、最多 20 个标量字段的 `type/properties/required`，禁止 `$ref`、嵌套结构和模型声明的身份字段；前端字段必须属于输出 Schema。身份只允许服务端把经认证的 `externalUserId/applicationId/sessionId` 注入固定头，模型参数不能覆盖。Tool Token 用 `p_secret(TOOL_ACCESS)` 单独加密，详情只显示后缀；版本和幂等记录不存明文。未经认证的模型输入须先过 Schema 校验，原始响应先按字节上限和 Schema 校验，再按 `frontendFields` 裁剪，默认不向前端返回字段。开发者必须保证远端端点只读并自行检查业务身份；HTTP 方法与 Schema 不能证明远端无副作用。

内部 `POST /internal/v1/skills/resolve` 只允许 Session 服务身份，请求 `{accountId,applicationId,sessionId,skillVersionId}`；每次核对固定应用配置、已确认 Session 引用、应用/Skill 当前状态和跨账号归属，再为 Tool 重新解析并返回已验证公网 `pinnedAddress`、固定配置与秘密。DEV-05 负责绑定版本及同一应用内 Tool 名冲突，DEV-08 消费此解析结果、执行请求前的身份注入/参数校验、次数限制和结果裁剪；本模块不宣称 Tool 已实际调用或业务数据授权已完成。

#### DEV-05 Application 接口（2026-09-25）

后台继续使用 `/api/v1/applications`，Management Key 使用同构的 `/openapi/v1/management/applications`，两者调用同一 Application Service。读操作要求后台 `platform:application:read` 或 Key 的 `config:read`；写操作要求 `platform:application:write` 或 `config:write`。`GET /` 接受 `pageNum/pageSize/status`，`GET /resources` 返回当前可选择 Avatar、官方 Voice、Relay、Skill，`GET /{applicationId}` 返回应用、当前配置和历史版本，`GET /{applicationId}/config-versions/{configVersionId}` 返回不可变配置。资源候选只用于编辑，发布仍重新锁定并校验当前授权。

`POST /` 请求 `{name,description?}`。 `POST /{applicationId}/config-versions` 要求 `If-Match=revision` 和 `Idempotency-Key`，请求体为 `{mode,avatarVersionId,voiceVersionId,llmRelayVersionId?,asrRelayVersionId?,llmModelId?,systemPrompt?,llmParameters?,llmCapabilities?,contextPolicy,runtimeLimits?,skills?}`。ID 为十进制字符串。CHAT 必须有 LLM Relay 版本、模型 ID 和官方 Voice；ASR 可选，`llmParameters` 只支持 `temperature:0..2`、`maxOutputTokens:1..8192`；`llmCapabilities` 只支持布尔 `image/tool`，且对应 Relay 版本也须声明。SPEAK_ONLY 只允许 Avatar、官方 Voice 与 `{enabled:false}`。两种模式均不接收自有 TTS 或 RELAY Voice。

`skills` 最多 20 项，每项 `{skillVersionId,enabled,sortOrder}`；版本 ID 与顺序不得重复。启用的 HTTP Tool 需要模型和 Relay 的 Tool 能力，同一应用内 Tool 名称不得重复。Skill Context 要求必须被应用策略覆盖。`contextPolicy` 关闭时只能是 `{enabled:false}`；启用时必须指定 `modes`（EXPLICIT/AI_ON_DEMAND）、`sources`（PAGE/HYBRID）、`captureScope` 与 `dom` 的 allow/deny 选择器数组、`dom.excludePassword=true`、`fullPageEnabled`、`resultMode`（PARTIAL/STRICT）、`highlightMode`（EVENT_ONLY/AUTO）、可选 `allowScroll`（默认 false）、`maxCapturesPerTurn:1..5`。Context 需要图片能力，AI_ON_DEMAND 还需要 Tool 能力。页面采集仍只在 DEV-10 的运行入口发生。运行限值当前只允许 `toolCallsPerTurn/capturesPerTurn:0..20`；平台没有跨轮历史配置或聊天正文保存。

发布响应含 `applicationId,configVersionId,versionNo,configHash,capabilities,effectiveLimits`；同一幂等键重试返回原版本，同键异参 409。缺少/不匹配修订分别为 428/412；无效配置 400，资源状态/能力/授权不可用于新绑定 422，同一应用 Tool 名冲突 409。发布在一个事务中写不可变配置、Skill 绑定、当前指针/策略、APP_CURRENT 引用、修订与幂等事实，保留既有 SESSION 引用。发布不触发 LLM、ASR、Tool 或官方 TTS 请求。

`POST /{applicationId}/status` 请求 `{status:ACTIVE|DISABLED,reason}` 并要求 `If-Match`、`Idempotency-Key`；停用递增应用授权 epoch 和 Outbox，重新启用须当前配置与资源仍可用，管理员禁用不能由开发者解除。Application Secret 仍由 DEV-01 独立管理，重新发布不轮换 Secret。现有 DEBUG 播报入口仅接受 SPEAK_ONLY；CHAT、ASR、Skill、Context 调试分别随 DEV-08～10 接入。DEV-06 创建 BUSINESS Session 时应固定发布版本并建立 SESSION 引用；旧版本不能因更新 current 指针而被修改或删除。

以下路径表示本计划必须覆盖的资源族。每个模块实施前在本协议下固定该族的方法、DTO、Scope、错误与后台对应 Service；不得只完成后台页面而宣称开放 API 已完成。

| 模块 | Management Key 资源族 | 写入边界 |
|---|---|---|
| DEV-01 | `/management/access-keys`、`/management/applications/{id}/secrets` | 创建/轮换/停用仅本账号凭证；Application Secret 只属于指定应用；一个应用同时只允许一个有效 Secret；不能解除平台禁用 |
| DEV-02 | `/management/avatars`、`/management/avatar-generation-tasks`、`/management/voices` 的官方 Voice 只读目录 | 调用既有资产、任务、引用和额度 Service；不提供私有 Voice 写入 |
| DEV-03 | `/management/relay-services` 的 LLM/ASR 配置 | 版本、授权、连接探测和引用保护完整交付；不发布 TTS Relay 或私有 Voice |
| DEV-04 | `/management/skills` | 官方 Skill 只读/选择；私有 Skill 创建、版本和停用 |
| DEV-05 | `/management/applications`、`/management/applications/{id}/config-versions` | 发布锁定依赖并保持旧版本；CHAT/SPEAK_ONLY 均必选官方 Voice，拒绝私有 TTS；运行时调试能力随 DEV-08～10 接入 |
| DEV-11 | `/management/usage`、`/management/call-records`、`/management/webhook-endpoints` | 脱敏查询、签名秘密一次展示、投递状态 |

DEV-11 查询协议：后台 `/api/v1/developer/usage`、`/api/v1/developer/call-records` 和 Management Key 同名路径均只查当前账号；`GET /usage/limits` 返回 `configured,limits,balances`，`GET /usage/reservations` 返回预占状态分页。用量与调用查询可带 `applicationId,from,to,capability,pageNum,pageSize`，调用另可带 `status`；`from/to` 为 UTC `YYYY-MM-DD`，单次最多 31 日，默认最近 7 日，页大小 1～100。`p_usage_daily` 按实际逐次事实和币种汇总；`knownUsageCount/knownCostCount` 区分厂商未报告与数值 0。返回 ID 为十进制字符串，不返回输入正文、业务用户身份或原始 Tool/页面数据。管理员可用 `POST /api/v1/admin/usage/rebuild {accountId,usageDate}` 在账号锁内按逐次事实重建指定 UTC 日汇总；`POST /api/v1/admin/quota-reservations/{id}/reviews {decision:SETTLE|RELEASE,evidenceNote}` 只核定 `REVIEW_REQUIRED` 预占并同时更新余额与不可变流水，不能无证据自动释放未知费用。

DEV-11 Webhook 协议：后台 `/api/v1/developer/webhook-endpoints` 和 Management Key `/management/webhook-endpoints` 调用同一账号 Service。`POST` 请求 `{name,url,events:[avatar.generation.succeeded|avatar.generation.failed],timeoutMs?,maxAttempts?}`，需 `Idempotency-Key`；返回 `endpointId,status,revision,signingSecret,secretAvailable`，同键重试只返回 `signingSecret:null`。`POST /{endpointId}/secret` 轮换、`POST /{endpointId}/status {status:ACTIVE|DISABLED}` 变更状态，均需 `If-Match` 和 `Idempotency-Key`；密钥轮换后旧密钥立即失效。`GET /{endpointId}/deliveries` 与 `GET /deliveries/{deliveryId}/attempts` 按账号和时间分页，仅返回安全状态、HTTP 状态和错误码。任务创建可选 `webhookEndpointId`，必须属于当前账号且已启用；同一任务的幂等参数包含此 ID。任务终态事务写含白名单快照的 Outbox，消费者幂等建立 delivery；重试保持同一 event ID 和正文，每次尝试使用当前 endpoint 密钥按 `webhook-id.timestamp.rawBody` 签 `v1,Base64(HMAC-SHA256)`。签名时间戳为 Unix 秒，事件正文时间为任务终态 UTC 时间。投递前重新核对 HTTPS 公网地址与原主机 TLS、拒绝重定向；最多 6 次，耗尽或停用不改变任务状态。delivery/attempt 长期保留，不保存响应正文、签名头或秘密；接收端按 event ID 去重，任务查询仍是真实状态来源。

可选 `webhookEndpointId` 同样适用于新角色完整制作、单动作重做及现有角色的新版本完整重制；仅继承旧版本而不产生制作任务时不接受该字段。服务端在创建任务的事务中校验 endpoint 归属与启用状态，幂等摘要包括该字段。

管理 Key 的权限按资源族分为 `assets:read/write`、`generation:read/write`、`config:read/write`、`keys:write`、`usage:read`、`webhooks:write`；具体操作不得因为拥有同账号身份就绕过 Scope。官方发布、账号额度授予和平台级封禁只允许管理员后台身份。每个开放 Controller 调用与后台入口相同的领域 Service，不复制业务状态机。

### 2.2 BUSINESS Session API

| 方法与路径 | 凭证及来源 | 结果与失败边界 |
|---|---|---|
| `POST /openapi/v1/sessions` | Application Secret；`externalUserId`、`Idempotency-Key` | 创建当前运行 Session 前经 system 预留配置依赖；未 ACTIVE 不签发运行 Token；旧 Session 不恢复 |
| `GET /openapi/v1/sessions/{sessionId}` | 同一应用 Secret 和经后端声明的同一 `externalUserId` | 仅查询当前运行状态、固定配置版本及到期，不含聊天正文；仅知道 Session ID 不足以查询 |
| `DELETE /openapi/v1/sessions/{sessionId}` | 同上，幂等键或 If-Match | 先不可恢复、撤销、停止，再可靠清理与释放引用 |
| `POST /openapi/v1/sessions/{sessionId}/tokens` | 同上，`sessions:grant`、`Idempotency-Key` | 签发 15 分钟浏览器授权；临近到期续签时旧 grant 保持到原到期时间，WSS 连接仍单活 |
| `POST /openapi/v1/sessions/{sessionId}/revocations` | 同上，`Idempotency-Key` | 撤销 Session；用户退出全部会话时按 `(applicationId,externalUserId)` 撤销，不能跨应用 |

DEV-06 的 BUSINESS Session 接口于 2026-09-25 随用户授权实施本模块而固定如下。浏览器不能调用这些 Application Secret 接口；不提供历史查询、已结束 Session 恢复或持久 Session Context 接口。DEV-07 的浏览器运行接口与 WSS 事件见下节。

#### DEV-06 请求、响应与事件 Schema（2026-09-25）

所有请求使用 `Authorization: Bearer lna_...`。system 按 `sessions:create/read/grant/end/revoke` Scope 验证当前 Secret、账号和应用；session 再匹配固定 `(accountId,applicationId,externalUserId,sessionId)`。`externalUserId` 只由可信开发者后端声明，区分大小写，非空且最多 191 字符，不接受控制字符；新 BUSINESS Session 不接受 `__ln_debug__:` 保留前缀。JSON 中的 ID 为十进制字符串。写入的 `Idempotency-Key` 为 1～64 个可见 ASCII 字符；结束可用 `If-Match: <revision>`。同键异参返回 409。

| 方法 | 请求 | 成功响应 | 主要失败 |
|---|---|---|---|
| `POST /openapi/v1/sessions` | `Idempotency-Key`；`{"externalUserId":"user-1","queryCredential":"可选短期只读凭证"}` | `{"sessionId":"...","applicationId":"...","configVersionId":"...","status":"ACTIVE","createdAt":"...","lastActivityAt":"...","expiresAt":"...","revision":"..."}` | 400 参数，401 Secret，403 Scope/停用，409 配置/幂等，422 资源，429 账号 Session 上限，503 内部授权不可用 |
| `GET /openapi/v1/sessions/{sessionId}?externalUserId=user-1` | 同一应用 Secret，无正文 | 同上当前状态、固定配置和期限 | 404 跨身份或不存在，410 已结束 |
| `DELETE /openapi/v1/sessions/{sessionId}` | `Idempotency-Key` 或 `If-Match`；`{"externalUserId":"user-1"}` | `DELETED` 墓碑摘要 | 404 跨身份，412 版本不符，428 缺少前置条件 |
| `POST /openapi/v1/sessions/{sessionId}/tokens` | `Idempotency-Key`；`{"externalUserId":"user-1","scopes":["session:read","avatar:read"]}`，省略 `scopes` 使用当前允许权限 | `{"token":"ln2....","expiresAt":"...","scopes":[...]}`；同键有效期内重建原 Token | 400 超范围，409 已结束/同键异参/旧 grant 过期 |
| `POST /openapi/v1/sessions/{sessionId}/revocations` | `Idempotency-Key`；`{"externalUserId":"user-1","allSessions":false}`；`true` 仅作用于同应用同用户 | `{"revokedSessions":1}` 或对应数量 | 404 跨身份，409 同键异参 |

运行 Scope 从固定配置与当前授权计算：基础 `session:read/avatar:read/speak:write`；CHAT 增加 `chat:write`，有 ASR 增加 `asr:write`，启用 Context 增加 `context:capture`。可选 `queryCredential` 由已鉴权后端在创建时提供，AES-GCM 加密写 Redis，最多保存 15 分钟且不超过 Session 剩余期限；结束/撤销删除，不进入 Token、数据库、Prompt 或浏览器响应。Tool 消费由 DEV-08 按当前业务身份授权。

`v2` payload 字段顺序固定为 `jti,accountId,applicationId,sessionId,configId,source,iat,exp`，ID 为十进制字符串，时间为 UTC 毫秒；`kid` 存于 `s_session_grant.signing_key_version`，与 Application Secret `issuer_key_epoch` 分离。验签后对照持久 JTI、来源、ID、时间、epoch、Scope 与当前 system 状态。DEBUG 新授权同用 v2，旧 v1 仅按原到期时间读取。续签的新旧 grant 并存至各自原到期，Secret 重置不追溯撤销既有 grant；管理员禁用及解除禁用递增应用 epoch，旧 grant 不复活。

跨库状态为 `CREATING → ACTIVE → DELETING → DELETED`；失败创建保留 `FAILED` 墓碑至引用释放后转 `DELETED`。system 按 `(accountId,sessionId,referenceOperationId)` 预留、确认和释放配置、Avatar、Voice、Relay、Skill 版本引用；session 固定版本和引用操作号，重启扫描补偿。撤销事务写 `s_outbox` 的 `BUSINESS_SESSION_REVOKED` v1 事件，payload 例 `{"sessionId":"123","applicationId":"456"}`；引用释放与本地停止完成后标记完成。Session/principal 墓碑及长期脱敏事实保留，临时对象进入删除队列。DEV-07 从此状态定义 WSS 终止/重授权事件与单活连接语义。

部署配置：`LN_SESSION_TO_SYSTEM_URL` 与 `LN_SESSION_TO_SYSTEM_INTERNAL_BEARER` 是既有 session→system 内部身份；`LN_SESSION_RUNTIME_TOKEN_KEY_VERSION` 指定当前 `kid`，对应秘密来自 `LN_SESSION_RUNTIME_TOKEN_SECRET_<kid>`（版本 1 兼容 `LN_SESSION_RUNTIME_TOKEN_SECRET`），至少 32 字节，旧版本须保留至对应 grant 全部到期。使用可选 `queryCredential` 时须配置 32 字节 Base64 的 `LN_BUSINESS_CREDENTIAL_ENCRYPTION_KEY` 和共享 Redis。创建幂等事实只保存该凭证的服务端 HMAC 摘要；同键不同凭证返回 409，不在重试时延长旧凭证 TTL。账号 `p_account_limit.max_sessions` 未配置或为 0 时拒绝新建业务 Session。

浏览器运行接口在 DEV-07 实现 `/api/v1/runtime/session`、`connection-tickets`、`avatar-package`、`media/{mediaId}` 和 `stop`；`asr`、`context-captures` 分别由 DEV-09/10 追加，每次从短期授权推导 Session。`GET /api/v1/runtime/session` 只返回当前状态、固定配置版本、有效权限和正在运行的 turn 摘要；不返回聊天正文。删除接口01的 `/api/v1/runtime/messages`。WSS 的 `text.delta` 以 `turnId`、requestId 和序号关联，不再包含指向持久 `s_message` 的 `messageId`；SDK 显示当前连接收到的文本，页面刷新后若需旧内容，由开发者自己的后端提供。

运行 Session 是平台处理当前连接、固定配置、停止、单活、幂等和额度的状态边界；它不是聊天记忆，也不因日志长期留存而永久可运行。浏览器授权是持有者可执行该 Session 操作的凭证，二者不应混称为“短期 Session”。BUSINESS Session 自最后一次成功业务活动起闲置 2 小时结束，创建满 24 小时强制结束，先到者为准；心跳不续期。浏览器授权 15 分钟，到期前由 SDK 经开发者后端续签，旧 grant 不因正常续签提前失效，最多保留到原到期时刻。Session 元数据墓碑及脱敏业务日志长期保留，与运行期限独立。

#### DEV-07 浏览器 SDK 与运行连接 Schema（2026-09-25）

`SessionClient` 独立于 Vue，构造参数为 `{baseUrl,getToken,player}`。`getToken():Promise<{token:string,expiresAt:string}>` 只调用开发者可信后端完成业务登录复核、Session Token 首次获取和到期前续签；Application Secret 不进入浏览器。核心方法为 `connect():Promise<SessionState>`、`chat(text)`、`speak(text)`、`stop(reason?)`、`playAction(action)`、`resumeAudio()`、`on(listener)`、`destroy()`。`chat.create` 和 BUSINESS 的 `speech.create` 在 DEV-08/09 上线前返回 `CAPABILITY_NOT_ALLOWED`；本模块可用的 BUSINESS 终点是 Session 状态、正式角色包、单活连接、动作和 stop。SDK 错误事件带稳定 `code`，能关联时带 `requestId/turnId`；网络断开、自动播放阻止、资源过期及不支持动作均发事件。

| 接口 | 请求 | `data` 响应/规则 |
|---|---|---|
| `GET /api/v1/runtime/session` | `Authorization: Bearer ln2...`（旧 DEBUG v1 仅原期限兼容） | `sessionId/applicationId/configVersionId/status/expiresAt/connectionEpoch/effectiveScopes/capabilities/effectiveLimits/activeTurn`；`activeTurn` 只有 ID 与状态，不含正文。 |
| `POST /api/v1/runtime/connection-tickets` | `{purpose:"CONNECT"}` 或 `{purpose:"REAUTHORIZE",connectionEpoch:"<当前十进制代数>"}` | `{ticket,expiresAt,webSocketUrl,protocol:"ln-avatar.v1"}`；30 秒一次性，绑定精确 grant、Session、用途；旧未绑定票据拒绝。续权票据只可由当前连接同 principal 消费，不分配新 epoch，不停本轮。 |
| `GET /api/v1/runtime/avatar-package` | 当前 Token 与 `avatar:read` | 正式 manifest，交给 `AvatarPlayer.loadPackage`；资源过期需重新获取。 |
| `GET /api/v1/runtime/media/{mediaId}` | 当前 Token 与 `speak:write` | 当前轮所属 WAV，`no-store`；过期或停止后不可读。 |
| `POST /api/v1/runtime/stop` | `{turnId,reason}`、当前 Token、`X-Connection-Epoch: <当前十进制代数>` 与 `speak:write` | `{turnId,alreadyStopped}`；本地先停止，旧 turn ID 不停止新轮。 |

WSS 固定 `/api/v1/realtime`、子协议 `ln-avatar.v1`，浏览器不用握手 Authorization 头。5 秒内首帧 `connection.auth` 携一次性 CONNECT ticket；返回 `connection.ready`，包含当前 epoch、权限、能力、限制与授权到期。`connection.reauthorize` 携一次性 REAUTHORIZE ticket 和当前 epoch；成功返回 `connection.reauthorized` 的新到期与有效 Scope，旧 grant 仍按自己原到期失效。服务端对每条新业务命令按精确 grant、Scope、Session/配置/用户与连接代数核验；`ping/pong` 只保活传输，不延长 Session。连接 epoch 在数据库持久递增，Redis 仅接受更高代；后连接取代前连接并停止旧轮。旧连接收到 `connection.replaced` 后关闭；跨节点旧连接最多一个扫描周期后关闭，期间不能再执行命令或接收新音频。20 秒心跳、60 秒无帧关闭；普通网络重连最多 3 次（约 1/2/4 秒加抖动），先查询当前状态，不恢复正文、不重发轮次或音频。被替换、撤销和删除不自动抢回连接。

WSS 信封沿用[平台内共享契约 C4.2～C4.3](platform-console-shared-contract.md)：`v/type/requestId/sessionId/connectionEpoch/turnId/seq/occurredAt/data`，seq 按连接和 turn 独立递增并在发送锁内分配。SDK 只消费当前连接代、递增序号和未停止 turn 的事件；`connection.replaced/revoked` 及 `request.error` 均携稳定错误码或原因。`speech.create/turn.stop/playback.report` 属现有 DEBUG 播报内核；BUSINESS 的 chat、TTS、ASR、Context 和指导事件分别由 DEV-08～10 开通，不用空成功响应代替。浏览器释放 WSS、当前音频、Object URL、待处理 fetch、动画帧与监听器。

部署须设置 `LN_PUBLIC_RUNTIME_WS_URL` 为公网 `wss://.../api/v1/realtime`；仅 loopback 本地开发可用 `ws://`。Gateway 与 session 同用 `LN_RUNTIME_ALLOWED_ORIGINS` 的明确页面来源列表，正式环境只接受 HTTPS 来源；没有配置时不开放跨源调用。Gateway 仅将 runtime 短期 Token 与 WSS 首帧票据交 session 验证，不按后台 JWT 解析，也不放行其他 API 路由。

#### DEV-08 CHAT、LLM 流与 HTTP Tool Schema（2026-09-26）

`chat.create` 是已连接 WSS 命令，`data={text:string}`（非空，最多 4096 字符），要求当前 grant 的 `chat:write`、CHAT 固定配置和当前连接代数。服务端先写 `s_turn`（`include_in_history=0`，只有摘要/状态/输入哈希），再依次发 `request.ack {requestId,turnId,status:"ACCEPTED"}`、`turn.started {mode:"CHAT"}`；随后 `text.delta {text}` 按序递增，终态 `turn.completed {textStatus:"COMPLETED"}` 或 `turn.failed {code}`。新消息替换旧轮；`turn.stop` 与 HTTP stop 只停止指定当前 turn。断线或新连接不重放正文。SDK 的 `chat(text)` 返回请求 ID，`on` 订阅流式事件；停止发生在 ack 前时，SDK 收到 turn ID 后补发 stop。后台登录可为 CHAT 应用创建 DEBUG Session，grant 限于固定配置并含 `chat:write`，调试页只在点击发送后发起 Relay 请求。

Session 服务通过受保护内部身份读取固定 CHAT 配置及已确认 SESSION 引用，每次新 LLM/Tool 外部操作重新核对当前账号、应用、授权、Relay/Skill 状态和公网 DNS；系统返回经过验证的 `pinnedAddress`，运行端固定连接该 IP，并用原域名做 TLS 主机验证。禁止重定向、私网目标和未经声明的端点。Relay `POST /chat/completions` 请求固定为 `{requestId,applicationId,sessionId,turnId,externalUserId,model,messages,tools,parameters,stream:true}`；`externalUserId` 在 BUSINESS 中只取持久的已鉴权 principal，DEBUG 中使用从当前已鉴权 Session ID 构造的保留身份，平台只传固定 System Prompt、启用 Prompt Skill、本轮用户输入及本轮 Tool 结果，不附旧轮历史。请求与响应均有字节上限，不自动重试模型或 Tool 业务请求。

LN_RELAY/1 的 SSE 每个事件使用单行 JSON `data:`，可有 `event:` 指定类型，事件以空行结束；类型为 `text.delta {text}`、`tool_call.delta {id,name?,arguments?}`、`response.completed {usage?:{inputTokens?,outputTokens?},providerRequestId?}`、`error`。Tool 参数片段只能在 `response.completed` 后作为完整 JSON 对象解析；模型提议的名称必须属于固定应用配置。每轮 Tool 总次数默认 10、显式限值至多 20，并且每个 Tool 仍受其 `maxCallsPerTurn` 限制。Tool 输入和输出只允许发布时确定的标量 Schema；服务端注入身份头 `X-LN-External-User-Id-B64`、`X-LN-Application-Id`、`X-LN-Session-Id`，需要业务查询凭证时从短期加密 Redis 读取并以 `X-LN-Query-Credential-B64` 传递，`-B64` 为 UTF-8 Base64URL 无填充。开发者 Tool 服务负责实际只读和业务身份授权；平台不从 GET/POST 推断无副作用。成功 Tool 结果按输出 Schema 过滤后只送回本轮模型，浏览器的 `tool.result` 仅含 `frontendFields`；失败送模型稳定错误码，不编造业务结果。

每次 LLM/Tool 在外部提交前写 `s_operation` 和同事务 `CALL_FACT_RECORDED` Outbox；被绑定、Schema、次数或凭证预检拒绝的模型 Tool 提议只作为失败结果回给模型，不建立外部调用事实；终态再写事实、可获取 token 用量，并转发到 `p_call_record/p_usage_daily`，归属为 `DEVELOPER`。停止、退出或在途连接关闭不能证明上游未计费，在途事实标为 `UNKNOWN`，留待 DEV-11 核对。请求正文、Tool 参数与原始结果只在有界当前轮内存中，不进入数据库、Outbox 或日志。DEV-08 的 session V6 增量迁移解除 V1 的 CHAT 历史标记约束；原 V1 文件不改。真实 Relay/Tool 网络、费用和用户终点须独立验收。

#### DEV-09 ASR、官方 TTS 与独立播报 Schema（2026-09-27）

`POST /api/v1/runtime/asr` 使用 Session Token、`asr:write` 与 1～64 位可见 ASCII `Idempotency-Key`；`multipart/form-data` 只含完整 `audio` 文件及可选 `language`。录音 MIME 限 `audio/webm|audio/mp4|audio/ogg|audio/wav`，文件至多 1.9 MB；服务端从 grant 推导账号、应用、Session、固定配置和 BUSINESS 身份，DEBUG 使用隔离身份。固定配置必须含 ASR Relay 版本；平台通过受保护的 Relay resolve 读取 ASR 授权与固定公网地址，随后以 `LN_RELAY/1` 的 `POST /audio/transcriptions` 提交完整录音。响应 `{code:"OK",data:{operationId,text,language?}}`；识别结果仅回当前请求，不在 Session 数据库保存。调用前写独立 `s_operation` 与 Outbox；网络提交后未确认的调用记 `UNKNOWN`，待核对。SDK `startRecording/endRecording/cancelRecording` 负责麦克风权限、格式、大小、取消和错误事件；`endRecording(true)` 在识别成功后提交 chat，默认返回文本供开发者确认。

WSS `speech.create {text}` 对当前 BUSINESS/DEBUG Session 开放，使用 `speak:write`、固定官方 Voice 与当前授权；CHAT 应用同样可独立 speak，但此命令不调用 LLM/Skills/Context。`chat.create` 的 LLM 流持续发送 `text.delta`；完整结果触发 `text.completed`，随后按句界及段长度合成官方 TTS，尾段也合成。每个新段提交官方服务前核对 grant、连接代、应用状态并以 `turnId:ordinal` 预占 `TTS_CHAR`；成功合成结算，提交前取消释放，结果不确定保留待核对。逐段 `s_operation`、额度流水及调用事实保留，正文与音频仅在内存/临时存储。`audio.segment` 含 `segmentId,ordinal,mediaId,mimeType,durationMs,expiresAt`；SDK 按 ordinal 播放并用 `playback.report {segmentId,state:STARTED|ENDED|FAILED}` 驱动 speaking 和后续段。`turn.completed` 表示播放完成；合成/播放失败发 `audio.failed` 或 `turn.failed`，文字仍由当前浏览器保留。stop 先终止本地音频/录音，再发服务端 stop；停止轮的迟到段不可读取或播放。

官方百炼实时 TTS 适配器固定 `response_format=pcm`、`sample_rate=24000`，只在收到 `response.audio.done` 与成功的 `response.done` 后发送 `session.finish`，收到 `session.finished` 才将 PCM 封装为受限的单声道 WAV 并写临时媒体。不能在 `response.audio.done` 后立即关闭 WebSocket；百炼会将这种提前断开记为 `ClientDisconnect`。停止轮若外部合成最终成功，逐段额度与调用事实仍记成功，播放保持 `STOPPED` 且媒体不可读。DEBUG Token 的 `asr:write` 只随固定 CHAT 配置中的 ASR Relay 授予；BUSINESS Token 同样从固定配置计算该 Scope。

#### DEV-10 Page/Hybrid 页面感知与指导 Schema（2026-09-27）

`ELEMENT` 不再是合法来源，旧配置版本也不得在运行时执行 Element 采集。`PAGE` 仅传当前页面选定范围的截图；`HYBRID` 在相同范围传截图及浏览器端过滤后的 DOM/文本和临时元素引用。默认范围为当前视口；`FULL_PAGE` 只允许开发者显式请求，且固定配置与应用当前策略均开启 `fullPageEnabled`。AI 按需请求只能使用 `VIEWPORT`，不能指定 CSS、URL 或任意页面元素。两种来源的截图都保持选定范围原样，不按 DOM deny 遮蔽；DOM deny 优先于 allow，密码输入默认排除。未获授权范围不上传，跨域内容不通过服务器代理读取。

`chat.create` 可带 `contextMode:AI_ON_DEMAND`、`businessContext`（本轮有界 JSON，最多 32 KiB）及 `contextRequest:{source:PAGE|HYBRID,coverage:VIEWPORT|FULL_PAGE,resultMode:PARTIAL|STRICT}`；无 `contextRequest` 且未选择 `contextMode` 时普通聊天不采集。显式采集须由开发者调用 SDK，或经开发者按钮触发；服务端先建轮次并验证 `context:capture`、固定配置与当前策略、次数和连接，再通过 WSS 发 `context.request {captureRequestId,source,coverage,resultMode,deadlineAt}` 给本轮活跃 SDK。AI_ON_DEMAND 只在用户当前轮向模型提供受控 `ln_capture_context` 工具，工具参数只允许 `source:PAGE|HYBRID`，平台统一限定 `VIEWPORT` 并执行同样的授权与次数检查；每轮最多取固定 `maxCapturesPerTurn`、当前应用限值和硬上限中的最小值。请求超时、停止、连接替换均使本次 ID 失效。

SDK 通过 `POST /api/v1/runtime/context-captures` 上传 multipart：`metadata` JSON 和 `screenshot0..n` 图片。Metadata 含 `captureRequestId,turnId,connectionEpoch,source,coverage:{mode,x,y,width,height,truncated},screenshot:{status,parts,reason?},dom:{status,text?,elements?,excludedCount?,reason?}`；服务端从短期 Token 推导身份，仅接受本轮待处理、同一连接代和同一来源的 ID。`captureScope.allow` 存在时截图与 DOM 均限制到固定配置和当前策略共同允许的元素区域；deny 区域与截图相交时截图失败。图片与文本分别限制字节数和覆盖范围；结果返回 `captureRequestId,screenshotStatus,domStatus,coverage`，同一 ID 的冲突重放拒绝。部分结果模式允许已成功且已授权的一侧继续，STRICT 要求所需部分全部成功；失败原因使用稳定码，如 `CONTEXT_SCOPE_DENIED/CROSS_ORIGIN/FILTER_FAILED/PIXEL_LIMIT/CONTEXT_TIMEOUT`。原始图片、DOM、业务 Context 和元素引用仅在本轮有界内存与短期传递中使用，不进普通数据库或日志，结束、失败、停止和替换时清除。

模型只能提出 `ln_highlight_element {captureRequestId,elementRef,scrollIntoView}`，平台确认该引用属于当前轮的 HYBRID 采集，再发送 `guidance.proposed`。SDK 在本地检查引用仍对应同一节点、节点仍存在且属于当前有效范围后，按 `EVENT_ONLY` 只通知开发者 UI，或按 `AUTO` 高亮；滚动还须开发者允许。提供清除和 `guidance.result`，失效返回 `TARGET_STALE`，不接受模型任意 CSS 选择器或执行点击、填写、提交。`PAGE` 不产生元素引用。业务 Context 与页面内容均作为用户数据输入模型，不能覆盖系统指令、身份或 Scope。

## 3. 一个应用配置凭证、会话授权与重置

1. 开发者在后台为每个 Application 分别配置一个有效 Application Secret，保存在自己的可信后端；同一账号的多个 Application 不共用 Secret。重新发布某应用的配置版本不自动换该应用的 Secret；平台在每次后端接入时检查目标应用当前可用状态。开发者后端验证业务用户后，以目标应用的 Secret 创建当前运行 Session 并取得浏览器短期授权。短期授权在有效期内可用于该 Session 的多次请求，不按每次 chat/speak 重新签发。`Idempotency-Key` 是写操作去重标识，WSS ticket 仅用于一次连接认证，二者不是开发者要管理的应用 Token。
2. 开发者主动重置 Application Secret 时，system 在事务中使旧 Secret 失效、递增旧 Key 的 `auth_epoch` 并登记新 Secret；同一应用仅一个有效 Secret。现有 `p_access_key` 只有 `public_id` 唯一键，实施时锁应用行串行化创建/重置，并以增量生成列 `active_application_id` 的唯一键防止并发留下两把 ACTIVE Secret。旧 Secret 从事务提交起不能创建 Session 或签发新授权；已有 grant 不因旧 Key 状态/epoch 变化而撤销，既有 WSS 连接不强断，grant 只保留到自己原到期时刻。Secret 重置不递增 Application/Session 的运行授权 epoch。新 Secret 只显示一次；重置由已登录后台或有 `keys:write` 的 Management Key 发起，不能由旧 Secret 自行重置。
3. 平台管理员禁止某个 Application 时，增量迁移中的 `p_application.admin_disabled` 独立于开发者可编辑的 `status`：管理员入口先持久化禁用并递增应用 `auth_epoch`，随后禁止该应用的新 Session、Secret 重置、浏览器授权签发和禁用后新发起的 chat/speak/ASR/Tool/Context 等业务动作及新外部副作用；不强制断开既有 WSS，也不打断已交付的音频。正在执行的外部请求不宣称撤回，后续副作用不得继续发起。开发者不能通过发布配置、重新启用普通状态或新建 Secret 绕过；仅管理员入口可解除禁用，解除时再次递增 epoch，旧授权不复活。账号停用仍是覆盖所有应用的总开关。
4. Application Secret 创建/重置响应若丢失，旧 Secret 已失效而新明文无法从摘要恢复。后台应展示新 Key 的脱敏状态，允许已登录开发者再次重置未知 Secret；同一幂等键只返回原操作元数据，不把新 Secret 存入幂等缓存。管理员禁用期间该恢复操作也被拒绝。
5. 浏览器短期 Session Token 响应若丢失，同一幂等键只复用原有效 grant：用持久化的 JTI、Scope、到期时间和签名密钥版本重建同一授权的 Token，不新增 grant、不延长期限、不保存 Token 明文。原 grant 已过期/撤销时，只有当前运行 Session 才可由可信后端重新验证用户后用新键签发；已结束 Session 必须新建。开发者如需使某业务用户退出，用用户或 Session 级撤销；不能让该用户自己拿应用 Secret 重置全部人的凭证。
6. 第一版不提供单独的“重置浏览器授权”按钮；用户退出或后端显式撤销按 Session/业务用户撤销接口处理。普通续签由 SDK 调用开发者后端，后端重新验证其业务登录并以当前 Application Secret 取得新 15 分钟 grant；只在临近到期时续签，同一 Session 的新旧 grant 可短暂并存，旧 grant 的到期时间不变，单活 WSS 连接通过 REAUTHORIZE 使用新授权。新 grant 签发、响应丢失或重连均不能把旧 grant 延期，也不能提前停掉正在播放的内容；到期后的旧 grant 不能用于新命令、连接认证、票据或媒体读取，未断开的 WSS 须先完成有效授权的 REAUTHORIZE 才可继续发起动作。

一个**浏览器也共用的**应用级 Token 无法区分业务用户和 Session；本协议确定浏览器使用单独的短期授权，Application Secret 只留在可信后端。

运行 Session 与浏览器凭证是两件事：即使不保存聊天历史，当前 WSS、单活、停止、额度、临时音频仍需要可定位的运行状态；2 小时闲置/24 小时最长限制仅约束当前运行，不表示历史可恢复。浏览器直连平台用绑定用户、Session 和 Scope 的 15 分钟授权；Secret 重置不追溯撤销它，显式退出/撤销、Session 到期及管理员禁用新动作仍分别生效。

运行 Token 统一采用现有 `RuntimeTokenCodec` 的 HMAC-SHA256 机制升级版 `v2`，并保留持久 JTI/grant 校验；不另引入与现有 DEBUG 并行的 JWT 签发器。`v2` 格式为 `ln2.<kid>.<payloadB64>.<macB64>`；payload 是字段顺序固定、无多余空白的 UTF-8 JSON，整数 ID 用十进制字符串，`iat/exp` 用 UTC epoch 毫秒，签名输入是前三段的原始 ASCII 字节。payload 仅含 `jti`、账号/应用/Session/配置内部 ID、授权来源、签发/到期时间，不放业务用户明文、Voice 绑定、Scope 或秘密。Scope、固定 Voice 与当前状态从持久 grant/Session/配置读取，不能信任客户端声明。`s_session_grant` 增量保存独立的 `signing_key_version`，与 Application Secret 的 `issuer_key_epoch` 不混用；同幂等键可用 JTI、持久时间和保留的签名密钥版本重建原 Token。验签先限制大小、版本、`kid` 和算法，再以常量时间比较签名，并将签名后的所有 ID、来源、时间及 `kid` 与持久 grant 对照；签名密钥来自部署秘密配置，旧版本至少保留到对应有效 grant 全部到期。旧 `v1` 仅供已签发 DEBUG Token 在其原有效期内兼容读取；新 BUSINESS/DEBUG 均签发 `v2`，待最长 15 分钟旧 Token 到期后撤去 `v1` 读取。旧接口01的 JWT+JTI 是历史设计，不再作为新运行凭证格式。

## 4. 新动作的授权检查点

授权检查针对**新发起的业务动作和新的外部副作用**，不对流里的每个字节查询数据库，也不因开发者正常重置 Secret 强制断开既有连接。三种情况须分开：

| 事件 | 后端使用旧 Secret | 已签发浏览器 grant | 已建立连接/已交付音频 |
|---|---|---|---|
| 开发者重置 Secret | 提交后新请求立即拒绝 | 不追溯撤销，到各自原到期时刻失效；只能用新 Secret 续签 | 不强断、不停止既有播放；仍受 Session、用户及应用当前状态约束 |
| 普通浏览器授权续签 | 使用当前有效 Secret | 新旧短暂并存，旧授权不延期 | REAUTHORIZE 或重连使用新授权；一个 Session 仍只允许一个活跃 WSS |
| 管理员禁用 Application | 禁止新 Session、续签和 Secret 重置 | 无法用于禁用后新发起的业务动作，解除禁用不复活旧授权 | 不强断连接或已交付音频；禁止新命令和后续外部副作用 |

- 每次 Application Secret 后端请求核对当前 Key；每次新业务 HTTP 请求、WSS 首帧认证/重连、新 turn、Tool/页面采集与每段新付费调用前核对 grant、Session/用户和应用当前状态。Secret 的签发 Key ID/epoch保留审计用途，但运行检查不因该 Key 后来正常重置而拒绝此前合法签发的 grant。账号停用、业务用户退出/显式撤销、Session 到期或结束仍按各自撤销规则生效。
- 管理员禁用从持久状态提交后阻止新业务动作；已开始的外部请求和已交付的音频不强行撤回，当前长流的下一次 Tool/TTS 等新副作用必须重新检查应用状态。单纯保持 WSS 传输连接不授予发起新命令的权利。
- 缓存只提高性能，不是授权事实来源。无法确认当前应用/Session 状态时拒绝新动作；Outbox 通知可辅助更新连接状态，但不承担强制断连语义。
- DEV-01 完成旧 Secret 新请求拒绝与管理员禁用事实，DEV-05 完成应用/资源收紧，DEV-06 完成 grant 与跨库核对，DEV-07～10 在各自命令和副作用入口接入校验，不在最后另补一套过滤器。

## 5. 网络、计额及模块交接

- LLM/ASR Relay 沿用接口01第 12 节中不冲突的 `LN_RELAY/1` 固定相对路径及 `GET /capabilities` 无计费握手；TTS 路径不用于开发者 Application。LLM 请求不包含平台历史消息。每轮由平台发送固定 System Prompt、已启用 Prompt Skills、当前用户输入和本轮 Context；同一轮 Tool 后续调用可附本轮临时 Tool 消息。平台从已验证的 BUSINESS principal 向所属开发者 Relay 传递 `externalUserId`；CHAT DEBUG 使用保留的 Session 身份，并带 `applicationId/sessionId/turnId`，使开发者能跨新的 Session 关联自己维护的多轮记忆；浏览器和模型不得覆盖此身份。每次模型操作仍使用稳定 requestId 防止重复计费。平台不保证开发者 Relay 的保存与删除。WSS 信封、Webhook 签名沿用接口01第 9、13.2 节中不冲突的字段，`messageId` 和历史回放字段除外；音频由官方 TTS 固定路径提供。实现时将所消费的请求/响应 Schema 固定在本协议或机器可校验的同源文件，不再引用旧路由或历史查询。
- Relay、Tool 和 Webhook 的开发者配置 URL 均只允许 HTTPS；拒绝本机、环回、私网、链路本地、元数据及平台内部目标。每次实际连接重新解析并验证，连接必须使用已验证目标；禁止重定向，防止校验后 DNS 改变或跳转进入内网。连接探测不调用付费模型。
- DEV-02/06/08/09/10 在产生任务、Session、turn、外部调用或临时对象时同步执行对应的并发、额度或存储准入与释放；每次调用提交前在所属服务库写独立事实/Outbox，结果到达后更新，长期保留。`p_call_record` 经可靠事件最终一致，不把跨库延迟解释成 Session 结束才记日志。DEV-11 负责统一查询、汇总、补偿和 Webhook 投递，不能等它完成才开始阻止超限请求。留存迁移需清空旧调用到期值、调整日汇总非空到期列、Avatar 任务及 Webhook delivery 的到期字段与清理任务，并保证业务记录与其必要父记录不被删除；临时素材/音频仍按原生命周期清理。
- DEV-05 交付配置编辑、发布、版本、引用与授权；CHAT、ASR、Skill、Context 的真实调试操作分别随 DEV-08～10 实现并验收。DEV-02 仅交付官方 Voice 目录；DEV-03 不再交付私有 Voice。
- 每个跨服务边界至少保留一个聚焦的可运行契约检查：Key 类型隔离与撤销、Session 重置/丢响应同键重试、跨库引用恢复、WSS 旧连接与迟到事件、Relay 能力探测和错误、Webhook 目标与签名。静态检查、迁移/服务、接口联通和用户终点仍分别记录，不互相代替。
