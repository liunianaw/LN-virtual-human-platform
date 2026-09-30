# 开发者接入简化执行计划书

> 起草日期：2026-09-29  
> 角色边界修订：2026-09-30  
> 项目索引复核：2026-09-30  
> 交付规则：全部代码、数据库迁移、前端、SDK、Demo 和文档改动完成后，只执行一次统一验收；实施包之间不做阶段验收。  
> 配套契约：[开发者接入简化接口契约](2026-09-29-developer-integration-simplified-contract.md)  
> 关系说明：本计划经用户确认后，取代《2026-09-23 开发者接入平台功能模块执行计划书》中与本计划冲突的 Management Key、开放管理 API、LLM/ASR Relay、Application 配置版本和 Webhook 边界。旧计划保留为历史实施资料。

## 1. 背景与问题

旧方案同时把 LN 设计成虚拟人运行平台、资源管理 OpenAPI 和 LLM 编排平台，导致普通开发者需要理解并配置 Management Key、Relay、模型 ID、模型能力、Application 配置版本和 Webhook。旧实现还把超级管理员当成默认开发者，使管理员菜单混入 Application、私有角色、私有 Skills 等开发者能力。

用户重新确认的产品边界是：开发者只能登录 LN 平台制作和配置资源；开发者代码只使用已配置好的 Application 运行虚拟人。平台不向开发者提供虚拟人制作、Application 配置或其他控制面资源的开放管理 API。

因此本计划以“后台完成配置、代码只负责运行”为原则，删除无必要的控制面开放能力，简化 Application，并将 LLM 选择和聊天历史交还开发者后端。

## 2. 最终产品边界

### 2.1 角色与菜单

开发者后台只保留四个菜单：

1. 虚拟人制作；
2. Skills 管理；
3. Application 创建和配置；
4. 用量和额度查看。

删除开发者菜单中的：

- 管理 Key；
- LLM/ASR Relay；
- Webhook。

管理员和开发者是不同主体，不因管理员权限更高而自动共享开发者身份、账号数据或菜单。管理员的开发者接入相关菜单只保留：

1. 公共角色；
2. 公共声音；
3. 公共 Skills。

管理员另外保留用户、角色、菜单、平台配置、服务配置、额度治理和审计等平台管理能力，但不进入开发者工作台，不创建、发布或调试开发者 Application，不管理开发者私有角色、私有 Skills、Application Secret 或业务 Session。

超级管理员的通配权限不能绕过领域身份边界。管理员访问开发者账号接口时必须返回 403；开发者访问公共资源制作、发布或平台治理接口时同样返回 403。真实 Demo 和验收必须使用独立开发者账号，不能复用管理员账号冒充开发者。

### 2.2 Application 配置

Application 只配置：

- 应用名称；
- 可选说明；
- 虚拟人角色形象；
- 官方声音；
- System Prompt；
- Skills；
- Application Secret。

Application 不再配置：

- CHAT/SPEAK_ONLY 模式；
- LLM Relay 或 ASR Relay；
- 模型 ID、temperature、最大输出 token；
- 图片或 Tool 能力开关；
- Context JSON 和运行上限；
- 配置发布、配置版本、历史版本和回滚。

保存 Application 后，新创建的 Session 立即使用最新配置。已经创建的 Session 保持创建时快照，避免连接期间角色、声音和 Skills 发生漂移；结束并重建 Session 后即使用最新配置。

### 2.3 开发者后端职责

开发者后端在自己的配置文件或秘密设施中保存：

- LLM 厂商；
- 模型 ID；
- 模型 Key；
- 模型参数；
- 多轮聊天正文和记忆。

平台不保存或代管这些配置。开发者后端创建 Session 后取得有效 System Prompt 和 Skills，调用自己的 LLM，再把最终文本交给 LN 平台播报。

### 2.4 平台运行职责

平台负责：

- Session、短期浏览器授权和 WSS；
- 虚拟人角色包、动作和状态；
- 默认官方 ASR；
- 官方 Voice/TTS、媒体和播放控制；
- Prompt Skill 和 HTTP Tool Skill 的当前配置；
- HTTP Tool 的受控调用、Schema、身份注入、秘密隔离和结果裁剪；
- Element、Page、Hybrid 页面采集 SDK；
- 用量、额度、预占、结算和管理员核对。

### 2.5 公共资源与私有资源

- 管理员制作并发布公共角色、公共声音和公共 Skills，供所有符合条件的开发者选择。
- 开发者制作自己的私有角色和私有 Skills，只能由所属开发者查看和使用。
- 开发者不能创建公共资源，也不能把私有资源提升为公共资源。
- 管理员负责公共资源生命周期和平台治理，不代替开发者创建或编辑私有资源。
- Application 只能由开发者创建和配置，可选择可用公共资源及本账号私有资源。

## 3. 明确删除的能力

### 3.1 Management Key 与管理 OpenAPI

- 删除 Management Key 的创建、轮换、停用、删除、Scope 和菜单。
- 删除全部 `/openapi/v1/management/**` 路由。
- 删除开发者后端通过 API 创建或修改 Avatar、Skill、Application、Voice、额度等资源的能力。
- 保留 Application Secret；它只访问所属 Application 的 Session 和受控运行接口。
- 保留平台管理员后台的额度和平台治理接口。

### 3.2 Relay

- 删除 LLM/ASR Relay 资源、版本、授权、Token、连接测试和菜单。
- 删除 Application、Session 和运行时对 Relay 的引用。
- LLM 完全由开发者后端调用。
- ASR 使用平台管理员配置的默认官方服务，开发者不选择 ASR 服务。
- 官方 TTS 继续由平台提供，不受本次删除影响。

### 3.3 Webhook

- 删除 Webhook Endpoint、签名 Secret、事件、投递、重试和菜单。
- 删除 Avatar 制作表单中的 Webhook 选择。
- Avatar 制作进度和终态只在平台页面查看；页面轮询真实任务状态。
- 删除 Avatar 任务的 `webhookEndpointId` 和相关开放接口。

### 3.4 Application 与 Skill 历史版本

- Application 改为一行当前配置，普通保存立即替换当前值。
- 删除配置发布、历史版本、版本详情和回滚。
- Application 绑定 Skill ID，不绑定 Skill Version ID。
- Skill 使用当前有效配置；更新后由新 Session 使用。
- 保留 revision、更新时间和审计事实用于并发控制与问题核对，这些字段不形成用户可管理的历史版本。

## 4. 目标运行流程

### 4.1 配置流程

1. 开发者登录平台制作并发布虚拟人。
2. 开发者按需创建 Prompt Skill 或 HTTP Tool Skill。
3. 开发者创建 Application。
4. 开发者选择虚拟人、声音和 Skills，填写 System Prompt 后保存。
5. 开发者创建或重置 Application Secret，完整值只显示一次。
6. 开发者在自己的后端配置 Application Secret 和 LLM 配置。

管理员的公共资源流程独立：管理员制作并发布公共角色、公共声音或公共 Skill；发布后进入开发者可选目录。管理员不进入 Application 创建、配置、Secret 或调试流程。

### 4.2 业务流程

1. 业务用户在开发者系统登录。
2. 开发者后端使用 Application Secret 和稳定 `externalUserId` 创建 BUSINESS Session。
3. 平台返回 Session 摘要、有效 System Prompt 和 Skill 描述；开发者后端再为该 Session 签发浏览器短期 Token。
4. 浏览器使用短期 Token 加载角色并建立 WSS，可直接使用 ASR、页面采集、播报和动作能力。
5. 开发者后端维护聊天历史并调用自己的 LLM。
6. 模型需要 Tool 时，开发者后端调用当前 Session 已绑定 Skill 的平台受控运行接口。
7. 开发者后端把最终回复文本交给平台播报。
8. 业务结束后关闭 Session；平台清理临时录音、页面采集结果和音频。

## 5. 项目索引复核结论

本计划已按 2026-09-30 项目索引复核。当前实现不是几个菜单页面的局部调整，而是贯穿 Gateway、`ruoyi-system`、`ruoyi-session`、Vue、SDK、Demo 和 Flyway 的跨模块改造：

| 当前实现链路 | 索引确认的关键依赖 | 本计划处理 |
|---|---|---|
| Application 发布 | `ApplicationServiceImpl.publish` 同时写入配置版本、Relay/Skill Version 引用和当前指针 | 改为原子更新一份当前配置，移除发布、历史、回滚及 Relay/Skill Version 绑定 |
| Session 创建 | `BusinessSessionService.create` 接收并固定 `configId`，后续 Token、WSS、运行服务继续使用 | 改为创建时生成内部 Session 快照；对外不再暴露 Application 配置版本 |
| ASR | `AsrRuntimeService` 通过 System 内部接口解析 ASR Relay 后调用目标 | 改为解析平台默认官方 ASR 服务，删除 Relay 解析链路 |
| Skill | `SkillServiceImpl` 以 Skill Version 解析 Prompt、Tool Schema、Secret 和限制 | 改为单一当前配置；Session 创建时复制安全运行快照，秘密只保留加密引用 |
| Management Key | Gateway `AuthFilter`、`ManagementKeyFilter`、管理 Controller 和 Scope 共同支撑 `/openapi/v1/management/**` | 删除整条鉴权、路由、Controller、Service 和前端链路，只保留 Application Secret |
| Webhook | Avatar DTO/任务、Webhook Service、Worker、Sender、Outbox 和前端页面相互引用 | 删除外部投递链路；制作页面通过任务查询查看状态 |
| 前端与菜单 | access-key、relay、webhook 页面/API 及多份 `sys_menu/sys_role_menu` 迁移仍在授权 | 删除页面/API/菜单并通过新增迁移纠正既有角色授权 |
| Demo 与 SDK | Demo 仍有 Relay 验收辅助代码，SDK/运行时仍有平台聊天编排入口 | Demo 改为开发者后端直连 LLM；SDK 删除 `chat`，保留 Session、ASR、播报、动作和采集 |

索引覆盖检查发现 V17、V20 的部分 SQL 解析不完整，已直接核对源文件：V17 创建了 Management Key 菜单和 `MANAGEMENT` 审计主体，V20 创建了版本化 Skill 菜单说明及管理员/开发者混合授权。因此迁移任务必须以 SQL 源文件为准，不能只依赖图索引删除 Java 与 Vue 代码。

## 6. 模块实施任务

### 6.1 实施与验收规则

- DEV-S00～DEV-S09 是同一次交付中的实现包，不是可独立验收的阶段。
- 实施按依赖顺序推进：身份边界 → 凭证与旧入口下线 → 当前配置与数据迁移 → Session 快照 → 运行时与 SDK → 前端与 Demo → 用量清理。
- 每个实现包允许执行编译、类型检查、聚焦单测和静态检查，用于尽早发现回归；这些结果只算开发门禁，不形成验收结论。
- 在全部实现包、增量迁移、Demo 和文档完成之前，整体状态始终为“实施中”，不得记录“某阶段已验收”或据此跳过最终场景。
- 只有第 8 节的统一验收全部通过，整项改造才标记完成。

### 6.2 实施依赖顺序

| 顺序 | 实现包 | 依赖原因 |
|---|---|---|
| 1 | DEV-S00 | 先建立管理员/开发者领域身份，后续资源归属和路由才能正确收口 |
| 2 | DEV-S01、DEV-S02 | 先下线 Management Key、管理 OpenAPI、Webhook 输入，阻止旧入口继续产生数据 |
| 3 | DEV-S04、DEV-S05 | 建立 Skill/Application 当前配置及 revision 模型 |
| 4 | DEV-S06 | 在当前配置之上建立内部 Session 快照，替换运行态 `configId` 依赖 |
| 5 | DEV-S03、DEV-S07 | 删除 Relay/平台聊天并接入默认官方 ASR，调整 System 与 Session 内部契约 |
| 6 | DEV-S08 | 在新 Token/Session 边界上收口 Element、Page、Hybrid SDK |
| 7 | DEV-S09 | 清理旧用量、Webhook 记录和管理查询边界 |
| 8 | 前端、SDK、Demo、文档收尾 | 删除旧页面和客户端入口，更新真实 Demo 与使用说明 |

顺序表示依赖关系，不表示分批发布或分批验收；同一实现包涉及的后端、前端、SQL 和文档必须一起完成。

### DEV-S00：管理员与开发者角色隔离

- 清理管理员角色中的开发者菜单授权，只保留公共角色、公共声音、公共 Skills 及平台治理菜单。
- 开发者角色保留私有虚拟人制作、Skills、Application、用量与额度菜单。
- 后台路由、Controller 和领域 Service 同时验证角色类型与账号身份，不能只依赖超级管理员通配权限。
- 管理员不能创建、发布或调试开发者 Application，不能创建 Application Secret，不能读取或修改开发者私有资源。
- 开发者不能创建或发布公共角色、公共声音和公共 Skills，也不能调用管理员额度与治理接口。
- 将原先管理员兼开发者的测试资源迁移到独立开发者账号；管理员测试数据只保留公共资源和平台治理事实。

实现完成定义（不作为阶段验收）：管理员和开发者菜单、Controller 与 Service 的身份边界均已改造；双方越权接口具备返回 403 的实现；Demo 已切换为独立开发者账号配置。

### DEV-S01：运行凭证收口

- 保留 Application Secret 的创建、一次展示、重置、停用和删除。
- 保留浏览器短期 Session Token、撤销和授权 epoch。
- 删除 Management Key 页面、类型、Scope、Filter 和管理 OpenAPI。
- 收口 Gateway 白名单和路由，确保 Application Secret 只能进入 Session 运行接口。

实现完成定义（不作为阶段验收）：Management Key、管理 OpenAPI 及相关鉴权代码已删除；Application Secret 与浏览器 Token 的边界已完成改造。

### DEV-S02：资产入口收口

- 删除 Avatar、生成任务和 Voice 的开发者管理 OpenAPI。
- 保留登录后台中的虚拟人制作全流程。
- 开发者虚拟人制作只产生本账号私有角色；管理员公共角色入口只产生公共角色。
- 公共角色的制作、发布、下架和停用由管理员专用菜单及接口处理，不复用开发者账号身份。
- 删除 Avatar 表单和任务 DTO 中的 Webhook 字段。
- 任务状态由平台页面查询，不产生外部通知。

实现完成定义（不作为阶段验收）：资源写入只保留登录后台及管理员公共资源入口，Avatar DTO、表单和任务不再包含 Webhook 字段。

### DEV-S03：删除 Relay 并调整 AI 边界

- 删除 Relay 前后端、数据库引用、秘密、授权和运行解析。
- 删除平台 LLM 编排入口和 SDK `chat(text)`。
- Session 创建结果向可信开发者后端返回 System Prompt 和安全 Skill 描述。
- Demo 后端从本地配置读取模型 ID 和模型 Key，并自行调用 LLM。
- ASR 切换为平台默认官方服务。

实现完成定义（不作为阶段验收）：Application、System、Session、SDK 和 Demo 均已移除 Relay/模型字段及平台聊天编排，Demo 后端已实现“开发者 LLM → LN 播报”代码链路。

### DEV-S04：Skills 当前配置

- Skill 改为单一当前配置并使用 revision 防并发覆盖。
- 开发者创建私有 Skill；管理员通过独立公共 Skills 入口创建和发布公共 Skill。
- 管理员不能编辑开发者私有 Skill，开发者不能改变公共 Skill。
- Application 绑定 Skill ID。
- Prompt Skill 在 Session 创建结果中返回有效指令。
- HTTP Tool Skill 向开发者后端返回安全描述，并提供基于 Session 的受控调用接口。
- 未绑定、停用、跨账号、Schema 错误和次数超限调用必须拒绝。

实现完成定义（不作为阶段验收）：Skill 当前配置、revision、Session Skill 快照和秘密隔离代码均已完成。

### DEV-S05：Application 当前配置

- 页面只显示名称、说明、虚拟人、声音、System Prompt、Skills 和 Secret。
- 删除模式、Relay、模型参数、能力开关、Context JSON、发布和历史版本 UI/API。
- 使用普通更新接口原子保存当前配置，并验证 revision、资源归属和状态。
- 新 Session 使用最新配置，现有 Session 保持快照。
- Application 只属于开发者账号；管理员没有创建、配置、发布、Secret 或调试入口。

实现完成定义（不作为阶段验收）：Application 当前配置读写、revision、资源校验和 Session 快照衔接均已完成，配置版本入口已删除。

### DEV-S06：Session 与 SDK 适配

- Session 创建时读取 Application/Skill 当前配置并形成运行快照。
- 创建结果只向 Application Secret 调用方返回 System Prompt 和 Skill 描述。
- 浏览器 Token 固定授予角色读取、ASR、播报、页面采集和指导所需 Scope。
- SDK 删除平台聊天编排，保留角色、WSS、ASR、播报、动作、停止、媒体和采集。

实现完成定义（不作为阶段验收）：Session 创建、内部快照、Token Scope、SDK 和 WSS 数据边界均已完成改造。

### DEV-S07：默认 ASR 与官方 TTS

- ASR 不再解析 Relay，使用平台默认官方服务。
- 保留录音格式/大小限制、幂等、临时清理和调用事实。
- 保留官方 Voice、TTS 字符额度、分段媒体、播放回执、停止和迟到隔离。
- `asr:write` 对有效 Session 默认可用。

实现完成定义（不作为阶段验收）：默认官方 ASR、官方 TTS、额度事实和临时媒体清理代码已完成衔接。

### DEV-S08：Element、Page、Hybrid

- 采集作为 SDK 显式调用，不再依赖 Application 的 Context 或图片能力开关。
- Element 采集指定元素；Page 采集受限页面信息；Hybrid 组合结构化信息与截图。
- 默认排除密码、隐藏字段和拒绝选择器，限制大小、频率和滚动。
- 结果交给开发者应用，平台不自动提交 LLM 或长期保存。

实现完成定义（不作为阶段验收）：三种采集模式、敏感内容排除、大小/频率限制和临时数据处理代码已完成。

### DEV-S09：用量与额度

- 开发者通过后台查看用量、调用、限额、余额和预占。
- 管理员保留限额替换、额度授予、日汇总重建和 `REVIEW_REQUIRED` 核对。
- 管理员只做账号级治理和核对，不进入开发者 Application 或业务 Session 页面代替开发者操作。
- 删除 Management Key 用量接口。
- 删除 Webhook 服务、表、Worker、Sender 和投递记录。
- 保留 ASR、TTS、Tool、Avatar 的脱敏用量事实；平台不接收或计量开发者 LLM 用量。

实现完成定义（不作为阶段验收）：目标能力的用量/额度链路已完成，旧 Webhook 与 Management 查询代码和入口已删除。

## 7. 数据迁移与切换原则

- 落地前查询各目标环境的 `flyway_schema_history`。已经执行的 Flyway 文件不得修改，统一使用下一可用版本的增量迁移；尚未执行且仍属于同一未交付变更的脚本，只有在确认所有环境均未执行后才能调整。
- `p_access_key` 只保留 Application Secret；删除 MANAGEMENT 类型的可用入口、Scope、Filter 和 `MANAGEMENT` 审计主体写入。
- 将每个 Application 当前指针指向的数据迁入单一当前配置；Application 对外只保留 `revision`，不保留发布版本、历史和回滚。
- 将 Application 的 Skill Version 绑定迁为 Skill ID；将每个 Skill 当前版本迁入单一当前配置并保留 revision。
- 新增内部 Session 快照结构，保存 Application revision、解析后的 Avatar/Voice Version、Skill 安全配置、Tool 秘密引用和计费所需事实。快照 ID 不是 Application 历史版本，不提供查询、回滚或选择接口。
- 切换前停止并清理依赖旧 `configId`、Relay 或 Skill Version 的活动测试 Session；生产迁移不得让旧 Session 静默改用新配置。
- 删除 Webhook 任务字段和表前记录迁移数量，停止 Worker/Sender 后再处理遗留投递；不保留用户入口。
- Relay Secret、Webhook Secret 和 Management Key 明文/密文不得迁入新结构；按审计要求只保留不具备认证能力的最小事实。
- 修正角色菜单关系：管理员移除开发者 Application、私有制作、私有 Skills、开发者用量工作台、Management Key、Relay 和 Webhook 菜单；开发者不获得公共资源发布和平台治理权限。
- 现有管理员账号下的开发者私有资源不得自动当成公共资源；迁移前逐项归属到独立开发者账号或明确废弃。
- 增量迁移必须包含迁移前计数、迁移后计数、孤儿引用检查和失败回滚说明；V17、V19、V20、V21、V22 等仓库既有脚本是否可调整，以目标环境 `flyway_schema_history` 的实际记录为准。

## 8. 统一验收

### 8.1 启动条件

只有以下事项全部完成，才启动统一验收：

- DEV-S00～DEV-S09 的代码和新增 Flyway 迁移均已提交到待验收工作区；
- Vue、SDK、System、Session、Gateway、Demo 和文档已按新契约同步；
- 旧 Management、Relay、Webhook、Application/Skill Version 和平台聊天入口已从目标构建中删除；
- 独立开发者账号、管理员账号、公共/私有测试资源、Application Secret 和真实服务配置已准备完成；
- 付费测试已取得当次明确授权和费用上限。

任何单个实现包完成都不能提前启动业务验收，也不能登记为“阶段验收通过”。

### 8.2 单次验收清单

统一验收按下列顺序一次执行并形成一份证据记录：

1. 文档与契约一致性、旧术语和旧路由扫描。
2. 后端聚焦测试与构建、前端类型检查与构建、SDK 构建、Demo 构建。
3. 在目标数据库执行全部新增 Flyway 迁移，核对迁移前后数量、孤儿引用和角色菜单关系。
4. 启动 Gateway、System、Session、Vue 和 Demo，检查健康状态及内部接口契约。
5. 验证管理员/开发者菜单和领域身份隔离，验证公共资源写入与开发者只读选择。
6. 验证四个开发者菜单、Application 当前配置、Secret、Skill、用量与额度页面。
7. 验证 Application Secret、Session Token、跨账号、跨角色、未绑定 Skill、停用资源等负向场景。
8. 使用独立开发者账号和真实 Demo 完成 Session、开发者自有 LLM、Prompt、Tool、默认 ASR、官方 TTS、角色、动作、停止、Element/Page/Hybrid 链路。
9. 更新 Application/Skill 后验证新 Session 使用最新配置、已有 Session 保持内部快照。
10. 验证旧 Management、Relay、Webhook、config-version、skill-version 和平台 `chat` 路由/客户端入口不可用。
11. 核对 ASR、TTS、Tool、Avatar 用量、额度、预占、结算、异常核对和临时文件清理。

静态检查、迁移、服务启动、接口联通、浏览器流程和真实付费终点分别记录证据，不能互相代替。任一必验项失败，统一验收整体记为“未通过”；修复后重新执行本节清单，不保留“部分已验收”状态。

### 8.3 完成判定

只有统一验收全部通过，才同时更新计划状态、契约状态和项目交付记录为“完成”。若真实付费终点因未授权或外部服务不可用未执行，状态记为“待验收”，不能用模拟、HTTP 200 或历史验收替代。

## 9. 当前状态

| 日期 | 状态 | 说明 |
|---|---|---|
| 2026-09-29 | 计划草案 | 已按用户确认的新菜单、Application 字段、凭证边界和无历史版本规则形成独立计划，等待用户确认后进入代码迁移。 |
| 2026-09-29 | 旧实现待收口 | 当前代码仍包含 Management Key、Relay、Webhook、Application/Skill 版本和平台 LLM 编排；这些能力不再是目标产品能力。 |
| 2026-09-30 | 角色边界修订 | 管理员与开发者彻底分离；管理员只维护公共角色、公共声音、公共 Skills 和平台治理，不拥有开发者 Application、私有资源、Secret 或业务 Session 能力。 |
| 2026-09-30 | 索引复核与验收规则修订 | 已按项目索引补齐 Gateway、System、Session、Vue、SDK、Demo、Flyway 的跨模块影响；取消阶段验收，改为全部实现完成后一次统一验收。 |
