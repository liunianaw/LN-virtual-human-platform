# 管理员官方声音配置、试听与发布执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.1。确认状态：用户于 2026-09-21 明确授权按本计划实施；仅做静态代理逻辑验收，不启动服务、不进行真实合成或外部调用。

## 1. 当前执行状态

| 项目 | 当前事实与证据 |
|---|---|
| 阶段/基线 | V-A～V-C 已实施；基线为 2026-09-21 当前源码和 `41c85b8`、`d6f8b7b`；未触碰未跟踪的 `logs/`。 |
| 已有能力 | 官方候选/版本/发布、公开目录、管理页、候选专用 `VOICE_PREVIEW` DEBUG、S→一次性票据→首帧WSS→音频读取/顺序播放回执/停止和临时音频清理已接线；官方TTS适配器继续复用。 |
| 静态逻辑验证 | system/session Maven compile 与 Vue typecheck 均通过；已审阅候选隔离、版本指针、服务修订、幂等、票据、WSS、媒体读取与清理的生产/消费链。 |
| 用户体验/修复 | 未启动服务、未创建真实 DEBUG 会话、未发起合成或播放；角色制作仍只作为输入。 |
| 下一步 | 用户在已有服务和角色下按第9节触发试听与发布终点验收；代理不启动服务、不执行迁移或真实合成。 |

## 2. 目标、范围和执行边界

管理员选择官方TTS服务，保存音色候选，通过平台同一播放链试听，然后公开发布给用户选择；后续变更形成新版本。必须交付官方列表、详情、候选版本、试听、发布；下架/删除按钮调用生命周期计划。只使用现有已选官方模型/音色，不新增音色探索、私有Relay或克隆。本轮只写计划，费用与静态边界见C1/C5。

## 3. 已确认决策与待决事项

| 规则 | 来源 | 实施含义 |
|---|---|---|
| Voice与Avatar独立、官方声音公开引用 | 需求FR-VOICE-01、数据库6.11/6.12 | 音色不保存到角色；应用绑定voiceVersionId |
| 不可变声音版本 | 需求FR-APP-02 | 更新不改旧会话和应用声音 |
| 试听与正式播报共用 | C4、需求FR-DEBUG-01 | 复用C4，不浏览器直调厂商 |
| 私有声音延期 | 用户2026-09-19决定 | 用户仅选择官方声音；旧私有数据不删除 |

C4“专用试听应用”是待确认设计，用于候选不公开即可试听；无额外厂商待选问题。当前已选Cherry是首个可用音色，列表由已实现适配器的可用项驱动，不凭模型名允许运行不支持音色。

## 4. 现有代码链路与强制复用

| 来源→正式落点 | 当前行为/保留内容 | 复用与差异 |
|---|---|---|
| [VoiceController/Service](../../../RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/voice/VoiceService.java):createOfficial/read | 管理员校验、服务检查、Voice/版本入库；当前创建即PUBLISHED | 适配为候选保存/独立发布；兼容旧调用入口，不再隐式公开新候选 |
| DebugSessionService→SessionDebugClient→ConsoleDebugGrantService | 原后台登录绑定DEBUG | 按C4适配候选试听专用路径，普通应用仍要求已发布声音 |
| OfficialDashScopeTtsRuntimeAdapter→TtsAdapterSupport→TemporaryWavStorage | 真实厂商协议、WAV检查与临时保存 | 直接复用，由播报计划补事件交付；不另建音频生成器 |
| Vue现有request与avatar-sdk | 声音管理/试听页面尚缺 | 新声音页面和专用请求封装；播放器调用同一播报组件 |

## 5. 完整业务流程

| 步骤 | 操作/输入 | 处理/输出与落库 | 页面反馈/失败 |
|---|---|---|---|
| 1 | 管理员进入声音管理 | 官方服务列表→选择ACTIVE TTS配置及可用音色 | 无服务则指向服务管理，不手填ID |
| 2 | 输入名称、说明、音色、白名单参数并保存 | p_voice DRAFT、不可变p_voice_version；保存非秘密快照 | 返回voiceId/versionId/ETag；校验失败不调用厂商 |
| 3 | 选已有发布角色并点击试听 | C4创建专用DEBUG→S→票据→speech.create | 显示费用提示、播放/停止；失败保留候选及错误摘要 |
| 4 | 听取结果后确认发布 | 锁Voice，校验服务有效、候选归属与revision；设置current_version_id/PUBLISHED | 公共声音列表出现；不再合成 |
| 5 | 修改声音配置 | 同voiceId追加version_no，不改已发布版本；重复试听/发布 | 旧应用保持原voiceVersionId |
| 6 | 下架/停用/删除 | 转交生命周期接口 | 引用保护及影响提示，不能页面直接删文件 |

## 6. 接口、数据与状态契约

以下目标API遵C2，A及platform:officialVoice:*，浏览器/gateway/system路径一致。旧 /api/v1/voices/admin/official需显式兼容适配到“保存候选”，返回新状态；更新调用方，不保留自动公开旁路。

| 方法/路径及用途 | 请求/响应data |
|---|---|
| GET /api/v1/admin/voices；管理列表 | 分页/status → Voice摘要列表 |
| POST /api/v1/admin/voices；新建候选 | VoiceInput、Idempotency-Key → voiceId/versionId/status=DRAFT/revision |
| GET /api/v1/admin/voices/{voiceId}；编辑/版本查看 | → Voice及versions[]、currentVersionId、ETag |
| POST /api/v1/admin/voices/{voiceId}/versions；修改音色 | VoiceInput、If-Match、Idempotency-Key → versionId/versionNo/revision |
| POST /api/v1/admin/voices/{voiceId}/versions/{versionId}/publish；人工发布 | {auditionConfirmed:true}、If-Match、Idempotency-Key → voiceId/currentVersionId/status/revision |
| GET /api/v1/voices；用户可选官方目录 | C、分页 → 已发布OFFICIAL列表及currentVersionId，屏蔽草稿/秘密 |
| 试听创建/授权/播放 | C4统一接口，拒绝普通用户试听未公开版本 |

| 字段 | 类型/必填/来源→映射 |
|---|---|
| name/description | C2；表单→p_voice |
| officialServiceId | 必填ID；服务选择器→p_voice_version.official_service_id；后端校验TTS/ACTIVE |
| expectedServiceRevision | 必填ID样式字符串；服务查询→快照锁内复核；不是Voice ETag |
| voiceAlias | 必填1～128；适配器可用音色→voice_code |
| language/modelAlias | 可省略；从服务能力推导，不允许覆盖任意模型；存language_code/model_id |
| parameters | 必填对象；只允许适配器支持的数值参数，{}有效；禁止任意URL/headers |
| auditionConfirmed | 发布必填true，用户确认；不是伪造的供应商成功证据 |

例：POST候选 {"name":"官方中文声音","officialServiceId":"2001","expectedServiceRevision":"2","voiceAlias":"Cherry","parameters":{}} → data {"voiceId":"3001","versionId":"3101","status":"DRAFT","revision":"1"}；POST该版本publish {"auditionConfirmed":true} → currentVersionId="3101"。试听失败不能自动勾选确认。

复用p_voice/p_voice_version唯一(voice_id,version_no)。版本表无独立status，未成为currentVersionId且未被正式引用的候选由关联事实区分，不凭空写不存在的列。首次DRAFT→PUBLISHED；已有PUBLISHED创建候选时旧current不变，发布新候选原子切指针；后续状态见C3。保存版本、快照、revision、幂等同事务；真实试听在事务外，不能长事务等WSS。需要发布人工确认审计时复用审计或追加明确字段，不修改已执行迁移。

不变量V1私有仍延期且官方凭证不公开；V2候选试听不公开；V3旧版本不变；V4发布不再收费。试听音频只临时保存、无需长期sample_file_id；后续长期样本不在此次。超时/未知按C5不自动重合成；服务修订冲突先刷新并明确保存新候选，不偷换参数。

## 7. 编码顺序与完整能力切片

| 切片 | 前置依赖 | 完整行为/落点 | 不变量与静态退出 | 交接 |
|---|---|---|---|---|
| V-A | 服务S-A、C2 | 页面选择服务→保存/查询候选；改VoiceService/Controller及新增Vue声音页 | V1/V3；ID/快照/版本事务闭合 | 可供试听的versionId |
| V-B | C4、播报D-A/D-B | 创建专用DEBUG→试听→停止→清理；Voice页复用播报组件 | V2；候选授权不泄露到普通应用 | 用户确认 |
| V-C | V-A/V-B、生命周期 | 发布→公共目录→应用选择；旧版本保持 | V3/V4；候选与当前指针分离、无二次合成 | 应用配置 |

## 8. 主代理静态逻辑验证

| 不变量 | 两端代码/数据 | 结论与辅助检查 | 待用户体验 |
|---|---|---|---|
| V2 | preview-sessions→VOICE_PREVIEW绑定→S→票据→首帧WSS；普通USER绑定与公开目录均过滤PUBLISHED | 静态审阅通过：候选版本仅被预览应用引用，普通应用SQL要求PUBLISHED，媒体读取按S的session/account过滤 | 管理员可试听，用户看不到草稿 |
| V3 | VoiceService版本事务→不可变p_voice_version→预览配置固定versionId | 静态审阅通过：追加版本不更新current指针，发布才在锁内切换；已创建会话保存configVersionId/voiceVersionId | 切新版不改变旧应用 |
| V4 | publish Controller→VoiceService事务；WSS speech.create→适配器 | 静态审阅通过：publish分支只有服务复核和SQL更新，无TTS适配器/运行时调用；试听仅由页面显式speech.create触发 | 发布不再播放或产生合成 |

## 9. 用户终点验收与修复

管理员使用已有官方服务和角色：保存候选→试听/停止→普通用户看不到候选→确认发布→普通用户可选→新版本试听不改变原应用。另验服务禁用时不能继续试听或发布。试听由用户实际触发并确认费用范围，代理不沿用旧预算自动调用。出现音频/权限/版本问题，由主代理修复对应共享链路、静态复验后用户复验；当前均未执行。提交遵C1。

## 10. 跨模块交接与项目贯通

[服务管理](2026-09-19-admin-official-services.md)提供服务/秘密解析；本计划输出voiceVersionId、公开可见性与不可变配置；[应用](2026-09-19-user-application-config.md)负责绑定；[播报](2026-09-19-user-console-speech.md)负责真实播放。试听先依赖播报内核，不能将声音页面保存完成冒充本计划完成。

## 11. 执行记录（2026-09-21）

- [x] V-A：新增管理候选、详情、版本、官方服务选择和公共已发布目录；服务快照只保留非秘密字段，写操作要求 Idempotency-Key，版本/发布要求 If-Match。
- [x] V-B：候选版本只能通过 `VOICE_PREVIEW` 应用配置进入既有 DEBUG Session 流程；S换一次性票据，浏览器首帧认证WSS，`AudioReadyResult`发布有序`audio.segment`，S读取WAV并回传播放状态；关闭预览会停止轮次、撤销grant并触发临时音频清理。普通 `USER` 应用查询明确只允许已发布 Voice，预览配置保存 `APP_CURRENT` 引用。
- [x] V-C：人工确认 `auditionConfirmed=true` 后才切换 `current_version_id`；发布事务锁主表、复核候选服务仍 ACTIVE 且修订未变，不含任何合成调用；公共目录过滤为 `PUBLISHED`。
- [x] 静态检查：`mvn -B -ntp -pl ruoyi-modules/ruoyi-system -am compile -DskipTests`、`mvn -B -ntp -pl ruoyi-modules/ruoyi-session -am compile -DskipTests`、`npm run typecheck`、差异空白检查通过。未启动服务或执行迁移，因此数据库兼容性、网关加载、真实试听/停止/播放和终点体验均留给用户验收。
