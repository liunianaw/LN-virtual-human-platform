# 管理员：公共角色制作与管理执行说明书

前置说明书：[项目整体说明书](../../../项目整体说明书.md) · [项目需求说明书](../../../项目需求说明书.md) · [数据库设计说明书](../../../数据库设计说明书.md) · [项目架构说明书](../../../项目架构说明书.md) · [接口设计说明书](../../../接口设计说明书01.md)

日期：2026-09-15。版本：1.0。状态：**用户已确认，计划阶段定稿；未进入本计划正式编码，不代表实现或验收通过。**

## 执行状态与验收记录

计划阶段：用户已确认，本文与私有计划、协作规则合并为一次计划阶段提交。正式编码、主代理正式逻辑验证、用户完整体验及修复复验均未执行。既有代码与未提交修复保留，不计作新计划完成。下一步按本计划进入编码阶段时直接由主代理实施；本次仅完成定稿提交，不启动编码或收费验证。正式编码完成提交一次，后续修复仅用户指定时提交。

阅读说明：第2节及标“现有”的接口描述是代码盘点；第4～7、10节是共享实施契约。标“新增/拟新增”只表示尚未实现，不表示业务规则待决策。第10节规定动作级行为，第4～6节的旧入口须按其兼容改造，不能保留整套失败即放弃所有动作的处理。

配套文件：[用户私有角色制作与管理](2026-09-15-user-private-avatar-flow.md)。两份计划共用同一制作引擎；共享契约唯一来源为本文件第4～7及10节，私有计划仅补充角色差异，不另建同义接口定义。

## 1. 目标、范围与角色

管理员上传有权使用的参考图，生成完整八动作，检查并发布为所有已登录用户可选用的公共角色；能够查看任务、管理版本和下架。这里的“公共”指平台用户可使用，不意味着 COS 桶公开、原始参考图公开或匿名下载。

- 制作者/发布者：具有公共资产管理权限的管理员。浏览器隐藏按钮不能代替后端校验。
- 使用者：普通用户，可选择公共已发布版本；不能修改、删除、重新发布公共原版。
- 共享引擎：平台任务编排、媒体 Worker、COS 文件管理、正式资源包和播放器。
- 不包含：声音制作、角色分享市场、多人审批流程、付费套餐、自动重发未知收费请求。
- 本计划不重新施加平台制作次数限制；定稿不触发生成、不自动恢复旧任务，付费验证仍遵守明确费用授权。

## 2. 当前代码依据与缺口

以下是静态阅读结果，不是本轮运行验收结论。路径均相对项目根目录。

| 现有落点 | 已观察到的行为 | 执行时需补齐或核验 |
|---|---|---|
| `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/asset/controller/AssetController.java` | 文件、服务列表、制作任务、预览、发布、删除入口存在 | 公共资产入口与读取授权需要单独定义，不能把管理员个人资产自动视为公共资产 |
| 同目录体系的 `service/AssetService.java` | 从登录身份取得账号；验证私有参考图；事务创建任务、八动作和 Outbox；保存模型服务快照 | 同一 requestId 改参数仍可能返回旧任务，需参数摘要冲突检查；现有创建接口每次新建 Avatar，尚非同角色新版本入口 |
| `service/AvatarPublicationService.java` | 按所有者预览/发布，检查八动作、文件、QA；删除检查引用和活动任务 | 公共可用范围、下架策略、跨账号公共引用；审核备注当前只校验未保存 |
| `ruoyi-media/src/ruoyi_media/worker/`、`providers/`、`media/` | 已有领取、调用、加工与回写链路 | 八动作汇总、状态完整性、下载恢复尚未整体验收；当前未提交修复不能当作已生效 |
| `validation/avatar_lab/provider.py`、`workflow.py` | 已验证工具先保存供应商回执，再下载；下载恢复不重新生成 | 逐段复用其行为，明确正式存储与任务协议的适配，不另写简化流程替代 |

## 3. 管理员完整执行流程

| 步骤 | 输入与动作 | 输出、落库与下一步 | 失败处理 |
|---|---|---|---|
| A1 选择制作服务 | 进入公共角色制作页，读取已启用服务，选择模型服务 | 持有 serviceId 和 revision；页面不拿密钥 | 无可用服务时明确提示，不能提交空编号 |
| A2 上传参考图 | 提交文件和权利确认 | 得到 sourceFileId；参考图仍为管理员私有文件 | 文件失败不创建生成任务；保存部分失败进入清理记录 |
| A3 提交制作 | 名称、sourceFileId、officialServiceId、requestId，目标为公共库 | 服务端鉴权并确定公共归属；原子生成 avatarId、avatarVersionId、taskId、八动作、Outbox、服务快照 | 相同请求复用任务；同键异参冲突；失败事务不留下半套业务记录 |
| A4 执行八动作 | Worker 从平台领取有租约的动作，使用任务固定服务快照 | 持久保存厂商回执 → 下载原图 → CPU 加工 → COS 上传 → 回写文件及动作 | 按第 6 节区分生成未知、下载失败、加工失败，禁止混为一类 |
| A5 动作预览与选用 | 完成一个动作就动态预览；八卡片独立显示进度 | 选择满意结果，不满意仅重做该动作；旧结果保持；程序QA和人工确认分别记录 | 下载/查询恢复不发新生成；一个动作失败不终止其他独立动作 |
| A6 汇总与整体验收 | 八个动作选用结果明确且满足第10节组装条件 | 登记动作、正式manifest、基准图/预览图、尺寸、锚点、QA；进入REVIEW后整套预览，再确认发布 | 汇总失败仅恢复汇总，不吞异常、不重跑八次生成 |
| A7 公共发布 | 指定确切 avatarId/versionId、确认和备注 | 发布该不可变版本，公共目录出现它；普通用户可预览并绑定此版本 | 版本不完整/权限不足拒绝；重复发布不重复扣费或生成 |
| A8 使用与维护 | 用户选择公共版本；管理员后续制作、下架或删除 | 应用固定 versionId；新制作不覆盖旧文件 | 下架、紧急停用和删除遵循下文已定需求规则 |

管理策略已由《项目需求说明书》FR-AV-06确定：普通下架禁止新绑定，已有绑定和Session继续使用；紧急停用立即阻止使用并提示更换，优先于历史配置。Application或可恢复Session仍引用时禁止永久删除；活动制作也须检查。先检查引用再逻辑删除和可靠清理，不能直接删COS。重新生成同一角色形成新版本，验收后采用；不能因为现有接口只会新建角色就改变需求。不要求用户恢复旧任务，重新制作仍是新任务。

## 4. 共享契约 C1：身份、字段与兼容规则

以下标为“现有”的路径是 system 服务内部控制器路径；浏览器网关前缀必须由现有前端 API 封装统一处理，不把内部路径直接假定为浏览器地址。`RuoYi-Cloud-Vue3/src/api/asset/avatar.ts` 是核对落点。

- 现有业务响应使用 `AjaxResult`：`code`、`msg`、`data`，成功业务码 200。调用端同时处理 HTTP 失败与业务码失败；不能因为 HTTP 200 就认为业务成功。
- 所有业务 ID 的目标线协议为十进制字符串，前端不得转 Number。Java 使用 Long、Python 使用 int 仅是进程内部表示；当前 Worker 数值序列化需与此目标逐项对齐，不能宣称已统一。
- 操作者账号来自已验证登录态，客户端不得指定 ownerAccountId 冒充他人。沿用数据库设计6.8～6.10：`p_avatar.account_id` 为私有拥有者或官方管理账号，不能为空；`visibility=OFFICIAL/PRIVATE` 区分官方与私有，不新增 ownershipScope 体系。版本与动作保留相同管理账号；版本 `created_by` 记录制作者，`accepted_by/accepted_at` 记录验收。
- `visibility=OFFICIAL/PRIVATE` 与版本 `status=BUILDING/REVIEW/PUBLISHED` 是两条独立维度：PRIVATE + PUBLISHED 仍然只对本人可用。主表 `p_avatar.status` 承载 PUBLISHED/UNLISTED/DISABLED 等当前可用状态，不再另造 availability 持久字段。
- 时间新增字段用带时区 ISO 8601；现有 createdAt 为 LocalDateTime，适配时明确转换，不能自行假定 UTC。
- 调用方不知道的字段不可默默用于决策；必填字段缺失立即报契约错误。变更必填项/字段含义须同步调用方与接收方，不能只改单边。
- URL 是短期读取凭证，不是资产标识；数据库和跨模块引用保存 fileId/versionId。过期后重新鉴权获取 URL，不能重新生成图片。

### 4.1 用户界面到平台接口

先看用途和调用时机，再看下表参数。下面既有接口仍需补齐业务链路，不等于已验收。

| 接口 | 做什么、什么时候调用 |
|---|---|
| GET `/asset/generation-services` | 进入制作页时获取可选择的模型服务，避免用户手填内部服务编号 |
| POST `/asset/files` | 用户选好参考图并确认使用权后上传，取得后续制作使用的文件ID；不触发生成 |
| GET `/asset/files/{fileId}` | 需要再次查看本人参考图或刷新过期读取地址时调用 |
| POST `/asset/generation-tasks` | 当前用于发起私有整套制作，立即返回异步任务，不等八动作完成；单动作制作调整见第9节 |
| POST `/asset/admin/public-generation-tasks`（拟新增） | 管理员从公共库入口提交制作，由后端确定官方归属；复用同一制作引擎 |
| GET `/asset/generation-tasks`、`/{taskId}` | 重开页面找回任务列表，或查询某次制作的进度和错误；查询不触发付费调用 |
| GET 版本 `/preview` | 产物可预览时加载八动作与播放参数，供人工检查，不表示已发布 |
| POST 版本 `/publish` | 人工确认后将指定版本变为可用；官方版本对用户开放，私有版本仍只属于本人 |
| DELETE `/asset/avatars/{avatarId}` | 用户明确删除角色时请求删除，先检查权限及应用/可恢复会话引用，不直接清空文件 |

| 接口与状态 | 请求字段及约束 | data 返回字段与约束 |
|---|---|---|
| GET `/asset/generation-services`，现有 | 无；有效登录和列表权限 | 数组：serviceId:string、name:string、providerCode:string、modelId:string、revision:integer；无密钥 |
| POST `/asset/files`，现有 | multipart：file 必填，真实 PNG/JPEG、≤10MB、宽高各≤4096；rightsConfirmed=true；rightsNoticeVersion 非空≤32字 | fileId:string、contentType:string、sizeBytes:integer、width/height:integer、readUrl:string；建议补 expiresAt |
| GET `/asset/files/{fileId}`，现有 | fileId 必填；所有者授权 | 同上；公共角色可用不授予读取私有参考图的权限 |
| POST `/asset/generation-tasks`，现有私有入口 | sourceFileId/officialServiceId:string 正整数；requestId 非空≤64、字符为字母数字及 `._:-`；name 去首尾空白后 1～100 字 | Task，见下文；普通用户不得传 visibility=OFFICIAL 改变归属 |
| POST `/asset/admin/public-generation-tasks`，**拟新增** | 同上；建议附 expectedServiceRevision:integer，防止选择后配置变更；不接受客户端所有者 | 同一 Task，服务端强制公共目标、保存 createdBy；复用任务应用服务，不复制引擎 |
| GET `/asset/generation-tasks`、`/{taskId}`，现有 | 当前账号任务；现有列表最近100条 | Task 或数组；管理员跨制作者公共任务查询另走受保护管理入口，不能删除现有账号条件凑实现 |
| GET `/asset/avatars/{avatarId}/versions/{versionId}/preview`，现有所有者入口 | 两个 ID 必须关联，版本 REVIEW/PUBLISHED | Preview，见下文；公共目录读取拟增加公共授权分支，仅准读已发布公共产物 |
| POST `/asset/avatars/{avatarId}/versions/{versionId}/publish`，现有 | visualAccepted=true；reviewNote 非空≤500字 | 当前返回 Preview；目标依资产归属分别确认私有可用或公共发布，公共写操作额外校验管理员权限 |
| DELETE `/asset/avatars/{avatarId}`，现有所有者入口 | 指定 ID，删除权限及归属校验 | 当前成功无 data；并不表示 COS 已同步删除，需可查询删除状态 |

Task 现有字段：taskId、avatarId、avatarVersionId、sourceFileId（均 string），requestId:string，status:string，internalState:string，progress:integer 0～100，errorCode:string|null，createdAt。建议扩展 stage、completedActionCount、failedAction、errorMessage、retryable、visibility；全部由服务端状态计算，不能由前端猜测。`retryable` 特指安全阶段重试，不授权付费重新生成。

Preview 现有字段：avatarId、versionId:string，status:string，frameWidth/frameHeight:integer，anchorX/anchorY:number，baseImageUrl/manifestUrl/previewUrl:string，actions:array。action 项：actionCode:string、frameCount:integer、fps:number、loopEnabled:boolean、frameLayout:string（现有 JSON 字符串）、atlasUrl:string、previewUrl:string|null。现有 frameLayout 字符串由单一适配层解析并校验，不让多个调用方各猜结构；建议后续契约版本返回结构化对象。建议增加 qaReport、visibility、expiresAt。

### 4.2 拟新增的管理与消费接口

以下路径是讨论提案，不能当成当前可调用接口：

| 接口 | 请求 → 返回 | 强制条件 |
|---|---|---|
| GET `/asset/public-avatars` | pageNum/pageSize → items[{avatarId,versionId,name,previewUrl,visibility}],total | 已登录，只列公共已发布且未下架版本，不返回源图、任务、厂商回执 |
| GET `/asset/admin/public-avatars` | pageNum/pageSize/status → 管理列表与任务/版本引用 | 公共资产管理权限；提供 creator 信息，不等于能访问所有用户私有内容 |
| POST `/asset/admin/avatars/{avatarId}/unpublish` | reason 非空 → avatarId,status=UNLISTED | 普通下架：仅管理员；禁止新绑定，已有绑定及Session继续使用 |
| POST `/asset/admin/avatars/{avatarId}/disable` | reason 非空 → avatarId,status=DISABLED | 紧急停用：仅管理员；立即阻止旧新引用使用并提示更换，运行时当前状态检查不能只读快照 |
| GET `/asset/avatars/{avatarId}` | ID → avatarId,name,visibility,status,currentVersionId,versions | 本人私有或允许读取的官方资产；删除状态可查询；源文件不随概览返回 |

### 4.3 示例：字段如何接起来（目标契约，非已执行记录）

```json
{
  "sourceFileId": "123456789012345678",
  "officialServiceId": "123456789012345679",
  "requestId": "public-avatar-example-01",
  "name": "公共示例角色",
  "expectedServiceRevision": 3
}
```

上传返回的 fileId 原样变成 sourceFileId；服务列表的 serviceId 原样变成 officialServiceId，revision 对应 expectedServiceRevision。创建返回的 avatarVersionId 在预览路径中叫 versionId，两者是同一值；taskId 仅用于任务查询，不能当 versionId。示例中的 revision 字段是拟新增，当前私有创建 DTO 不接收它。

## 5. 共享契约 C2：平台、Worker、文件、播放器的交叉部分

### 5.1 接口与责任映射

内部前缀为 `/asset/internal/generation`，仅媒体服务身份可调用，不向浏览器开放。当前使用 `X-LN-Internal-Token`；本文不包含真实凭证。

| 交接 | 当前接口/输入 → 输出 | 必须保持的关联和检查 |
|---|---|---|
| Outbox → Worker | POST `/outbox/claim`：workerId → id,eventType,eventId,traceId,accountId,payload | payload.taskId 必须回查所属账号；仅生成事件进入生成引擎，审核通知不能被误消费 |
| 平台 → 动作执行 | POST `/claim`：accountId,taskId,workerId → accountId,taskId,stepId,action,attemptNo,leaseEpoch,leaseSeconds,model,parametersJson,referenceUrl,outputPrefix | accountId/taskId/stepId 一致；action 固定八选一；parametersJson 当前为 JSON 文本，只在边界解析一次 |
| Worker → 调用账本 | POST `/attempts`：租约字段+requestHash → attemptId,providerRequestKey | 请求摘要固定；登记后才允许付费调用；新租约不能自动新建一次付费尝试 |
| Worker → 续租/进度 | POST `/progress`：租约字段+attemptId,state,providerRequestId,errorCode → 成功确认 | 租约字段=accountId,taskId,stepId,workerId,leaseEpoch；旧租约写回必须拒绝 |
| Worker → 文件登记 | POST `/results/succeeded`：租约字段+attemptId,objects,manifest → 成功确认 | object 项含 objectKey、sha256、sizeBytes、contentType；校验指定前缀、归属、实际对象与哈希；客户端不能任意登记别人对象 |
| Worker → 终态 | POST `/results/terminal`：租约字段+attemptId,state → 成功确认 | 错误详情在 progress 中现有分步记录；目标需原子保存原因和终态，避免只有 FAILED 无原因 |
| Worker → 预检释放 | POST `/claim-release`：租约字段+errorCode | 仅未提交付费请求时安全释放为待执行，不能用于已提交未知结果 |
| 平台 → 预览/应用 | avatarId,versionId → 已授权正式 manifest 与签名文件 | 使用者身份不等于公共资产所有者；按授权读取，不能把文件所有者改成使用者 |

上述返回目前混有 Map 数值 ID；C1 的字符串规范属于需要适配的目标。内部状态值必须对照现有枚举定稿，不能把页面中文阶段直接当状态值传回。

模型服务配置交叉：创建时平台固定 serviceId/revision/model/parameters/pipelineVersion；Worker使用非秘密快照，不逐动作读取新默认模型。秘密仅服务端取得，不进入前端、日志或公开manifest。服务DISABLED及凭证撤销优先于快照，禁止后续厂商调用，也不自动切厂商/Key。保留已提交事实及已获取文件；停用期间外部查询/下载恢复暂停，允许保全本地证据，但不借恢复绕过停用。恢复有效服务后在有效期内仅恢复原任务；到期仍不可取回时明确报错，不自动重新生成。

### 5.2 收费结果与恢复的拟补契约

当前接口尚缺完整“回执持久化确认—仅下载恢复”交接，不能把本说明当作已修好。建议在同一内部前缀补充：

- POST `/receipt`：租约字段、attemptId、providerRequestId、受保护 imageUrl、receivedAt、可得 usage → receiptStored=true。供应商成功回执必须先保存原始响应和解析结果，再开始下载；平台登记失败时保留本地回执，不再 POST 生成。
- POST `/defer`：租约字段、attemptId、stage、errorCode → retryAt。仅恢复下载/加工/上传/汇总，不创建新付费尝试；恢复领取需返回既有 attemptId、回执引用和 recoveryOnly=true。
- POST `/finalize`：accountId,taskId,workerId → readyForReview:boolean、stage、errorCode。平台自行回查八动作，不相信 Worker 声称完成；失败不能通过 Outbox 确认掩盖。
- 持久回执使用受保护存储，不只放易丢失系统临时目录；下载失败首次最多三次 GET，后续有限延迟恢复。重试总上限/过期处置作为可配置值定稿；禁止无限循环或 URL 过期后自动再次付费。

回执 imageUrl 不返回用户、不写明文运行日志；回执存在但原图下载失败，与供应商是否生成成功分别记录。

### 5.3 正式产物及跨流程复用

- 固定动作：idle、speaking、listening、thinking、nod、shake_head、wave、happy；每动作初始规格六帧、512×768、6fps。修改需统一契约，不能只有一端变。
- 每个动作回写六张帧图、atlas 和动作 manifest；正式汇总还必须有八动作记录、整套 manifest、基准图、预览图、几何和 QA。动作 manifest 不等于 SDK 整套 manifest。
- 正式包沿用 `接口设计说明书01.md` 与 `avatar-sdk/src/manifest.ts`，不再定义第三套格式。字段包括 packageType/schemaVersion、avatarId/versionId、width/height、anchor、preview/baseImage、actions；资产项通过 fileId、sha256、短期 url/expiresAt 关联。
- 预览 DTO 的归一化 anchorX/anchorY 与 SDK 像素锚点不能原样互传；由一个明确适配函数按画布尺寸转换并检查边界。帧布局同样由唯一适配层校验。
- 下载后的原图保留以便离线加工；引用真实产物，不用原参考图伪装成已生成基准图。程序 QA 不替代人工视觉验收。
- 管理员端、用户端、外部接入复用正式播放器/SDK和版本读取逻辑，不各写一套图集解释器。
- 整套 manifest 中短期 URL 到期后，通过授权入口刷新；不能只有初次创建时可播放。公共文件也保持 COS 私有。

## 6. 共享契约 C3：状态、幂等、异常

保留任务QUEUED/PROCESSING/SUCCEEDED/FAILED等现有状态域，但制作进度以版本下动作聚合为准。动作失败不提前终止其他动作；首轮全部尝试结束但有缺项时该批任务可FAILED，候选仍可继续动作级重做。新重做创建新的动作执行批次及attempt，不重开历史终态记录。版本BUILDING → REVIEW → PUBLISHED，只有八动作选用与汇总成功才REVIEW；单批任务成功不等于整套已公开。下架/紧急停用使用主表UNLISTED/DISABLED，独立于不可变版本内容。

| 情形 | 正确处理 | 禁止行为 |
|---|---|---|
| 同账号、同业务范围、同 requestId、同参数 | 返回原 taskId/版本；创建与 Outbox 同事务 | 重复发模型请求 |
| 同 requestId 不同参考图/服务/名称/目标 | 参数摘要不符，冲突返回 | 静默拿旧任务冒充新请求 |
| 用户明确重新制作 | 新 requestId、新任务、新候选；旧结果保留 | 要求用户先恢复旧任务才能创建 |
| 厂商超时且不知道是否生成 | 保存未知结果，停止自动收费重发，显示可解释错误 | 把网络超时认定“未收费”然后自动再发 |
| 厂商成功、下载失败 | 保留回执，在同一 attempt 内只重试 GET | 把生成成功事实清空或重新生成 |
| 下载完成、加工/上传失败 | 保留原图；确定的格式问题报错，临时上传问题有限恢复 | 删除原图迫使重新付费 |
| 单动作成功或结果重复回写 | 幂等登记实际文件与动作，更新真实进度 | 进度长期零，重复文件记录或虚增成功动作数 |
| 八动作成功、汇总失败 | 显示汇总阶段与原因，仅恢复汇总 | 吞异常或显示完成100% |
| Worker 重启/旧结果迟到 | 续租、epoch 与 attempt 关联校验；已提交未知不得重发 | 旧租约覆盖新状态 |
| 无权限、私有 ID 猜测 | 拒绝读取/绑定；统一避免泄露他人存在信息 | 仅靠列表过滤、前端按钮隐藏 |

建议业务错误语义：参数非法400、未登录401、禁止403、状态/幂等冲突409、厂商失败502、暂不可用503；适配现有异常处理时同时明确 HTTP 状态和 AjaxResult.code，不假定它们现在已一致。

## 7. 如何验证跨模块真的接通

由主代理本人完成代码逻辑验证，不交子代理代验；执行者自查不能代替此项。此稿只进行了接口盘点，下面均是待执行检查，不勾选“通过”。

1. 沿前端 API → Controller/DTO → Service → Mapper/事务 → Outbox → Python 解析 → 厂商回执 → 文件回写 → 汇总 → SDK，逐段核对字段、类型、归属与状态。
2. 特别核对 serviceId/officialServiceId、avatarVersionId/versionId 的映射；长 ID 无精度丢失；parametersJson 没有二次转义；文件 URL 不是永久标识。
3. 公共目标必须由管理员入口决定；共享 Service 接受可信业务上下文，不接受浏览器伪造所有者；读取公共版本不顺带开放源图。
4. 对一条真实新任务追踪同一组 taskId/stepId/attemptId；检查已生成后下载失败的分支不会走到新的付费 POST。
5. 两端按同一接口契约校验；使用最短受控联调证明字段实际被接收并落库。无付费故障注入覆盖复杂恢复缺陷；普通改动不自动新增测试/TDD。
6. 记录“检查位置、结论、缺陷与修复、未验证事项”即可，不重复大规模测试；未通过不得交作完成模块。

## 8. 管理员终点验收与执行顺序

- [ ] 主代理亲自完成第7节逻辑验证，阻断问题处理完。
- [ ] 管理员真实新建一套八动作，任务进度、结果和实际文件一致。
- [ ] 管理员逐一动态预览，程序 QA 和人工视觉结论分开；经用户确认后才公共发布。
- [ ] 普通用户 A、B 均可选用并预览该公共版本；均不能修改公共原版、读取源图或未发布候选。
- [ ] 平台内/接入模块收到固定 avatarId/versionId，刷新后仍能授权加载，不因旧签名 URL 到期永久失效。
- [ ] 普通下架仅禁止新绑定；紧急停用立即阻止旧新使用；Application或可恢复Session仍引用时禁止永久删除。

执行顺序：先定归属/可见性与共享契约 → 复用并补齐制作引擎 → 接公共管理入口与读取/引用授权 → 主代理代码逻辑验证 → 最短联调 → 用户终点验收。当前不派代理、不改代码。

## 9. 基线核对与剩余设计事项

2026-09-15根据用户要求重新核对，不再将以下已定规则列为待讨论：

| 事项 | 已有依据 | 本说明书采用的结论 |
|---|---|---|
| 官方资产归属 | 《数据库设计说明书》第3节规则4、6.8～6.10 | 非空管理账号+OFFICIAL；版本/动作账号保持关联；跨账号官方引用按可用性授权 |
| 普通下架/紧急停用 | 《项目需求说明书》FR-AV-06、FR-APP-02；数据库6.8、第8节引用约束 | UNLISTED只挡新绑定；DISABLED立即挡使用，优先于历史快照 |
| 删除 | FR-AV-06 | Application或可恢复Session引用均阻止永久删除，不能只查当前应用 |
| 重新生成 | FR-AV-06；数据库6.9 | 同Avatar新增version_no，验收后采用；旧Application不自动切换，已有/恢复Session固定原配置 |
| 服务停用 | 数据库6.4；需求FR-APP-02 | 检查当前服务/凭证有效性；非秘密快照不恢复已停用能力 |

上述依据文件位于项目根目录：[需求说明书](../../../项目需求说明书.md)、[数据库设计说明书](../../../数据库设计说明书.md)。这里核对的是设计基线，不宣称已核验本机数据库实际结构或全部代码符合设计。

单动作实施契约见第10节：整套八动作是发布单位，付费重做仅针对选定动作；下载恢复不是重新生成。本文新增接口按该契约实施，现有入口兼容接入同一编排。数据迁移的索引、类名等技术细节由主代理在编码时确定，不改变已确认业务边界。

## 10. 八动作可视制作、单动作重做与结果恢复

### 10.1 页面与操作流程

上传参考图后点击“开始制作”，创建候选版本并立即显示固定八张动作卡片。大屏并排网格，小屏换行；八张始终可见，不等全部结束才展示。界面并排不等于八次厂商调用同时执行；后台按服务允许的并发度调度，初始可串行，不为展示需求增加收费并发。

每张卡片显示：动作名称、当前阶段、阶段开始时间/已等待时长、当前可用结果、新一轮制作状态、安全错误说明，以及由服务端返回的可用操作。完成一个即可独立播放该动作六帧；八动作齐全是整套发布条件，不是单动作预览条件。

| 卡片状态（界面语义，非直接新增数据库枚举） | 展示 | 可执行操作 |
|---|---|---|
| 排队/生成中 | 当前阶段，尚无真实帧进度时不编造百分比 | 查看详情；禁用重复重做 |
| 已生成，下载中/加工中/保存中 | 供应商成功事实与本地阶段分开展示 | 系统有限恢复；不把此状态当成重新生成按钮 |
| 可预览、待确认 | 动态预览和QA提示 | 确认此动作；重做此动作 |
| 已确认 | 已选结果和确认标记 | 预览；重做此动作（保留旧结果） |
| 本地处理失败 | 明确下载/加工/上传哪一步失败 | 有可恢复输入时“重试下载/处理”；“重新生成”作为不同的收费操作 |
| 生成明确失败 | 安全原因，不把费用未知写成免费 | 重新生成此动作，形成新尝试 |
| 结果待核对 | 上次可能已生成/产生费用，不能确定结果 | 有可查询任务时“核对结果”；人工确认费用风险后才可另发新生成 |

首轮结果不会因为程序完成就算用户满意。用户逐动作确认，最终还有一次整套预览/发布确认；可在最终确认界面批量确认已看过的动作，但后端须记录明确采用的八个结果ID。重做只更新该卡片的“最新尝试”，不能清空已确认旧结果。

后台刷新建议：制作页可见且存在运行任务时每3秒查询一次聚合进度；请求不得重叠；连续失败退避到最多15秒，页面隐藏暂停，返回后立即刷新。无运行任务停止轮询；页面刷新从后端恢复，不靠浏览器内存保存采用结果。网络断开显示“状态暂未更新”，不能自行标记制作失败。

### 10.2 结果、尝试、版本的关系

- avatarId：同一个角色；versionId：本次可编辑候选版本；actionCode：固定八动作中的一个。
- attemptId：一次明确的新生成尝试；生成后的查询、下载、加工重试继续沿用它，不再创建收费尝试。
- resultId：一次尝试加工成功的不可变动作产物组；与attemptId不同，供应商已生成但加工未完成时可以还没有resultId。
- selectedResultId：候选版本该动作当前选用结果；latestAttemptId：最新尝试。两者可以指向不同轮次，这是保留旧结果的关键。
- 已发布版本不可修改。对已发布角色重做，先建立同角色的新候选版本并复用可用旧动作文件引用；仅选定动作重新生成。不能原地替换已发布manifest/图集。
- 重做沿用候选固定的参考图、模型、提示词/加工版本；新attempt记录种子等非秘密参数。若允许变更种子，明确记入新recipe；不承诺重做必定产生更满意图片，不私自切换模型。
- 参考图已独立删除时，已有成品仍可播放；新生成必须提示重新提供参考图，不能把旧atlas误用作源图。

数据库映射：沿用 `p_avatar/version/action`、`p_generation_step/attempt` 和 `p_file`。`p_avatar_action` 的唯一键 `(avatar_version_id,action_code)` 保存候选当前采用的正式动作，不用多插同动作行保存历史。拟新增动作结果记录承载 resultId、account_id、avatar_version_id、step_id、attempt_id、action_code、产物文件引用与QA；候选动作选择记录承载 selected_result_id、accepted_result_id、revision，均须版本/账号一致。具体DDL在实施时追加迁移，本次不改库。

动作结果及已采用文件不能随终态任务清理消失：永久产物元数据保留，尝试来源可独立留存或清理前解除强外键；不能给新结果表建立会阻止既有任务留存策略的盲目级联。复用文件需登记引用，只有没有候选、发布版本、应用和可恢复会话相关引用的文件才能清理。

### 10.3 新增动作级接口与字段

路径均为拟议system内部控制器路径，沿用登录态、AjaxResult及字符串ID规范。管理员官方候选和本人私有候选复用这些接口，以可信归属与操作权限区分。客户端不提交accountId、官方可见性或厂商密钥。

| 接口 | 用途和调用时机 | 请求 | data 返回 |
|---|---|---|---|
| GET `/asset/avatars/{avatarId}/versions/{versionId}/production` | 制作页一次取得八卡片与汇总状态 | 路径ID；无收费副作用 | ProgressSnapshot，结构见下文 |
| POST `/asset/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/generations` | 用户明确点击该动作“重新生成” | requestId、expectedActionRevision、acknowledgeUncertainCharge:boolean（默认false）、supersedesAttemptId（仅未知结果另发时必填） | taskId、stepId、attemptId、actionCode、actionRevision、stage=QUEUED；旧selectedResultId保持 |
| GET `/asset/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/results/{resultId}/preview` | 一动作完成就预览；无需整套REVIEW | 路径四项必须关联 | resultId、actionCode、frameCount、fps、loopEnabled、frameLayout、atlasUrl、expiresAt、qaReport |
| POST 同动作 `/selection` | 用户确认采用某一成功结果 | requestId、resultId、expectedActionRevision、visualAccepted=true | selectedResultId、acceptedResultId、actionRevision；不生成、不收费 |
| POST 同动作 `/attempts/{attemptId}/recovery` | “核对结果”或“重试下载/处理” | requestId、expectedActionRevision | attemptId、stage、recoveryScheduled:boolean、nextRetryAt或明确不可恢复原因；禁止触发生成POST |
| POST `/asset/avatars/{avatarId}/versions/{versionId}/assemble` | 八动作确认后组装正式包 | requestId、expectedCandidateRevision、selectedResults八项[{actionCode,resultId}] | assemblyId、stage=ASSEMBLING；完成后版本REVIEW，再按原发布接口确认 |

taskId不由浏览器随意串接，平台按版本/动作关联执行批次。初次“开始制作”提交八动作，立即返回候选ID并展示卡片。新增 POST `/asset/avatars/{avatarId}/versions`：必填requestId、expectedAvatarRevision；可选baseVersionId、sourceFileId、officialServiceId、expectedServiceRevision；返回avatarId、versionId、candidateRevision、taskId（无自动生成时null）。有baseVersionId时必须属于同角色且可授权复用，默认继承recipe和八动作引用，不自动付费；用户再点指定动作重做。无baseVersionId时必填源图和服务，启动完整首轮制作。改变参考图或服务则不复用旧动作，需明确创建完整新候选。官方角色只允许管理员创建版本，普通用户不能通过该入口复制修改官方角色。

ProgressSnapshot 字段：avatarId、versionId、candidateRevision、versionStatus、assemblyStage、completedActionCount、acceptedActionCount、totalActionCount=8、canAssemble、actions。每个action含 actionCode、actionRevision、stage、latestAttemptId、selectedResultId、acceptedResultId、resultIds、errorCode、safeMessage、nextRetryAt、allowedOperations。ID和可空ID遵循共享C1；stage是展示映射，不能直接扩写数据库枚举。completed表示可用产物数量，accepted表示人工采用数量，两者不可混为“八次请求成功”。

用户单动作重做后新结果可单独预览；只有selection成功才替换采用关系。失败/未知/不采用时旧selectedResultId与确认记录保持。活动生成/恢复、待选新结果或未知尝试阻止assemble。新增 POST 同动作 `/attempts/{attemptId}/discard`，用途为“保留旧结果并结束本轮选用”：请求requestId、expectedActionRevision、retainResultId（必须是已确认可用结果），返回attemptId、selectionClosed=true、selectedResultId、actionRevision。此操作关闭该尝试的候选选用资格，阻止未发出的生成及自动恢复；已提交请求不能保证取消，费用未知仍如实保留。迟到结果可保存历史，但不得加入已关闭选用或覆盖发布包；后台有活动写入时通过epoch隔离结果落点，不能修改已冻结候选。

组装时锁定candidateRevision和八项selectedResults，生成不可变清单；REVIEW期间新重做/选用会使其退回BUILDING并失效旧汇总，发布必须校验同一revision且无活动未关闭尝试。PUBLISHED不允许退回修改，只能创建新版本。外部/公共消费者永远只能读取已发布版本；候选单动作预览只给合法管理者/本人。

新增字段统一约束：requestId沿用1～64安全字符；revision为非负整数且必填；actionCode固定八选一；selectedResults必须恰好八个互异动作，其resultId各自归属正确且QA可用；可空ID使用null，不用0或空字符串。新增预览frameLayout返回结构化对象，现有整套Preview字符串由单一适配器兼容，禁止双重JSON编码。allowedOperations由当前权限、状态及可恢复证据计算；无证据时不能给出恢复按钮。服务端校验与按钮显示一致，不以按钮禁用代替并发保护。

幂等键作用域包含账号、操作、版本、动作和requestId；同键异参返回409。原子锁定动作revision后才能创建attempt与Outbox；双击只创建一次，不同requestId同时点击仍由活动尝试唯一约束拒绝第二次。选择和重做更新同一actionRevision；每次候选选择变化递增candidateRevision，组装与发布绑定该快照，拒绝并发过时结果。错误时返回最新revision供重读，不自行覆盖。

### 10.4 供应商已生成但本地缺结果：恢复矩阵

| 持久证据 | 允许处理 | 边界 |
|---|---|---|
| 原始成功响应及imageUrl已保存 | 同attempt重试下载；文件到本地后计算哈希、校验、持久保存，再加工 | 下载失败不新建生成请求，地址过期也不能偷偷重新生成 |
| 原始响应保存了但解析失败 | 保留原文，修复/重试解析后继续；展示“响应解析待处理” | 不能丢弃原响应后按生成失败收费重试 |
| 有可查询providerTaskId，无最终图片 | 通过厂商任务查询取回结果，有限轮询；取得URL后保存回执再下载 | taskId与requestId不等价，只有提供方明确支持的标识可查询 |
| 只有providerRequestId | 作为诊断线索，尝试提供方明确支持的核对渠道 | 不把诊断ID拼成任务查询URL，不承诺可找回 |
| 已发请求，但无可用响应/任务ID | 标记UNKNOWN，保留请求摘要与发送时间，停止自动生成重试 | 用户另发必须确认可能重复收费；没有厂商幂等保证就不能承诺“恰好一次收费” |
| 原图已保存、上传或汇总失败 | 复用本地产物/COS结果，只恢复失败的非生成阶段 | 不必再次消耗模型调用，也不让其他动作结果变失败 |

保存顺序：持久化attempt与请求摘要 → 发出一次生成 → 收到响应即保存受保护原始响应 → 解析并保存任务ID/图片URL → 下载原图并持久化 → 加工 → 上传 → 登记结果。原始回执写入失败则保留现场并停止自动生成；读取到部分响应或JSON截断也不能推断未生成。回执日志只输出安全标识与阶段，不输出密钥或签名URL。

恢复策略建议值：单轮下载最多3次（退避1秒、2秒）；后续最多5轮（30秒、1分钟、2分钟、5分钟、10分钟），可配置并受URL有效期限制。到上限进入需要处理状态，不无限轮询。格式错误、认证失败等不可按网络错误盲目重试。领取恢复使用新租约但同attempt，不重复计一次生成。异步任务查询有独立截止时间，超期未确认不自动重发。

未知旧attempt后来返回时，只登记到它自己的结果历史，不覆盖用户新选结果、不重开已终止任务；若用户已授权另一次生成，两次可能都产生费用，必须保留两笔事实而不是藏掉旧调用。

### 10.5 千问适配依据与尚未验证的边界

2026-09-15只读核对：项目 `qwen_image.py` 当前使用 DashScope同步 multimodal-generation 路径，解析request_id及图片URL；未见异步task_id轮询链路。本轮未修改它、未发起收费调用。

官方3.0文档同时提供同步与异步方式，异步通过task_id查询；request_id用于追踪，不能当作task_id。可据此评估将新任务适配为“提交后保存任务ID再查询”，但必须验证当前北京地域、账号、模型、参考图和参数兼容性；未验证前不切换现有可用调用，也不声称旧同步请求能通过异步查询找回。异步提交响应丢失仍有未知窗口，不是绝对不重复收费的保证。依据：[阿里云千问图像生成与编辑3.0 API参考](https://www.alibabacloud.com/help/zh/model-studio/qwen-image-generation-and-editing-api-reference)。

### 10.6 主代理逻辑验证与用户终点验收增补

异步只读兼容性核对（2026-09-15）：官方3.0参考文档明确支持 `qwen-image-3.0-pro`、北京地域、1～3张URL/Base64参考图；因此现有双图+文本结构，以及 n=1、size=1536*1536、prompt_extend、seed、watermark 在协议层可以保留。异步需更换为 `/api/v1/services/aigc/image-generation/generation` 并增加 `X-DashScope-Async: enable`，不能只给同步地址加头。提交后保存 `output.task_id`，用 `/api/v1/tasks/{task_id}` 查询；提交与查询须同地域、同业务空间和API Key。官方说明任务查询及生成链接有效期为24小时，应立即落盘和入COS，不能承诺隔天一定恢复。来源为上节官方链接；本段为文档核对，非当前账号实测。

代码/数据侧：当前适配器没有异步提交/轮询；数据库设计6.22已经有 `provider_task_id`，应优先接线复用，区别于 `provider_request_id`。本机表结构、当前账号权限、实际endpoint覆盖及单动作真实异步效果未验证；本轮未读取密钥、未发送收费生成。需要改的是持久化taskId、轮询/恢复、结果解析与状态回写，不是重新写提示词和图像加工。优先方案为异步，但不自动切换运行服务。首次真实验证另按明确费用边界执行一个动作，复用这一次taskId验证查询中断恢复，禁止为“恢复测试”再提交生成。

- [ ] 主代理亲自核对八卡片来源、单动作预览授权、长ID及版本/动作/result关联，不以页面显示代替后端权限。
- [ ] 核对双击/并发/旧revision不会创建重复收费attempt；recovery任意分支均不能调用生成POST。
- [ ] 核对原始响应保存失败、解析失败、URL下载失败、Worker重启的每条分支，不丢供应商成功事实；未知结果不自动重发。
- [ ] 核对单动作失败不终止其他独立动作；公共基础设施故障可暂停未发请求并解释原因，不盲目消耗剩余动作费用。
- [ ] 核对重做保留旧选用结果，迟到回写不能覆盖；组装固定八个已确认result，已发布版本不可被原地修改。
- [ ] 用户看到八卡片持续更新，完成一个即可单独播放；重做一个时其余七个保留，实际仅有这一个新的生成尝试。
- [ ] 用已有产物/受控故障证明下载恢复不发新生成请求，失败卡片不会要求重跑八次；复杂缺陷补最小聚焦检查，不扩成全面TDD。
- [ ] 八动作采用后整套动态预览与发布通过，官方/私有权限和已定下架/停用/引用保护不因新操作而改变。
