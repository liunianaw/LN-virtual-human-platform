# 历史交接与执行计划导航

2026-09-20清理说明：旧工程计划已删除，可从Git历史恢复；日志已移入本机回收站。当前计划统一见[项目整体说明书](../../项目整体说明书.md)，下文只保留历史证据，不再作为待办清单。

2026-09-15起不再维护独立任务清单。当时依据：[公共角色执行计划](../superpowers/specs/2026-09-15-admin-public-avatar-flow.md)、[私有角色执行计划](../superpowers/specs/2026-09-15-user-private-avatar-flow.md)及各自执行状态。工作按AGENTS.md的计划、编码、验收与修复三阶段执行。下文原清单仅为历史记录，不再驱动当前执行。

2026-09-15 当前优先事项：用户确认按管理员平台使用、用户平台使用、开发者接入划分业务流程，先讨论执行说明书，暂不按旧清单继续实现。代码逻辑验证由主代理本人负责，终点验收保留；不派子代理代验。

- [x] AGENTS.md工作流程已按本轮决定重整：基线核对→完整执行说明书/共享契约→实施→主代理本人逻辑验证→最短联调→用户终点验收；保留轻量测试和启动止损规则。当前不派子代理，旧M1/M2作为历史映射，不据此自动继续编码。

- [x] 写出两份讨论稿：[管理员公共角色](../superpowers/specs/2026-09-15-admin-public-avatar-flow.md)、[用户私有角色](../superpowers/specs/2026-09-15-user-private-avatar-flow.md)。公共稿第4～7节统一共享契约，区分现有接口与拟补内容；本轮仅文档和静态接口盘点。
- [ ] 与用户讨论两份样稿，再确定实施契约；尚未修改业务代码、启动服务或发起付费调用。既有未提交 Avatar 修复保留，不能算已完成或已加载。
- [x] 按需求FR-AV-06/FR-APP-02及数据库6.4、6.8～6.10修正两份样稿：官方管理账号+OFFICIAL、普通下架与紧急停用、可恢复Session引用保护、同角色新版本均沿用既定规则；接口补用途和调用时机。
- [ ] 用户新增要求：保留满意动作、仅重做指定动作以控制费用。单动作制作/重做/候选选用接口仍待细化，现有整套任务表不是新协议定稿。
- [x] 公共稿第10节补充八动作卡片、独立预览/重做/选用/恢复/汇总契约与风险；私有稿引用同一规则。千问3.0官方异步文档与当前请求结构只读核对：协议支持，现有数据库设计已有provider_task_id；账号实际权限/运行配置与真实异步调用尚未验证，未收费调用。

更新：2026-09-13。分支：`feat/m1-db-cos-bootstrap`。恢复时先读 AGENTS.md 和本清单，再查相关 Git commit；实现与验证细节以提交正文为准。

本轮已落地的前置提交：平台库 V1 `d5c9530`、会话服务与 13 表 V1 `568adaa`、媒体 API/Worker `09edbb1`、system Flyway 接线 `e243c7d`、M1 HTTP 健康检查 `731fb44`、受保护的 Nacos 发布工具 `e7a4d5c`（Windows PowerShell/请求头修复 `2b11aa3`、Nacos v3 响应解析修复 `6ae5080`）。这些均待隔离空库与完整服务组联调，不等同 M1 通过。详细执行步骤属于已清理的旧M1工程计划，可查Git历史；保留本页和M1-RUN-001的实际验证记录。

- [x] 精简 AGENTS.md：直接沟通、独立任务并行、按成果提交、复用验证结果；本轮仅调整规范与交接。
- [x] COS 本地配置文件与忽略规则已创建：`f44e189`。用户已手填密钥；现有适配器测试通过。
- [x] 验证 COS 实际配置加载、私有桶上传/签名读取及测试对象清理：运行中的 system 包内本地配置与忽略的源码配置一致；2026-09-13 临时对象上传成功，私有签名读取 HTTP 200 且内容一致，删除后确认不存在。长期密钥的 session-token 保持空值。
- [x] 生成双库 SQL：`0703ede`；静态核对平台库 51 表、会话库 13 表，共 716 个业务字段。
- [x] 本机 MySQL 已建立双库并完成重复执行验证：`platform_db` 51 表、`session_db` 13 表；MySQL 8 仅提示旧整数显示宽度弃用。`s_api_idempotency.resource_type` 按设计“按接口白名单扩展”，不增加固定 CHECK。
- [x] 本机后端核心链路已启动：Nacos 3.2.4 server 模式、Redis、system（9201）、auth（9200）与 gateway（8080）；网关转发 system 文档返回 HTTP 200。Nacos 开发配置已导入 `public` / `DEFAULT_GROUP`。
- [x] 本地初始管理员与系统权限种子已写入：`admin` 可登录并经网关读取菜单；种子脚本可重复执行，生产环境须重置默认密码。
- [x] **M1-PLAT-001**：将已验证的 51 张 `platform_db` 表与本地管理员种子迁入 `ruoyi-system` 的 Flyway V1；空库、重启与 checksum 负向检查均已在隔离库验收。既有本机库已安全登记 Flyway V0 基线并执行 V1，随后默认配置重启为 `UP`。
- [x] **M1-SESS-001**：`ruoyi-session` 经 Nacos 启动并在 `9202/actuator/health` 返回 `UP`；带时间戳的隔离库首次 Flyway V1 创建 13 张会话表，重启后 history 仍仅一条成功 V1。启动时只注入会话库环境变量。
- [x] **M1-MEDIA-001**：`ruoyi-media` API 在 `8002/health` 返回 200/ready，有限 Worker smoke 输出 ready 与 heartbeat，3 项单元测试通过；未连接供应商、MQ 或业务库。
- [x] **M1-CONFIG-001**：Compose 已定义 RabbitMQ、system、session、media API/Worker 服务边界；Nacos guarded publisher 已实测通过认证写入并回读 7 份开发配置（含 Sentinel JSON 规则）。`792de0c` 处理 Nacos Client API 瞬时未就绪响应。
- [x] **M1-LOGIN-001**：本机浏览器已完成 admin、只读开发者、运维开发者的登录/退出及菜单边界验证；只读开发者的邮箱标识已经真实登录复核；停用账号被登录页拒绝，直接越权 API 保持 403。未修改任何前端视觉或新增 UI 功能。详见 `tasks/M1-RUN-001.md`。
- [x] **M1-OPS-001**：最终 `check-m1-services.ps1` 结果中 gateway/auth/system/session/media API 及 gateway→system OpenAPI 全部为 HTTP 200；Worker 持续输出 ready/heartbeat；Vue 生产构建、测试、类型检查均通过。详见 `tasks/M1-RUN-001.md`。
- [ ] **M2-ASSET-001**：参考图私有 COS 上传、文件授权与制作任务幂等/额度/Outbox 基础。
- [ ] **M2-PROCESS-001**：外部生成编排、CPU 素材加工、八动作图集与 manifest 校验、失败/未知/Worker 重启恢复。
- [ ] **M2-PUBLISH-001**：后台预览、人工验收、不可变 Avatar 发布版本及跨账号引用限制。
- [ ] **M2-VOICE-001**：官方与 Relay Voice 配置、最小 `SPEAK_ONLY` 调试 Session、分段播放、stop 与临时音频清理；不做声音克隆。
- [ ] **M2-ACCEPT-001**：真实成功路径与受控故障验收、本机完整服务组和浏览器记录、正式样品包与已知问题收口。

2026-09-14 Avatar 链路排查（由主代理独立执行）：

- [x] 确认提交→Outbox→Worker→COS 参考图→千问 POST 的实际链路。服务 `930010002` 使用 `qwen-image-3.0-pro`；密钥与已成功的 validation 配置一致。参数已对齐该验证脚本（`prompt_extend=false`、`seed=20260908`）。原 `prompt_extend=true` 请求在等待响应时 300 秒超时；对齐参数的任务 `102078449035771933` 于 23:02:21 发出，29.48 秒后 HTTP 200，厂商请求 ID `d722a517-d8fa-9b14-98b8-320de4f9d5eb`。这是成功响应证据，不等于八动作制作成功，也不足以断言所有上游超时都由该参数引起。
- [x] 修复慢调用期间不续租导致超时回写被 `STALE_LEASE` 丢弃；真实超时任务 `102078449035771920` 已正确回写 UNKNOWN attempt 和 FAILED task，Outbox 已结束。旧任务 `102078449035771908` 的两条未得到响应的 attempt 已保留为 UNKNOWN，不自动重发。
- [x] 独立复现后处理失败：真实调用 `8e5ef8e6-0dec-93f8-99a5-26be61f875d5` 23.45 秒 HTTP 200，原图为六格不同颜色背景，处理器拒绝不符合抠图约束的边缘。正式链路现复用 validation 的纯品红提示词、1024×1536 参考图整理及六格排版参考，代替泛化英文提示。修正后真实调用 `0c5c5d98-8bec-9fe8-babc-5ff478eebb8d` 33.42 秒 HTTP 200，单个 idle 动作切帧/抠图通过，6 帧、atlas、manifest 共 8 文件上传 COS 后回读 SHA-256 一致；仍有 `touches_border_05` 人工检查提示。真实诊断原图及包保留在 `logs/real-action-fixed/`，COS 前缀 `avatar-generation-diagnostics/20260914/0c5c5d98/`。这些是独立诊断产物，未伪造成业务任务成功。
- [x] 页面显示任务错误码；确定的后处理格式失败标记 `ACTION_PROCESSING_INVALID`，与上游结果未知区分。生成原图持久保留（默认系统临时目录下 `ruoyi-media-generation`），便于离线修复，私有素材不提交 Git。
- [ ] 完整八动作、汇总登记及后台预览仍未通过。顺查代码发现 `submitSucceeded` 只登记 `p_file` 和动作 step，缺少 `p_avatar_action`、版本 manifest/QA/几何汇总写入；随后 `markGenerationReadyForReview` 的失败被吞掉。必须补齐正式汇总链路并真实验收，不能仅凭八次图片返回就标记 M2 完成。

本轮 system 与 Worker 已重启加载修复，system health 为 UP；前端 Vite 保持运行并热更新。Java 打包、Vue 类型检查、当前源码的 3 项入口检查和 2 项聚焦验证（慢调用续租/UNKNOWN 不重发、后处理失败保留原图）通过。现场日志在未提交的 `logs/worker-traced-out.log`，不得把进程 ready 或单次 HTTP 200 写成 M2 已通过。此前四个业务任务保留 FAILED/UNKNOWN 历史，均不自动重发。

当前执行顺序：**M1 本地验收已通过**，完整证据见 `tasks/M1-RUN-001.md`。下一阶段按 `M2-ASSET-001` 开始；COS 真实私有桶上传/签名读取/清理是 M2 的真实外部服务验证，未在 M1 执行。当前 Nacos 已启用认证；Java 服务冷启动时由进程环境传入 Nacos 登录凭据，绝不写入仓库或 Nacos 配置。前端只承担最小后端验证，不做美化。

M2 当前实现提交：`ee7a287`（受保护 Worker/COS 输出接线）、`0ac4650`（后台候选预览与发布入口）、`1b3d0b9`（Worker Compose/COS 依赖）、`ea645b0`（Voice 与 DEBUG SPEAK_ONLY 授权）。2026-09-14 本机集中验收已完成受影响模块打包、媒体依赖安装、全部 HTTP 健康检查和 Worker 心跳；Worker 缺失或错误内部令牌均返回应用码 `401`。Windows 下 Java 必须以 `-Dfile.encoding=UTF-8` 启动，否则 Nacos YAML 中的中文会触发加载器把 `MalformedInputException` 误报为“配置不存在”。M2 的部署验收口径已由用户改为本机完整服务组真实运行，不再要求 Linux Compose；真实八动作图像、官方 TTS 与 Relay 试听、浏览器样品/故障记录仍必须实际完成，不能以构建或模拟路径替代。

Git：COS 与 SQL 已本地提交，未推送、未合并。此次仅文档修改，不重写历史。历史资料按需查阅：[M1-DB-001](tasks/M1-DB-001.md)、[GOV-001](tasks/GOV-001.md)、[模块裁剪](../ruoyi-module-trimming.md)、第一阶段方案（旧稿已清理，可查Git历史）。
