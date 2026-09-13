# 当前任务清单

更新：2026-09-13。分支：`feat/m1-db-cos-bootstrap`。恢复时先读 AGENTS.md 和本清单，再查相关 Git commit；实现与验证细节以提交正文为准。

本轮已落地的前置提交：平台库 V1 `d5c9530`、会话服务与 13 表 V1 `568adaa`、媒体 API/Worker `09edbb1`、system Flyway 接线 `e243c7d`、M1 HTTP 健康检查 `731fb44`、受保护的 Nacos 发布工具 `e7a4d5c`（Windows PowerShell/请求头修复 `2b11aa3`、Nacos v3 响应解析修复 `6ae5080`）。这些均待隔离空库与完整服务组联调，不等同 M1 通过。详细执行步骤见 `docs/superpowers/plans/2026-09-12-m1-foundation.md`。

- [x] 精简 AGENTS.md：直接沟通、独立任务并行、按成果提交、复用验证结果；本轮仅调整规范与交接。
- [x] COS 本地配置文件与忽略规则已创建：`f44e189`。用户已手填密钥；现有适配器测试通过。
- [ ] 验证 COS 实际配置加载、私有桶上传/签名读取及测试对象清理。长期密钥的 session-token 保持空值。
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
- [ ] **M2-ACCEPT-001**：真实成功路径与受控故障验收、Linux Compose 和本机浏览器记录、正式样品包与已知问题收口。

当前执行顺序：**M1 本地验收已通过**，完整证据见 `tasks/M1-RUN-001.md`。下一阶段按 `M2-ASSET-001` 开始；COS 真实私有桶上传/签名读取/清理是 M2 的真实外部服务验证，未在 M1 执行。当前 Nacos 已启用认证；Java 服务冷启动时由进程环境传入 Nacos 登录凭据，绝不写入仓库或 Nacos 配置。前端只承担最小后端验证，不做美化。

Git：COS 与 SQL 已本地提交，未推送、未合并。此次仅文档修改，不重写历史。历史资料按需查阅：[M1-DB-001](tasks/M1-DB-001.md)、[GOV-001](tasks/GOV-001.md)、[模块裁剪](../ruoyi-module-trimming.md)、[第一阶段方案](../superpowers/specs/2026-09-09-phase-one-design.md)。
