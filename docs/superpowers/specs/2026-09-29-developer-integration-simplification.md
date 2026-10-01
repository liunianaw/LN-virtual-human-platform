# 开发者接入简化执行计划书

> 起草日期：2026-09-29  
> 角色边界修订：2026-09-30  
> 项目索引复核：2026-10-01（Windows 路径新鲜度检测受限，关键结论已直接核对源码）
> 执行状态：实施、目标迁移与服务切换完成，核心 Demo 与后台体验已由用户确认；显式采集按用户决定留作后续视情况优化，技术收尾核对仍以第 9 节为准。本次获准提交并推送 v1.0，不宣称原统一清单全部通过。
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

超级管理员的通配权限不能绕过领域身份边界。管理员访问专属开发者账号接口时必须返回 403；开发者访问公共资源发布或平台治理接口时同样返回 403。角色制作是同一功能，双方共用 `/asset/**` 和 `/avatar` 页面，发布范围由服务端身份固定，不因共享路径而共享账号数据。真实 Demo 和验收必须使用独立开发者账号，不能复用管理员账号冒充开发者。

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

角色制作使用共用功能和路径，管理员完成后发布为公共角色，开发者完成后发布为本人私有角色。公共声音与公共 Skill 仍由管理员独立入口维护；发布后进入开发者可选目录。管理员不进入 Application 创建、配置、Secret 或调试流程。

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
- 开发者虚拟人制作只产生本账号私有角色；管理员公共角色制作使用同一路径并只产生公共角色。
- 角色制作共用 `/asset/**` 路径及同一制作流程，服务端按管理员/开发者身份固定发布范围；公共角色的下架、停用和删除仍由管理员治理入口处理。
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
- 修正角色菜单关系：管理员移除开发者 Application、专属私有资产库、私有 Skills、开发者用量工作台、Management Key、Relay 和 Webhook 菜单；开发者不获得公共资源发布和平台治理权限。
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
   用户 2026-10-01 最新决定：主 Demo 的 Element/Page/Hybrid 显式采集不作为本次 v1.0 的阻塞项，留作后续视情况优化；保留本地夹具结果，不据此补记主页面采集通过。
9. 更新 Application/Skill 后验证新 Session 使用最新配置、已有 Session 保持内部快照。
10. 验证旧 Management、Relay、Webhook、config-version、skill-version 和平台 `chat` 路由/客户端入口不可用。
11. 核对 ASR、TTS、Tool、Avatar 用量、额度、预占、结算、异常核对和临时文件清理。

静态检查、迁移、服务启动、接口联通、浏览器流程和真实付费终点分别记录证据，不能互相代替。任一必验项失败，统一验收整体记为“未通过”；修复后重新执行本节清单，不保留“部分已验收”状态。

### 8.3 完成判定

只有统一验收全部通过，才同时更新计划状态、契约状态和项目交付记录为“完成”。若真实付费终点因未授权或外部服务不可用未执行，状态记为“待验收”，不能用模拟、HTTP 200 或历史验收替代。

## 9. 执行状态与验收记录

当前整体状态：**目标迁移与服务切换完成，初版 37 项脚本检查通过；用户已确认商品查询、Prompt、多轮记忆、ASR、TTS、角色动作、停止/结束会话及后台权限体验成功，显式采集按最新决定留作后续视情况优化，获准提交并推送 v1.0**。DEV-S00～DEV-S09 已落实到当前工作区；目标 V23/V7 已成功执行。新增 CORS 检查的完整重跑、本次用量/额度/预占与临时音频清理仍需技术收尾核对，不将本次提交等同原统一清单全部通过。

### 9.1 实施与开发门禁（2026-10-01）

- 已删除 Management Key/管理 OpenAPI、Relay、Webhook、Application/Skill 版本入口、DEBUG 和平台聊天编排；Application/Skill 使用当前配置与 revision，Session 固定内部运行快照。
- 管理员与开发者领域身份、资源归属、菜单分流、默认官方 ASR、官方 Voice/TTS、Session HTTP Tool、脱敏用量及临时数据清理已同步。官方声音候选试听复用播报内核，不创建开发者 DEBUG Session。
- 按用户最新决定，角色制作共用页面、接口及 Service；身份固定发布范围，客户端不能选择或提升可见范围。公共资源治理与开发者私有资产库保持权限边界。
- SDK 删除 `chat` 和平台自动 Context 上传，保留显式 Element/Page/Hybrid 本地采集；Demo 复用保留测试项目，由开发者后端管理 LLM Key 与聊天历史，并隔离停止/关闭后的迟到回复。
- System、Session、Gateway 在只含当前源码的临时目录全新打包通过，9 个聚焦测试类共 16 项通过；JAR 检查未发现已删除的 Management/Relay/Webhook/CHAT/DEBUG/Context 编排类。临时构建用于排除原工作区旧 class 残留，不代表运行服务已更新。
- Vue 类型检查与生产构建、SDK 构建及采集隐私/频率聚焦检查、Demo 3 项离线检查、Application 当前配置静态检查均通过。未调用真实模型或生成服务。
- 全量代码图已刷新；图工具无法证明 Windows 根路径的新鲜度，SQL 存在部分解析覆盖，实际实现边界以直接读取的源码/XML/SQL 和构建结果为证，不把索引状态当作业务验证。

### 9.2 切换前数据库核对与迁移准备（历史记录）

- 只读核对本机 `platform_db`、`session_db` 的 `flyway_schema_history`：分别已执行到 V22、V6；本次新增 V23/V7 未执行，没有修改既有已执行迁移。
- 新增迁移包含切换前后计数、孤儿引用查询、旧认证材料退役、旧 Session/Token/Ticket 终止、临时媒体待清理、角色菜单修正及备份回滚说明。
- 按本机真实旧表 DDL 创建两个无业务数据的临时数据库演练：V23 执行 60 条、V7 执行 21 条 SQL 通过；临时库已删除。本演练证明 DDL 可执行，不证明目标存量数据迁移与业务验收通过。Session Prompt 字段使用 `mediumtext`，覆盖 Unicode 长文本。
- 本机管理员仍有 1 个启用的开发者 Application、2 个未停用私有 Skills，无启用私有角色。V23 在迁移开始即拒绝未明确处置这些资源的切换，不自动转为公共资源。
- 本次尚未修改目标库、重启服务、发布 Nacos 配置或重新制作角色。

### 9.3 统一验收准备与当次授权

1. 2026-10-01 用户确认管理员旧的 1 个启用 Application、2 个私有 Skills 不保留。执行前核对准确 ID 与关联范围，删除业务资源及可用认证材料；保留不可用于认证的审计/调用事实。先按迁移规范备份以支持切换回滚，备份不作为可用测试资源。
2. 独立开发者验收账号、可复用公共/私有资源及新 Application Secret 就绪后，按相邻 Examples-Demo 说明书准备运行配置；秘密不写源码、文档或 Git。
3. 2026-10-01 用户授权本次真实 LLM、ASR、TTS 测试总费用不超过人民币 5 元；使用有限短请求，不启动 Avatar 生成。历史费用授权不复用，当前调用与估算单独记录。
4. 完成上述准备、备份与服务切换后，完整执行第 8 节统一清单；浏览器、真实调用、存量迁移、额度和清理分别记录证据，全部通过才标记完成。

### 9.4 验收工程统一迁移（2026-10-01）

用户决定：验收前刷新代码索引，所有验收使用独立 [Examples-Demo 工程](../../../../Examples-Demo/README.md)，原 examples 验收代码按逻辑迁移后删除，不再使用。此决定覆盖原保留 examples 的入口约定，但仍保留 Demo 工程、可复用正式资源和必要审计事实。

- PlatformClient 补齐关闭 Session 的 If-Match，增加 Session Skill 调用；DeveloperLlmClient/DemoSessionService 承接自有模型、Prompt、Tool、多轮记忆和有限调用预算。
- Vue Demo 同步当前 SDK，模型回复通过开发者后端取得后显式播报；停止/关闭隔离迟到回复，Element/Page/Hybrid 使用独立本地采集入口。
- ToolLookupController 承接真实 HTTPS Tool 夹具；反向隧道、Nginx include 和安装辅助代码迁至 Demo 的 acceptance 目录，隧道转至 Spring Boot 8040；不自动修改远端配置。
- 原 Flask 3 项边界检查迁为 Java 聚焦检查，Demo 后端共 4 项检查与 Vue 类型/生产构建通过。旧 Webhook/Relay/平台 chat 与自动采集能力从 Demo 移除。
- Demo acceptance/Verify-Static.ps1 已运行通过：4 项 Java 检查、Vue 构建、Demo 内置当前 SDK 构建及 1 项采集隐私/频率检查；隧道辅助 Python 编译检查通过。全新源码 Demo JAR 无旧 Webhook 类。
- 上述对应能力核对后，原 examples 中 9 个现存验收入口/代码/说明文件及 6 个旧生成字节码已删除；原协议已删除脚本不再复用，目录只剩预留 .gitkeep。历史已提交源码可由 Git 恢复，现行实现保留在独立 Demo。
- 迁移及删除完成后，平台与 Demo 全量代码图通过已安装的官方 CLI 重建成功，分别为 12,769 节点/44,334 边和 272 节点/689 边；桌面 MCP 连接已恢复并核对同一索引代次。Windows 新鲜度检测、SQL/Nginx 解析和 Demo 内置 vendor 排除仍受限，关键实现以源码和构建核对，不声称完全覆盖。
- 本节记录时只完成逻辑迁移和开发门禁；其后目标环境切换见 9.5，仍不能据此登记统一验收完成。

### 9.5 目标迁移与正式验收环境（2026-10-01）

用户已备份两个目标库的数据和结构，并授权实际迁移、启动及无付费脚本检查；复杂页面和真实厂商终点由用户完成。

- 执行前核对 `RuoYi-Cloud/sql/备份/platform_db.sql`（646,969 字节，60 张表）、`session_db.sql`（266,322 字节，15 张表）：均含结构、数据、Flyway 记录及外键恢复结尾；保存哈希并排除 Git。未做这两个完整转储的恢复演练，不把文件核对当作恢复成功。
- 在受保护的单事务内删除授权的管理员 App `102089642576183297`、私有 Skills `102097029416615936` / `102097029416615947` 及关联配置/绑定/可用 Secret，释放对应引用。用户 910105 的 App、公共资产及审计/调用事实保留；另一个已停用管理员 App 不在本次删除范围，未擅自删除。已删除资源只能通过用户的切换前数据库备份恢复，不再作为可用夹具。
- Flyway 实际执行成功：`platform_db` V23、`session_db` V7，`success=1`。没有修改已执行迁移。平台审计计数：App 2→2、Skill 3→3，迁移后当前指针缺失/孤儿 Skill 绑定/活跃 SESSION 引用均 0；旧 MANAGEMENT 可用项 0。Session 审计计数：旧 Session 63→63、快照 63、快照孤儿 0、旧活跃 Session 26→0、旧活跃 Grant 9→0、Ticket 0。旧 App Config、Skill Version、Relay、Webhook、消息正文表已退役。
- 从干净源码构建切换 System/Session/Auth/Gateway；Redis、MySQL、Nacos、RabbitMQ 复用本机服务，Vue 80、媒体 API 8002 和 Demo 8040/5173 启动。制作 Worker 不启动，不发起 Avatar 生成。Gateway Nacos 配置已发布并核对内容，统一 `/api/v1/admin/**` 路由覆盖官方及公共资源治理；废弃 DEBUG 路由移除。
- 修复启动配置：Session 允许完整 Demo Origin；System 从被忽略的本地配置运行时注入 COS，不依赖干净 JAR 携带秘密。系统代理假 IP 被安全校验拒绝后，用 Demo `acceptance/Prepare-JavaDns.ps1` 查询真实 DNS，为 System 单进程显式配置验收 hosts；不改 OS hosts、不降低 DNS/公网/TLS 校验。此文件需在网络或地址变化后重建并重启 System。
- 账号使用既有独立开发者 `user001`（910105），不重置密码、不新建替代账号。复用 App `102089642576183523`、官方已发布角色 `102089642576183351`、官方 Voice `102089642576183516`，Secret 仅保留于 Demo 忽略配置/DPAPI，登录令牌不写文档。
- Demo 新建 Prompt `102102542225244169`、HTTP Tool `102102542225244171`，当前 App 绑定二者。Tool 经现有 HTTPS `/dev08/tool/lookup` 转发至 Demo 8040，使用新 Tool Token 与平台注入身份；隧道只使用已有服务器 loopback 18030，不安装或覆盖远端配置。
- 已配置 ACTIVE 默认官方 ASR `102102542225244160`（北京 `qwen3-asr-flash`），内部解析可用；复用北京官方 TTS 服务与声音。给开发者授予 500 TTS 字符，准备后可用 695；历史预占 2 不被伪装为本次消费或擅自结算，后续按既有事实核对。
- Demo 运行 `qwen-flash`，每进程最多 6 次模型请求、输入每次最多 64 KiB、输出最多 256 token，不自动重试。脚本不调用模型/ASR/TTS；当次人工真实调用仍共享 ¥5 总上限，次数上限不是人民币计费硬闸，不允许通过重启反复刷新次数规避总预算。
- `acceptance/Verify-Live.ps1` 最终 37 项全部通过：健康、身份拒绝、固定授权、角色包、票据首帧认证、HTTPS Tool、重放/冲突、旧入口、If-Match、快照隔离、真实 Demo 代理及结束。Gateway 与 System 的退役/缺失路由已修为真实 404，受影响模块重新打包并切换；无付费脚本的健康和正反请求也已在新构建上复测。本机实时连接使用 loopback WS，不把首帧认证结果当作公网 WSS 部署验收。
- Tool 夹具在公网 TLS 链路上出现过 5 秒超时，超时改为 15 秒后完整脚本通过；保留 2 条 UNKNOWN 和 5 条 SUCCEEDED 脱敏 Tool 调用事实，不删除失败历史、不自动重试外部请求、不擅自核销历史预占。快照检查通过同幂等创建重放读取旧冻结配置（普通 GET 不返回 developerConfig），再比较新 Session 的最新配置。
- 收尾已恢复 App 当前 System Prompt，结束所有本轮临时 Session；查询未收尾 Session、活跃 Grant、活跃 SESSION 引用均为 0，票据均已消费/不再可用。Demo 模型尝试次数为 0，本轮没有 LLM、ASR、TTS 或角色生成请求。保留 App、Prompt、Tool、官方服务/资产、Demo 与隧道供用户手工验收。
- 验收前再次刷新平台与 Demo 全量代码索引：分别为 12,772 节点/44,349 边和 286 节点/717 边。秘密配置、转储和运行日志排除；PowerShell/SQL/Nginx 部分解析、Windows 新鲜度信号仍受限，相关文件已直接读取/运行核对，索引不替代业务证据。
- 用户剩余验收：后台页面和公共/私有资源权限体验；Demo 的 Prompt 标记、真实商品 Tool、多轮记忆、录音确认/默认 ASR、官方 TTS/角色动作/停止/替换、Element/Page/Hybrid 显式采集、额度/调用事实及临时音频清理。不要重做用户已确认的角色制作，不复用旧 examples 或旧协议。
- 用户浏览器连接报 `Could not fetch asset 102089642576183374`：SDK 共用 fetchAsset 保留 CORS、SHA-256 及尺寸校验；该 COS PNG 实际 GET 200（617,721 字节），但携带 Demo Origin 后没有 Access-Control-Allow-Origin，浏览器因此不能读取。初版脚本只核对角色包接口，未覆盖跨域资产下载；已补两个 Demo Origin 的资产 CORS 检查，当前检查会准确失败。
- 用户已授权仅追加 `http://127.0.0.1:5173`、`http://localhost:5173` 的 GET/HEAD CORS，保留原规则与私有桶权限；现有凭证 GET Bucket cors 返回 403/AccessDenied，当前可控浏览器没有已登录的 COS 控制台，尚未执行 PUT、不盲目覆盖规则。需要用户在 COS 控制台追加该规则，或让当前凭证具备该桶 GetBucketCORS/PutBucketCORS 权限后再合并。未重做角色、未绕过 SDK 校验或修改桶公开权限、未发起付费模型请求。
- 用户提供新增 CORS 截图后实测：原来源 `http://127.0.0.1` 的 OPTIONS 成功 200，新两个 5173 来源均为 403/AccessForbidden，GET 仍缺少跨域响应。说明新来源未匹配，不能据截图登记修复成功；需在编辑框核对来源分别填写、不夹逗号/顿号，并取消本次未要求的 POST。诊断创建的 Session 已结束，Demo 模型次数仍 0。

2026-10-01 用户问题复核（续）：

- 保留用户确认的角色动作成功；直接检查基础图及八个图集均为 200，匹配 127.0.0.1:5173 跨域头。内置浏览器重连仍有图集 fetch 失败，当前连接不据此判为通过，未重做角色或绕过校验。
- 录音失败根因是 Mapper 读取不存在的 max_recording_bytes，按现有单文件限额 max_file_bytes 修复，无新 DDL、未改已执行迁移、未扩大账号额度。内部错误/缺失限额不再解释为 0。System/Session 新 JAR 已启动，实际 recording-limit 返回 1,900,000；真实 ASR 仍待用户确认。
- 实际 Element 原结果 FAILED/FAILED（指定区域不在视口）；Demo 改为仅指定区域 FULL_PAGE。修复截图克隆平滑滚动错位、隐私移除导致布局变化和父容器相交导致视口外文字混入。Demo 的纯本地采集夹具使用相同 SDK，三模式浏览器复核通过：Element/Hybrid 图文成功，Page 图片成功、DOM=NOT_REQUESTED；密码/私有区排除，Hybrid 仅可见公开文字。主 Demo 因重连失败尚未复验三个新结果，不能混用夹具与主页面证据。
- 旧 DEMO_MODEL_FAILED 未保留原因，当前不能确认模型根因；新增安全 HTTP/超时/TLS/IO 分类，不透出厂商正文或密钥。本地模拟 401 与有限次数检查通过。切换前已用 3 次，切换后最多剩余 3 次，总费用 ¥5 不变；本轮代理未提交模型 completion、ASR/TTS 或生成。无认证 Java HEAD HTTP400 仅为一次网络可达；只读模型列表请求超时，不能当作模型/Key 验收。下一次用户短消息错误码用于定位。
- 聚焦回归：Demo 后端 5 项、平台录音 2 项、SDK 与 Demo 隐私/频率检查、前端类型检查/构建通过。验收前平台/Demo 全量索引刷新为 12,775 节点/44,382 边和 299 节点/753 边；Windows 新鲜度缺失文件已直接读取，部分解析不替代测试。

2026-10-01 后续用户确认：录音识别成功，保留为本次真实 ASR 已确认结果；发送消息返回 DEMO_MODEL_HTTP_401。Demo 启动器从受忽略 validation/config.local.json 注入模型 Key，文件早于本次启动且不存在多余空白/模板；地址为北京 dashscope，非套餐专属 Key。复用 Demo 的 ModelConnectivityProbe，通过同一 Java 运行时和同一 Key 调用只读模型列表接口，结果 HTTP401 / InvalidApiKey；未提交 completion、不读取或记录厂商正文/Key。当前可确认该 Key 与北京地址组合不被接受，不能判断其已删除或地域错误。官方 ASR 与开发者模型是不同凭证来源，未擅自复制平台官方凭证或修改秘密配置。新进程模型尝试 1 次，累计已用 4/6，剩余 2 次，总费用 ¥5 不变；等待用户提供有效开发者 Key 的本地配置或明确授权复用其现有有效百炼凭证。未重启或扩大次数。

2026-10-01 用户随后提供独立新 Key 本地文件，授权用于 Demo：完整 Key 通过同一 Java 运行时的只读模型列表鉴权（HTTP200），支持点号，不截断或假定旧十六进制格式；不提交推理。复用现有 DPAPI helper 保存为 Demo 忽略运行目录中的专用模型凭证，启动器优先读取它，缺失时才回退旧 validation 配置；原 Key 文件、平台 validation 与官方 ASR 配置均未修改。DPAPI 往返及启动器 PowerShell 语法检查通过，Demo 索引刷新为 299 节点/759 边，部分解析与 Windows 新鲜度限制仍以源码补核。重启前计数仍为 1，因此新 Demo 后端仅开放剩余 2 次请求（累计已用 4/6，总费用 ¥5 不变）；健康 UP/configured/modelEnabled，模型次数为 0，前端 HTTP200。只读鉴权和启动检查不替代模型回复、Tool、多轮记忆的真实验收，用户可重新连接后发送短消息确认。

2026-10-01 用户后续发送返回 DEMO_MODEL_CALL_LIMIT：只读健康检查 modelCalls=2，与该进程上限 2 相符，累计尝试计数为 6/6，剩余 0。源码核对：次数在提交模型请求前计入，失败也占尝试；一次聊天的 Tool 往返可能使用多个模型请求，所以不能将次数等同聊天轮数或人民币花费。触发上限的请求在发送至厂商前被拒绝，仅凭计数不能判断此前两次成功、是否执行 Tool 或实际消费金额。代理未新增付费调用、未重启清零或调整次数，真实模型回复/Tool/记忆验收仍未确认；继续模型验收需用户确认追加尝试，¥5 总费用边界不变。

2026-10-01 用户明确授权“再开放5次”：保留此前累计 6 次尝试事实，在 ¥5 总费用不变的前提下，只重启 Demo 后端并通过现有 Start-Demo.ps1 -ModelMaxCalls 5 开放新增 5 次（累计允许 11 次，已用 6、剩余 5）；不修改官方 ASR、平台服务或凭证，不主动调用模型/ASR/TTS。启动后健康 UP/configured/modelEnabled、modelCalls=0，前端 HTTP200。次数是有限尝试保护，不是人民币计费硬闸；真实金额仍需厂商用量核对。Demo 内存会话因重启清空，用户需刷新页面并重新连接 Session 后继续，模型终点未据此登记通过。

2026-10-01 用户真实终点确认：发送“查询 demo-1 商品库存”，模型回复库存为 7，声音播放正常；值与 Demo ToolLookupController 的 demo-1 夹具 stock=7 相符。登记用户侧开发者模型商品查询回复与官方 TTS 播放体验通过，保留此前录音识别和角色动作成功，不重复付费验收。此处以用户结果及夹具核对为证，不把数值匹配单独当作本条 Tool 的服务端审计记录；HTTPS Tool 授权/执行链路的已有脚本证据仍单独保留。只读健康 modelCalls=2，因此新增 5 次中已用 2、剩余 3，累计尝试 8/11；¥5 总费用边界不变，实际金额未据次数推断。多轮记忆、Prompt 标记及其他尚未确认的统一验收项目继续保留，不登记全部验收完成。

2026-10-01 用户进一步明确确认：多轮记忆与 Prompt 标记均成功，回复按验收 Prompt 以“Demo确认”开头。两项登记为本次 Demo 用户真实验收通过，与已确认的商品库存回复、官方 TTS、录音识别及角色动作结果一并保留；不要求重复验证，不新增付费调用、不重启服务或扩大次数。此前计数只是上次查询时的快照，本条确认不推断最新剩余次数或实际费用。其他未确认的统一验收项仍保留，未登记全部验收完成。

2026-10-01 用户交付决定：确认剩余手工检查第 1 项（停止、结束会话、迟到隔离及重连）和第 3 项（后台页面与管理员/开发者权限体验）完成成功；第 2 项主 Demo 显式采集留作“后续视情况待优化”，不是已验收通过。该最新决定覆盖本次显式采集必须完成后才能提交的门槛，安全/秘密边界不放宽。用户授权按“功能优化，LN-virtual‑human‑platform v1.0”提交并推送平台仓库；保留代码、迁移、既有测试证据与本次用户确认，不重做已通过终点、不增加付费调用。新增 CORS 检查的完整重跑和本次用量/额度/预占/临时音频清理未在本次提交前补造结论，继续作为技术收尾待核对。独立 Examples-Demo 位于平台 Git 根外且没有独立 Git 仓库，其源码和验收记录保留本地，不在本次平台提交中；密钥、DPAPI、备份、日志和构建产物不提交。

本次 v1.0 提交前检查：仅暂存计划覆盖的源码、迁移、配置模板、SDK、文档和已授权旧验收代码删除；Git 识别后为 208 个变更路径（含重命名）。已修正一个新增 Vue 页面的末尾多余空行，业务逻辑不变；暂存差异检查通过，131 个新增/修改文件的密钥模式与禁止产物路径检查无命中，新增行的字面凭证检查无命中。SDK 重新构建及现有显式采集隐私/频率回归 1 项通过；这些开发检查不替代用户延期的主页面采集验收。远端 main 在提交前与本地起点一致，按用户授权提交并普通推送，不强制覆盖远端历史。

### 9.6 计划修订记录

| 日期 | 状态 | 说明 |
|---|---|---|
| 2026-09-29 | 计划草案 | 已按用户确认的新菜单、Application 字段、凭证边界和无历史版本规则形成独立计划，等待用户确认后进入代码迁移。 |
| 2026-09-29 | 旧实现待收口 | 当前代码仍包含 Management Key、Relay、Webhook、Application/Skill 版本和平台 LLM 编排；这些能力不再是目标产品能力。 |
| 2026-09-30 | 角色边界修订 | 管理员与开发者彻底分离；管理员只维护公共角色、公共声音、公共 Skills 和平台治理，不拥有开发者 Application、私有资源、Secret 或业务 Session 能力。 |
| 2026-09-30 | 索引复核与验收规则修订 | 已按项目索引补齐 Gateway、System、Session、Vue、SDK、Demo、Flyway 的跨模块影响；取消阶段验收，改为全部实现完成后一次统一验收。 |
| 2026-10-01 | v1.0 交付范围确认 | 用户确认停止/结束会话和后台权限体验通过；主 Demo 显式采集改为后续视情况优化，授权按指定信息提交并推送，技术收尾待核对项如实保留。 |
