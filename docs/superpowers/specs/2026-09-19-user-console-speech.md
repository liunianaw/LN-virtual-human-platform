# 用户平台内播报调试与会话收尾执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.1。确认状态：范围依据用户本轮决定，具体计划待确认；本轮只编写文档，不授权编码或运行时操作。

## 1. 当前执行状态

| 项目 | 事实 |
|---|---|
| 阶段/代码 | 计划讨论稿；实施前以 2026-09-21 当前源码和 `41c85b8`、`d6f8b7b` 为基线复核。 |
| 已有能力 | DEBUG创建/Token、官方TTS、分段处理/停止、SDK本地播放 |
| 发现的缺口 | WSS仅处理turn.stop；TtsAdapterSupport忽略onAudioReady返回事件；缺授权音频读取/调试页面；临时删除实现需补齐 |
| 静态结论 | 新完整链路证据不足；本轮源码盘点不算静态验收通过 |
| 用户验收/下一步 | 本计划未执行；确认后按D-A～D-C接线，不真实合成 |

## 2. 目标、范围和执行边界

用户从已发布SPEAK_ONLY应用进入调试，角色加载完成后输入文字、听到按顺序播放的官方声音与对应动作，并可停止、退出和清理。管理员官方声音试听复用此内核。必须包含实际浏览器可用授权、实时事件、音频读取、顺序播放、异常反馈及持久清理。不做外部接入示例、B/M授权、LLM、ASR、私有Relay或美化。费用/操作边界见C5。

## 3. 已确认决策与待决事项

| 规则 | 依据 | 处理 |
|---|---|---|
| 调试只能来源于当前后台登录 | C4 | C创建授权、S运行；登出使旧调试失效 |
| 实际播放才驱动speaking | 需求FR-RENDER-02 | 收到音频事件不能直接播放说话动画 |
| 独立播报不写AI历史 | C4.2、数据库s_turn.include_in_history | 仅保留必要状态/计量，不存正文 |
| stop不等于供应商未计费 | 需求FR-SES-03 | 立即停本地，后台尽力取消，未知核对 |
| 标准音频/连接协议 | C4.1～C4.4 | 复用24kHz WAV及首帧票据，不自行另造协议 |

无业务待决项；接通过程中若发现协议字段与既有实现不一致，按接口契约适配并兼容旧调试入口，不能降低为仅HTTP调用成功。

## 4. 现有代码链路与强制复用

| 已有源码/调用点 | 已有事实与保留行为 | 本次差异 |
|---|---|---|
| system/voice/DebugSessionService.create/mint/binding → SessionDebugClient | 当前登录绑定和固定应用读取；部分登记在内存 | 持久恢复/引用预留、实时状态检查；不伪造Application Secret |
| session/runtime/ConsoleDebugGrantService、DatabaseConsoleDebugSessionAuthenticator | grant入库与S校验 | Scope补齐C4，登录撤销和资源停用即时接线 |
| RuntimeWebSocketConfiguration.RuntimeHandler、RuntimeVoiceController | WSS只stop；HTTP speech调度已有 | C4票据首帧、命令、事件publisher共用服务；不保留无鉴权兼容旁路 |
| SpeakOnlyRuntimeService.start/onAudioReady/reportPlayback/stop → PersistentRuntimeStore | 分段/序号/停止/库状态存在，部分轮次状态内存 | 并发新轮/幂等及重启收尾；事件必须被实际发往对应连接 |
| OfficialDashScopeTtsRuntimeAdapter → TtsAdapterSupport.complete → TemporaryWavStorage | 原厂商请求、WAV检查、临时保存 | 复用完整失败路径；接收AudioReadyResult并发布，记录UNKNOWN而非泛化失败 |
| TemporaryAudioCleanupQueue/RuntimeExpiryReaper/TemporaryAudioDeletionExecutor | 清理排队、过期扫、删除接口 | 真正本地删除执行与s_temp_object重启扫描，不只内存队列 |
| avatar-sdk/src/avatar-player.ts:loadPackage/playAudio/stop/destroy | 正式manifest及本地声音动作控制 | 直接引用SDK，新增平台调试连接/分段队列；不复制Canvas/manifest解析器 |

system/session路径前缀同服务管理计划；本轮实际文件可在[运行时目录](../../../RuoYi-Cloud/ruoyi-modules/ruoyi-session/src/main/java/com/ruoyi/session/runtime)和[播放器](../../../avatar-sdk/src/avatar-player.ts)定位。不迁入整套外部框架。

## 5. 完整业务流程

| 步骤 | 用户/触发输入 | 处理与输出/持久化 | 页面反馈/失败恢复 |
|---|---|---|---|
| 1 | 应用详情点调试，applicationId | C4预留引用→DEBUG Session→S→票据 | 创建中/连接中；失败核对同operationId |
| 2 | 首帧认证、请求正式包 | 固定版本/当前权限→manifest→SDK加载 | 角色完整出现；加载失败不开始合成 |
| 3 | 用户输入1～8000码点文字并点击播报 | speech.create稳定requestId；锁Session创建轮/段，调用官方TTS | 显示合成/等待播放，不编造帧级进度 |
| 4 | 段完成 | 存音频、持久mediaId归属→audio.segment | fetch带S获取音频，按ordinal播放；后段不越过前段 |
| 5 | 浏览器playing/pause/waiting/ended | SDK驱动动作、发送playback.report；清理已结束音频 | 自动播放被拒提供点击恢复，不自动重新合成 |
| 6 | 点击停止或开始新播报 | 先本地stop，再服务端终止旧轮，迟到丢弃但保留用量事实 | 立即静音回idle；旧turn.stop不影响新轮 |
| 7 | 离开、登出、失效、断网 | 停轮/禁读/释放本地URL；后台清理与引用核对 | 状态明确，不刷新后自动重播 |

## 6. 接口、数据与状态契约

管理路径、运行时HTTP接口、WSS信封和全部公共字段直接引用C2/C4及C4.1～C4.4，不维护第二套命名。新增前端页面建议views/application/debug.vue，接收路由applicationId；官方试听嵌入同一调试组件，sessionId来自声音计划，不由浏览器伪造绑定。

最小命令示例（ticket已验证后）：{"v":1,"type":"speech.create","requestId":"speak-example-1","connectionEpoch":"3","data":{"text":"你好，欢迎使用。"}}。受理响应为request.ack，给原requestId及turnId；audio.segment数据必须含segmentId字符串、ordinal整数、mediaId字符串、mimeType、durationMs、expiresAt，不含正文或裸音频地址。客户端请求GET runtime/media/{mediaId}时附S。WSS字段约束沿C4.1～C4.4，不以此简写代替完整信封。

标识链：applicationId→configVersionId→sessionId→grant→connectionEpoch→turnId→segmentId/mediaId；身份/归属来自服务端。p_resource_reference在system写；session只写s_session/grant/turn/operation/temp_object/outbox等自己的库。

- READY连接才可发speech；Session每次只允许一轮，新有效请求在s_session锁内终止旧轮并创建新轮；同requestId同正文摘要返回原事实，不停止当前另一轮，不二次合成。
- s_turn的status/text_status/audio_status/playback_status沿C4.3分别记录；纯播报text_status=NOT_REQUESTED；用户停止后晚到合成可修正调用事实但不能复活轮次或播放。
- 音频归属/有效期/当前轮与grant每次读取都检查；stop后立即不可读。临时文件不是COS正式角色资源，禁止用静态目录暴露。
- 发布音频事件必须晚于文件可读取及数据库登记；事件发送失败不重合成。丢失的进程内状态按库记录中断/UNKNOWN收尾，不凭空重启厂商请求。
- 增加票据、scope及必要清理字段按既有表/Redis职责追加迁移，不能改已有V1。真实对象删除走限定临时目录，校验路径，保留重试事实。
- 当轮文本/音频不入长期调用日志；C5唯一调用事实不随会话清理丢失。

不变量D1 C与S分离；D2版本与权限一致；D3一次业务请求不自动二次合成；D4顺序播放、实际播放驱动动作；D5stop/过期/撤销后不复活；D6临时数据可清理且重启可收尾。完整故障矩阵C5；额外：自动播放失败等待用户手势，等待超时则STOPPED/UNKNOWN按协议，不无界缓存和合成。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整能力/落点 | 不变量与静态退出 | 交接 |
|---|---|---|---|---|
| D-A | C2/C3、应用A-A（已完成静态实现）、声音V-A | 登录→引用→Session/S→票据→正式包→SDK显示 | D1/D2；所有身份/引用/路由生产消费一致，必要Java/TS检查 | 官方试听和应用页面 |
| D-B | D-A、服务解析 | 文字→TTS→持久音频→事件→fetch→顺序播放→回执 | D3/D4；AudioReadyResult被消费、没有缺失读取端 | 用户听到声音 |
| D-C | D-B、C5调用事实 | stop/替换/撤销/断网/重启→禁读→清理→引用释放 | D5/D6；终态和删除执行完整，事件重试不重费 | 生命周期、运维 |

## 8. 主代理静态逻辑验证

| 不变量 | 真实代码生产方→消费方 | 当前结论/辅助检查 | 用户验收 |
|---|---|---|---|
| D1/D2 | DebugSessionService→grant→ticket→WSS/media | 证据不足；查浏览器可建立连接及全程账号/epoch | 登录进入、退出失效、跨账号拒绝 |
| D3/D4 | start→createSpeakTurn→adapter→onAudioReady→publisher→SDK | 当前事件连接缺失；待编码后逐分支审阅 | 多段按顺序播报且动作跟随 |
| D5/D6 | stop/revoke→SQL/outbox→media守卫→删除执行器 | 当前删除执行接口不足；补齐后核对重启路径 | 停止无迟到声，刷新不重播 |

只运行无外部连接的必要编译/类型检查；不默认建Mock矩阵或启动服务。具体静态结论在实现后填写，不预填通过。

## 9. 用户终点验收与修复

前置：已有已发布角色、官方声音、SPEAK_ONLY应用，管理员/用户账号；已有Key沿配置来源复用。用户进入调试→输入短句→听到并看到speaking→多段按序→暂停/缓冲回idle→停止立即静音→新轮不被旧音频打断→刷新无自动播放→退出旧授权失效。再验声音/服务被管理员停用时旧调试停止。用户确认视听效果；代理只有另行授权才协助真实调用，并先明确本次费用上限。失败保留安全诊断，修复后只静态复查影响链及用户复验。当前未执行；Git遵C1。

## 10. 跨模块交接与项目贯通

本计划是[官方试听](2026-09-19-admin-official-voices.md)与[应用配置](2026-09-19-user-application-config.md)共同消费端；输出调用状态给[运维](2026-09-19-admin-task-operations.md)，释放引用给[生命周期](2026-09-19-admin-public-asset-lifecycle.md)。平台页面真实听到、停止、退出才是终点；服务端返回200或厂商合成成功不算完成。
