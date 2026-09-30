# 开发者接入简化接口契约

> 起草日期：2026-09-29  
> 角色边界修订：2026-09-30  
> 项目索引复核：2026-09-30  
> 验收规则：本契约只在全部实现与迁移完成后进行一次统一验收，不按接口组或模块分阶段验收。  
> 配套计划：[开发者接入简化执行计划书](2026-09-29-developer-integration-simplification.md)  
> 生效规则：本契约经用户确认后，覆盖旧开发者接入契约中的冲突条款；未冲突的通用安全、幂等、额度和临时数据清理规则继续有效。

## 1. 接口边界

平台只向开发者代码开放 Application 运行接口，不开放资源管理接口。

明确不存在：

- Management Key；
- `/openapi/v1/management/**`；
- LLM/ASR Relay API；
- Webhook API；
- Application config-version API；
- Skill version API；
- 平台 LLM `chat.create` 编排。

开发者通过平台登录 Token 操作开发者后台；可信后端使用 Application Secret 操作 Session；浏览器使用短期 Session Token 操作当前 Session。管理员使用独立管理员身份维护平台和公共资源，不具有开发者账号身份。

### 1.1 管理员与开发者不兼容身份

- 管理员和开发者是两个独立主体，不能根据“管理员权限更高”推导管理员也是开发者。
- 超级管理员的 `*:*:*` 或等价通配权限只解决管理员权限判断，不能生成开发者 `accountId`、资源归属或 Application 身份。
- 管理员调用开发者 Application、私有角色、私有 Skill、Secret、调试 Session 或开发者用量接口返回 403。
- 开发者调用公共资源制作/发布、额度授予、平台治理或管理员审计接口返回 403。
- 管理员端同样不存在 Management Key 和 Webhook；不能通过管理员菜单创建这些资源。
- 自动化测试和真实 Demo 使用独立开发者账号，不能把管理员账号兼作开发者测试账号。

### 1.2 管理员功能范围

管理员在开发者接入领域只负责：

- 制作、发布、下架和治理公共角色；
- 创建、发布、下架和治理公共声音；
- 创建、发布、下架和治理公共 Skills；
- 配置平台默认 ASR/TTS 等官方服务；
- 管理账号、角色、菜单、限额、额度、审计和平台级限制。

管理员不创建、配置、发布或调试开发者 Application，不创建 Application Secret，不管理开发者私有角色、私有 Skills 或业务 Session。

管理员公共资源接口统一使用管理员身份和 `/api/v1/admin/**` 边界：

| 路径族 | 作用 |
|---|---|
| `/api/v1/admin/public-avatars/**` | 公共角色制作与生命周期。 |
| `/api/v1/admin/public-voices/**` | 公共声音创建、试听、发布与生命周期。 |
| `/api/v1/admin/public-skills/**` | 公共 Skills 创建、编辑、发布与生命周期。 |
| `/api/v1/admin/accounts/**` | 账号限额、额度和平台治理；不得代替开发者操作 Application。 |

现有不符合以上前缀或同时复用开发者身份的管理员入口在迁移时统一收口，不能通过前端隐藏继续保留越权路径。

## 2. 开发者后台接口

### 2.1 Application

Application 当前配置：

```json
{
  "applicationId": "102089642576183523",
  "name": "文档助手",
  "description": "可选说明",
  "avatarId": "1001",
  "voiceId": "2001",
  "systemPrompt": "可选系统提示词",
  "skills": [
    { "skillId": "3001", "sortOrder": 0 }
  ],
  "status": "ACTIVE",
  "revision": "7",
  "updatedAt": "2026-09-29T14:00:00Z"
}
```

Application 不包含模式、模型、Relay、能力开关、Context、运行上限或配置版本字段。

`avatarId`、`voiceId` 和 `skillId` 是开发者选择的逻辑资源 ID。创建 Session 时，平台解析这些资源当时最新且可用的已发布内容，并把实际 `avatarVersionId`、`voiceVersionId`、Skill 安全配置及秘密引用写入内部 Session 快照。版本 ID 和快照 ID 不属于 Application 对外配置，不提供历史、选择或回滚接口。

Application 只能属于开发者账号。管理员没有 Application 列表、创建、编辑、发布、Secret 或调试权限。

| 方法与路径 | 权限 | 作用 |
|---|---|---|
| `GET /api/v1/applications` | `platform:application:read` | 查询当前账号 Application。 |
| `POST /api/v1/applications` | `platform:application:write` | 创建 Application。 |
| `GET /api/v1/applications/{applicationId}` | `platform:application:read` | 返回当前配置。 |
| `GET /api/v1/applications/{applicationId}/resources` | `platform:application:read` | 返回本账号私有资源和当前已发布公共 Avatar、公共 Voice、公共 Skill。 |
| `PUT /api/v1/applications/{applicationId}` | `platform:application:write` | 更新名称、说明、Avatar、Voice、System Prompt 和 Skills。 |
| `POST /api/v1/applications/{applicationId}/status` | `platform:application:write` | 启用或停用。 |
| `GET /api/v1/applications/{applicationId}/secret` | `platform:application:read` | 查询脱敏 Secret 状态。 |
| `POST /api/v1/applications/{applicationId}/secret/reset` | `platform:application:write` | 创建或重置 Secret，完整值只显示一次。 |
| `POST /api/v1/applications/{applicationId}/secret/{keyId}/disable` | `platform:application:write` | 停用 Secret。 |
| `POST /api/v1/applications/{applicationId}/secret/{keyId}/delete` | `platform:application:write` | 删除 Secret 可用状态并保留脱敏审计。 |

更新和状态变更使用 `If-Match: <revision>`。缺失返回 428，过期返回 412。写请求使用 `Idempotency-Key`；同键同参返回原结果，同键异参返回 409。

保存时必须验证 Avatar、Voice 和 Skill 的账号归属、可见性及当前状态。保存成功后新 Session 使用最新配置；已创建 Session 保持快照。

### 2.2 Skills

| 方法与路径 | 权限 | 作用 |
|---|---|---|
| `GET /api/v1/developer/skills` | `platform:skill:read` | 查询当前账号和可用官方 Skill。 |
| `POST /api/v1/developer/skills` | `platform:skill:write` | 创建 Skill 当前配置。 |
| `GET /api/v1/developer/skills/{skillId}` | `platform:skill:read` | 返回当前配置和引用状态。 |
| `PUT /api/v1/developer/skills/{skillId}` | `platform:skill:write` | 更新当前配置。 |
| `POST /api/v1/developer/skills/{skillId}/status` | `platform:skill:write` | 启用或停用。 |
| `DELETE /api/v1/developer/skills/{skillId}` | `platform:skill:write` | 无 Application 或活动 Session 引用时删除。 |

Skill 使用 `revision`，不存在版本发布和历史版本查询。Application 绑定 `skillId`。创建 Session 时复制当前 Skill 的名称、类型、Prompt 指令、Tool Schema、限制和加密秘密引用到内部快照；后台更新 Skill 只影响新 Session。Skill 被停用后，已有 Session 的后续 Tool 调用也必须拒绝。开发者创建的 Skill 固定为本账号私有；管理员只能通过 `/api/v1/admin/public-skills/**` 创建公共 Skill。管理员不能编辑开发者私有 Skill，开发者不能修改公共 Skill。

Prompt Skill：

```json
{
  "skillType": "PROMPT",
  "name": "商品说明",
  "instructions": "回答时优先使用已确认的商品信息"
}
```

HTTP Tool Skill：

```json
{
  "skillType": "HTTP_TOOL",
  "name": "库存查询",
  "toolName": "lookup_stock",
  "toolUrl": "https://developer.example.com/tools/stock",
  "httpMethod": "POST",
  "inputSchema": {},
  "outputSchema": {},
  "frontendFields": ["name", "stock"],
  "identityBinding": true,
  "timeoutMs": 5000,
  "maxResultBytes": 65536,
  "maxCallsPerSession": 10
}
```

HTTP Tool URL 只允许 HTTPS 公网目标。保存和调用时重新验证 DNS、TLS 和实际连接地址，拒绝本机、私网、链路本地、元数据地址、平台内部地址及重定向。Tool Token 单独加密保存，不进入 Application、Session 返回、浏览器事件、模型上下文或日志。

### 2.3 虚拟人制作

开发者通过开发者后台上传、制作、处理动作、组装和管理本账号私有角色。管理员通过 `/api/v1/admin/public-avatars/**` 制作和管理公共角色。两者使用独立身份和入口，不把管理员账号写成开发者资源 owner。

公共声音只由管理员通过 `/api/v1/admin/public-voices/**` 创建和发布；开发者只能在 Application 中选择当前已发布公共声音。

不存在对应 `/openapi/v1/management` 路由。开发者不能创建公共资源或把私有资源提升为公共资源；管理员不能编辑开发者私有角色。

制作任务不接受 `webhookEndpointId`。页面以任务查询结果显示进度、成功或失败，不向开发者服务器发送通知。

### 2.4 用量与额度

开发者只通过后台登录查询：

| 路径 | 作用 |
|---|---|
| `GET /api/v1/developer/usage/limits` | 查询限额和余额。 |
| `GET /api/v1/developer/usage/summary` | 查询日期范围汇总。 |
| `GET /api/v1/developer/usage/reservations` | 查询预占。 |
| `GET /api/v1/developer/call-records` | 查询脱敏逐次调用事实。 |

管理员继续使用受权限保护的后台治理接口替换账号限额、授予额度、重建日汇总和核对 `REVIEW_REQUIRED` 预占。管理员不进入开发者 Application 或 Session 页面代替开发者操作。

Application Secret 和浏览器 Token 均不能读取账号级用量或修改额度。

## 3. 凭证契约

### 3.1 Application Secret

- 前缀为 `lna_`；
- 每个 Application 同时只允许一个有效 Secret；
- 明文只在创建或重置响应出现一次；
- 数据库只保存使用部署 pepper 计算的摘要；
- 浏览器、WSS 事件、Prompt、Tool 请求和日志不得包含 Secret；
- 重置后旧 Secret 的新请求立即失败；已有短期 Session Token 按自己的到期和撤销规则处理；
- 停用 Application 后不得创建 Session、签发 Token 或调用 Skill。
- 只有所属开发者能创建或重置 Application Secret；管理员不能创建、查看、重置或代用开发者 Secret。

### 3.2 浏览器短期 Token

Token 绑定 account、application、session、externalUserId、授权 epoch 和到期时间。允许 Scope 由平台固定计算：

- `session:read`；
- `avatar:read`；
- `speak:write`；
- `asr:write`；
- `context:capture`；
- `guidance:receive`。

不存在 `chat:write`。浏览器不能请求超出固定集合的 Scope。

## 4. BUSINESS Session API

以下接口使用 Application Secret，不属于管理 API：

| 方法与路径 | 作用 |
|---|---|
| `POST /openapi/v1/sessions` | 创建 Session。 |
| `GET /openapi/v1/sessions/{sessionId}` | 查询所属 Session 摘要。 |
| `POST /openapi/v1/sessions/{sessionId}/tokens` | 签发浏览器短期 Token。 |
| `POST /openapi/v1/sessions/{sessionId}/stop` | 停止当前运行操作。 |
| `POST /openapi/v1/sessions/{sessionId}/revocations` | 撤销当前或同应用同用户 Session。 |
| `DELETE /openapi/v1/sessions/{sessionId}` | 结束 Session。 |
| `POST /openapi/v1/sessions/{sessionId}/skills/{skillId}/invoke` | 调用本 Session 已绑定的 HTTP Tool Skill。 |

### 4.1 创建 Session

请求：

```json
{
  "externalUserId": "user-1"
}
```

响应：

```json
{
  "sessionId": "4001",
  "applicationId": "102089642576183523",
  "externalUserId": "user-1",
  "status": "ACTIVE",
  "expiresAt": "2026-09-29T14:30:00Z",
  "developerConfig": {
    "revision": "7",
    "systemPrompt": "可选系统提示词",
    "skills": [
      {
        "skillId": "3001",
        "skillType": "PROMPT",
        "name": "商品说明",
        "instructions": "回答时优先使用已确认的商品信息"
      }
    ]
  }
}
```

`developerConfig` 只返回给 Application Secret 调用方，不进入浏览器 Token、角色包或 WSS 状态。HTTP Tool Skill 只返回安全名称、说明、输入/输出 Schema，不返回 Tool Token 或内部解析地址。

创建时一致读取 Application 和 Skill 当前配置，生成 Session 快照并建立 Avatar、Voice、Skill 引用。后台随后编辑 Application 或 Skill，不改变该 Session；新 Session 使用最新 revision。

内部快照至少保存：Application ID/revision、解析后的 Avatar Version、Voice Version、System Prompt、Skill 安全配置、Tool 加密秘密引用、运行限制和计费引用。快照只服务当前 Session 的运行一致性与审计，不是用户可管理的 Application/Skill 历史版本；对外响应不得出现旧 `configId`、`configVersionId` 或 `skillVersionId`。

### 4.2 Tool 调用

请求使用 Application Secret 和 `Idempotency-Key`：

```json
{
  "externalUserId": "user-1",
  "arguments": {
    "itemId": "demo-1"
  }
}
```

平台核对 Application、Session、业务用户、Session 快照中的 Skill 绑定、Skill 当前状态、输入 Schema 和次数上限。平台注入经认证的 `externalUserId/applicationId/sessionId`；参数不能覆盖这些身份。响应检查字节上限和输出 Schema 后按 `frontendFields` 裁剪。

## 5. LLM 与聊天边界

平台不调用开发者 LLM，不保存模型 ID、厂商 Key、模型参数或聊天正文。开发者后端在自己的配置中保存：

```yaml
ai:
  provider: dashscope
  model: qwen-plus
  api-key: ${DASHSCOPE_API_KEY}
```

调用流程：

1. 创建 Session 并取得 `developerConfig`；
2. 将 System Prompt、Prompt Skills、开发者自己的聊天历史和用户输入提交给开发者自己的 LLM；
3. 模型提出 Tool Call 时调用 Session Skill 接口；
4. 把 Tool 结果继续提交给模型；
5. 把最终文本交给 LN 播报。

SDK 和 WSS 不再提供平台编排模型的 `chat(text)` 或 `chat.create`。旧入口下线后返回稳定的能力已移除错误，不能静默转为播报。

开发者 LLM 用量由开发者自己的系统统计。LN 平台不接收、不计量，也不在账号额度中推算模型 token 或费用。

## 6. ASR、TTS 与实时协议

### 6.1 ASR

`POST /api/v1/runtime/asr` 使用浏览器 Session Token 和 `Idempotency-Key`，请求为 `multipart/form-data`，包含完整 `audio` 和可选 `language`。

- 支持 `audio/webm|audio/mp4|audio/ogg|audio/wav`；
- 文件上限由账号限额和平台固定上限共同约束；
- 使用平台管理员配置的默认官方 ASR；
- Application 不选择 ASR 服务或模型；
- 结果只返回当前请求，不长期保存录音或转写正文；
- 调用前写 operation 和额度事实，不确定结果进入核对状态。

### 6.2 独立播报

WSS `speech.create {text}` 使用 `speak:write`。平台按 Session 快照中的官方 Voice 合成音频，按序发送：

- `request.ack`；
- `turn.started`；
- `audio.segment`；
- `playback.ack`；
- `turn.completed` 或 `turn.failed`。

每段提交官方服务前预占 `TTS_CHAR`，成功结算，提交前取消释放，结果不确定保留 `REVIEW_REQUIRED`。临时音频按到期和 Session 结束进入清理。

### 6.3 连接与停止

- WSS 使用一次性 ticket 完成首帧认证；
- 同一 Session 只允许一个当前连接代；
- 旧连接、旧操作和迟到音频不能污染新连接；
- stop 立即终止本地播放并请求服务端停止；
- 已提交厂商且无法证明未计费的请求不能自动退额。

## 7. Element、Page、Hybrid

页面采集由浏览器 SDK 显式调用，结果交给开发者应用，不由平台自动发送给 LLM。

| 模式 | 含义 |
|---|---|
| Element | 采集开发者指定元素的受限文本、结构和可选区域图。 |
| Page | 采集当前页面的受限可见信息或受限页面截图。 |
| Hybrid | 同时提供结构化页面信息和截图，供开发者自己的多模态模型使用。 |

共同约束：

- 排除密码、隐藏输入、拒绝选择器和跨源受限内容；
- 限制单次大小、图片尺寸、频率和滚动；
- 原始 DOM、截图和采集文本不写平台数据库或普通日志；
- SDK 只返回当前调用结果，是否提交模型由开发者应用决定；
- Application 不保存图片能力开关或 Context JSON。

## 8. 数据、状态与错误

- 对外 ID 使用十进制字符串。
- 写接口使用 1～64 位可见 ASCII `Idempotency-Key`；同键异参返回 409。
- 当前配置更新使用十进制 `If-Match` revision；缺失返回 428，过期返回 412。
- 跨账号、跨 Application 或跨业务用户资源按不存在处理。
- 缺失或错误凭证返回 401；有效凭证权限不足返回 403。
- 错误只返回稳定 `code/message/retryable/requestId`，不透出厂商正文、SQL、内部 URL、Token 或堆栈。
- Session 状态为 `CREATING → ACTIVE → DELETING → DELETED`。
- 平台不保存聊天正文、ASR 转写、完整 DOM、截图、Tool 原始参数/结果或模型 Key。
- Session、operation、调用事实、日汇总、额度流水和 Avatar 制作任务长期保留；临时录音、采集数据和音频按生命周期清理。

## 9. 下线与迁移契约

| 旧能力 | 处理 |
|---|---|
| `/openapi/v1/management/**` | 全部下线，不接受旧 Management Key。 |
| Management Key | 删除页面、Scope、Filter 和可用状态；仅保留必要脱敏审计。 |
| Relay | 删除菜单、API、版本、授权、Token、探测和运行解析。 |
| Application config versions | 迁移当前配置后删除发布、历史、回滚和版本选择。 |
| Skill versions | 迁移当前 Skill 后删除版本发布和 Application 的 version 绑定。 |
| Webhook | 删除 Endpoint、Secret、delivery、attempt、Worker、Sender 和 Avatar 任务关联。 |
| 平台 LLM chat | 删除 Relay 编排和 `chat.create`；开发者后端自行调用模型。 |

落地前必须查询各目标环境的 `flyway_schema_history`。已执行脚本只能通过新的 Flyway 增量迁移纠正；尚未执行且属于同一未交付变更的脚本，只有在确认所有环境均未执行后才能调整。迁移前结束依赖旧 Relay 或配置版本的活动测试 Session；迁移后验证旧路由和旧凭证均不能继续工作。

角色与资源归属迁移还必须：

- 删除管理员角色关联的 Application、私有制作、私有 Skills、开发者用量、Management Key、Relay 和 Webhook 菜单；
- 为管理员保留公共角色、公共声音、公共 Skills 和平台治理菜单；
- 阻止超级管理员通配权限绕过开发者领域身份检查；
- 将管理员账号下既有开发者测试资源迁移到独立开发者账号或明确废弃，禁止直接改为公共资源；
- 保证开发者只能读取公共资源，不能执行公共资源写操作。

## 10. 项目索引对应的实现契约

项目索引确认当前实现存在以下强耦合。实施时必须整条替换，不能只隐藏菜单或删除单个 Controller：

| 索引定位 | 当前行为 | 契约要求 |
|---|---|---|
| Gateway `AuthFilter`、System `ManagementKeyFilter`、Management Controllers | 识别 `/openapi/v1/management/**` 并解析 Management Key | 删除路由分支、Filter、Controller、Scope、Nacos/Gateway 配置及测试；旧路径返回 404 |
| `ApplicationServiceImpl.publish` 与 `ApplicationMapper` | 校验 Relay/Skill Version，写 `p_app_config`、版本号、引用和当前指针 | 改为 revision 保护的当前配置原子更新；不再创建可发布配置版本 |
| `SkillServiceImpl` 与 `SkillMapper` | 创建和解析 `p_skill_version`，Application 绑定 Skill Version | 改为 Skill 当前配置；Application 只绑定 Skill ID；Session 创建时复制运行快照 |
| `BusinessSessionService.create`、`BusinessSystemClient`、Session Store/Token/WSS | 通过 System Snapshot 取得并固定 `configId`，运行时继续解析 config-version | 内部改用 `sessionSnapshotId`；对外只返回 `developerConfig.revision`，不暴露配置版本 ID |
| `AsrRuntimeService` 与 `BusinessSystemClient.resolveAsrRelay` | 解析 ASR Relay 目标再调用 | 解析平台默认官方 ASR 服务；删除 Relay Binding/Token 数据结构和内部接口 |
| `ChatRuntimeService`、WSS chat 事件、SDK `chat` | 平台调用 Relay LLM 并编排 Tool/播报 | 删除平台聊天运行时和客户端入口；Demo 后端自行调用 LLM 后使用 `speech.create` |
| Webhook Service/Worker/Sender、Outbox、Avatar DTO | 保存 Endpoint/Secret，生成投递并重试 | 删除 Webhook 资源和任务关联；Avatar 页面轮询任务状态 |
| Vue access-key/relay/webhook 页面与菜单迁移 | 向开发者和管理员暴露旧能力 | 删除页面、API、路由和菜单授权；管理员/开发者使用独立菜单树 |
| V17/V19/V20/V21/V22 等迁移历史文件 | 建立 Management Key、Relay、Skill Version、Webhook、额度权限和混合角色授权 | 先核对各环境 `flyway_schema_history`；已执行脚本不修改，用新迁移纠正数据、约束和菜单 |
| Demo Relay 辅助代码及旧文档 | 验收平台聊天/Relay 链路 | 更新保留的 Demo 为开发者 LLM + LN 运行服务链路，并同步 README/配置样例 |

### 10.1 内部接口切换

- System 向 Session 提供“创建运行快照”和“读取 Session 快照所需资源”的内部接口，不再提供 `chatConfig`、`resolveAsrRelay` 或 config-version 解析。
- Session Store、Token、WSS、ASR、TTS、Context、Tool 和媒体链路统一以 `sessionSnapshotId` 关联同一份内部快照。
- `sessionSnapshotId` 只允许内部使用；业务 OpenAPI、浏览器 Token 声明和 SDK 公共类型不得暴露。
- 切换期间不做新旧双写或自动回退。数据库迁移、System、Session、Gateway、Vue、SDK 和 Demo 作为同一交付一起切换。

## 11. 统一验收契约

本节是唯一验收入口。第 1～10 节对应的所有代码、SQL、前端、SDK、Demo 和文档完成之前，不执行阶段业务验收，不产生“部分验收通过”结论。实现期间的编译、类型检查和聚焦测试只属于开发门禁。

### 11.1 静态、构建、迁移与启动

- 前后端不再存在可访问的 Management Key、Relay、Webhook 或 Application 历史版本入口；
- Flyway 成功迁移 Application/Skill 当前配置并清理旧引用；
- Session 内部快照替代运行态 `configId/configVersionId/skillVersionId`，公共 API 和 SDK 不暴露快照 ID；
- Application Secret 和 Session Token 类型隔离有效；
- 管理员和开发者的菜单、路由、accountId 和资源归属完全隔离；
- 管理员只能维护公共角色、公共声音、公共 Skills 和平台治理，不能操作开发者 Application、私有资源、Secret 或 Session；
- 开发者不能创建、修改或发布公共资源；
- 后端聚焦测试与构建、前端类型检查与构建、SDK 构建、Demo 构建通过；
- Gateway、System、Session、Vue 和 Demo 使用迁移后的数据库完成启动和健康检查。

### 11.2 角色、权限与负向场景

- 管理员只看到公共角色、公共声音、公共 Skills 和平台治理菜单；开发者只看到虚拟人制作、Skills、Application、用量与额度；
- 管理员调用开发者 Application/私有资源/Secret/Session 接口返回 403；开发者调用公共资源写入或平台治理接口返回 403；
- 跨账号、跨 Application、跨 externalUserId、未绑定 Skill、停用资源和过期 revision 均按本契约拒绝；
- 旧 Management、Relay、Webhook、config-version、skill-version 和平台 chat 路由返回 404，旧客户端方法不存在。

### 11.3 真实 Demo 端到端

- Demo 后端从自己的配置文件读取模型 ID 和模型 Key；
- Demo 使用独立开发者账号，管理员账号不参与 Application 和 Session 运行；
- 创建 Session 后取得 System Prompt 和 Skills；
- 开发者后端完成真实模型调用和可选 Tool 调用；
- 浏览器完成默认 ASR、官方 TTS、角色动作、停止和媒体清理；
- Application 保存后，新 Session 使用最新角色、声音、Prompt 和 Skills；
- 已连接 Session 不发生配置漂移；
- 旧 Management、Relay、Webhook 和 config-version 路由不可用；
- Element、Page、Hybrid 都能通过 SDK 显式调用，敏感字段排除且结果不进入平台长期存储；
- ASR、TTS、Tool、Avatar 用量、额度、预占、结算、异常核对和临时文件清理一致；
- 真实付费测试遵守用户当次授权上限，证据不包含秘密。

### 11.4 判定规则

统一验收形成一份结果和一组按证据类型区分的记录。静态检查、迁移、启动、接口、浏览器流程和真实付费调用不能互相替代。任一必验项失败，整体结果为“未通过”；修复后重新执行统一验收清单。只有全部通过，执行计划和本契约才同时标记“完成”。
