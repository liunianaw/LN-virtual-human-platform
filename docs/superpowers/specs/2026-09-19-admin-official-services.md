# 管理员官方服务配置与管理执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.2。确认状态：用户于 2026-09-21 授权按本计划编码；本轮只做静态逻辑验收，不启动服务、执行迁移或发起真实厂商调用。

## 1. 当前执行状态

| 项目 | 当前事实与证据 |
|---|---|
| 阶段/基线 | S-A～S-C 静态实现完成；以 2026-09-21 当前源码和 `487355c` 为基线，未触碰未跟踪 `logs/`。 |
| 已有能力 | 图像服务查询及任务快照、官方 TTS 适配器，以及本计划新增的服务管理、安全凭证和运行时解析链路。 |
| 静态核对 | `ruoyi-system`、`ruoyi-session` Maven compile、Vue typecheck、媒体 Worker 定向测试及 XML/差异检查通过；未以这些替代真实服务验收。 |
| 用户验收 | 未启动服务、未执行 Flyway、未保存真实凭证、未发起图片/TTS 调用；已完成角色制作不重验。 |
| 剩余/下一步 | 用户按第9节在受控环境验收保存、检查、启停、制作和试听；部署时提供受保护的 `LN_OFFICIAL_SERVICE_MASTER_KEY`，不写入仓库。 |

## 2. 目标、范围和执行边界

管理员从已有官方配置进入，完成查看、配置、检查、启停，终点是制作或官方声音能使用明确选中的服务且停用生效。必须交付非秘密配置与凭证安全维护；保留已成功使用的模型、参数及现有秘密来源。明确延期用户Relay，非目标为多厂商自动路由、自动换Key、模型探索与新增制作流程。操作边界及Git安排遵共享C1/C5。

## 3. 已确认决策与待决事项

| 规则 | 依据 | 实施含义 |
|---|---|---|
| 官方生成/TTS由管理员配置 | 需求FR-PROV-01；数据库6.3～6.4 | 普通用户只有可用服务选择权，不能读官方Key |
| 模型显式选择，不自动切换 | 已定配置及需求FR-SPEECH-01 | 图像沿用qwen-image-3.0-pro；首个TTS沿用北京既有模型与Cherry，不为证明配置而再生成 |
| 任务/Voice固定非秘密快照，服务实时停用 | 数据库6.4、需求FR-APP-02 | 旧任务不偷换模型，禁用后未提交调用不得开始 |
| 检查不等于真实生成/试听 | 本轮仅规划，规范7 | 默认校验参数和凭证引用；真实探测需单独动作及费用授权 |

没有重新选择供应商等业务待决项。新增API及凭证接线为本稿设计，待计划确认；部署主密钥可用性在实施前核对，不要求用户重复提供已有厂商Key。

## 4. 现有代码链路与强制复用

路径均相对根目录。

| 环节/源码与方法 | 当前事实 | 方式及必要差异 |
|---|---|---|
| RuoYi-Cloud-Vue3/src/api/asset/avatar.ts:listAvatarGenerationServices → asset/controller/AssetController.listAvatarGenerationServices | 列出当前可用服务 | 原样复用选择器；新增管理页面，不改制作表单参数 |
| system/src/main/resources/mapper/system/AssetMapper.xml:selectActiveAvatarGenerationService(s) → asset/service/AssetService.createGenerationTask | 读ACTIVE配置、保存任务serviceSnapshot | 复用查询及快照格式；管理员写入必须被同一查询消费 |
| system/voice/VoiceService.createOfficial → session/runtime/OfficialDashScopeTtsRuntimeAdapter.synthesize | Voice固定已选模型；适配器目前读VoiceRuntimeProperties和进程Key | 适配为内部解析所选服务与secret引用，保留实际TTS协议与异常路径，不能保存服务A却调用全局B |
| system/storage与现有本机秘密配置 | COS已有验证成果 | 不改COS、不打印私密配置；官方凭证p_secret按既定设计加密维护 |
| gateway配置及若依菜单/权限 | 旧/system路由与权限框架可用 | 新管理路由和独立前端响应适配遵C2 |

本表system缩写为RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system；session同级ruoyi-session/src/main/java/com/ruoyi/session。强制保留已验证的图像适配器/参数，不重写供应商算法。

## 5. 完整业务流程

| 步骤 | 操作/输入来源 | 处理与落库/输出 | 页面反馈/失败恢复 |
|---|---|---|---|
| 1 | 管理员打开服务列表 | 分页查询p_official_service，返回ETag及credentialConfigured | 不回显密钥；无权限403 |
| 2 | 选择现有配置或新增；名称/能力/适配器/地址/模型/参数 | 校验适配器和地址白名单、schema；新配置默认DISABLED | 展示字段错误，不自动外发验证 |
| 3 | 选择已有官方秘密引用或输入替换凭证 | p_secret AEAD密文与服务引用；部署主密钥不入库 | 只返回是否配置；失败保留旧凭证 |
| 4 | 点击检查 | 检查同revision参数及秘密可解析，记录安全检查结果 | “配置检查通过”不称“厂商调用成功” |
| 5 | 点击启用 | 锁行复核revision/配置有效性，ACTIVE；revision递增 | 制作/声音选择器可见 |
| 6 | 修改参数或停用 | 写修订与审计/撤销事件；不改旧任务快照 | 提示在用影响；旧revision冲突刷新 |

## 6. 接口、数据与状态契约

本表全部为新增目标；现有图像服务GET保持兼容。管理员页面→gateway同路径→system，A及platform:service:*，公共外壳/分页/ETag/幂等见C2。

| 接口及用途 | 请求 → data |
|---|---|
| GET /api/v1/admin/official-services；列表/筛选 | capability/status/pageNum/pageSize → 公共分页，每项Service |
| GET /api/v1/admin/official-services/{serviceId}；编辑前读取 | 无body → Service及ETag |
| POST /api/v1/admin/official-services；保存禁用配置 | ServiceInput、Idempotency-Key → Service，201 |
| PUT /api/v1/admin/official-services/{serviceId}；修改非秘密配置 | ServiceInput、If-Match、Idempotency-Key → Service |
| PUT /api/v1/admin/official-services/{serviceId}/credential；替换Key | providerKey必填非空字符串、If-Match、Idempotency-Key → credentialConfigured/revision |
| POST /api/v1/admin/official-services/{serviceId}/checks；本地配置检查 | {}、If-Match、Idempotency-Key → checkedRevision/configurationValid/providerVerified=false/issues[] |
| POST /api/v1/admin/official-services/{serviceId}/status；启停 | status=ACTIVE/DISABLED、reason1～500、If-Match、Idempotency-Key → Service |

ServiceInput：name必填1～100；capability必填AVATAR_GENERATION/TTS；providerCode必填1～64且已实现适配器；endpoint必填固定HTTPS或官方WSS地址；modelId必填1～128；parameters必填白名单JSON；secretId可省略沿用既有引用，创建需有效引用或随后单独配置，不接受null清空有效凭证。Service返回serviceId、上述非秘密字段、status/revision/credentialConfigured；secretId仅管理员可见，ciphertext/Key永不返回。凭证输入不上日志/浏览器正文缓存，secret摘要用keyed digest。

目标 POST /internal/v1/official-services/{serviceId}/resolve（I，system）：请求expectedServiceRevision、purpose及taskId或voiceVersionId，内部验证真实绑定；返回受保护endpoint/model/parameters/credential或禁用错误。仅所需后端可读，不放公开DTO/调用日志。媒体现有凭证注入先兼容，不能将其静默当成另一服务凭证。

最小示例：POST保存请求 {"name":"官方语音","capability":"TTS","providerCode":"DASHSCOPE_BEIJING","endpoint":"wss://dashscope.aliyuncs.com/api-ws/v1/realtime","modelId":"qwen3-tts-flash-realtime","parameters":{},"secretId":"1001"}；成功data为 {"serviceId":"2001","status":"DISABLED","revision":"1","credentialConfigured":true}。示例不要求真实发送。

数据：复用p_official_service与p_secret，数据库6.3/6.4约束；新增菜单/必要审计字段追加迁移，不修改V1～已有迁移。服务创建/修订、审计和幂等同事务；凭证加密失败回滚。DISABLED→ACTIVE需有效配置；ACTIVE→DISABLED立即禁止新调用；修订不覆盖已冻结版本。无图像/COS产物。

不变量：S1秘密不出后端；S2选择serviceId与实际调用服务一致；S3旧快照不漂移、实时禁用优先；S4检查不触发收费。异常遵C5；秘密无法解密则阻断，不能回退任意全局Key。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整行为/落点 | 不变量/静态退出 | 下游 |
|---|---|---|---|---|
| S-A | C2 | 管理列表/编辑/安全凭证维护→查询消费；新增system官方服务Controller/Service及Vue页面，复用表和权限 | S1/S2；DTO/查询/密文来源及前端均接通 | 声音创建 |
| S-B | S-A | 配置检查→启停→旧版本与进行中调用约束；适配媒体/官方TTS解析 | S2～S4；所有发送路径查当前有效状态，必要离线Java/Vue检查 | 试听/制作选择器 |
| S-C | S-B、生命周期撤销 | 停用→在用影响→运行时拒绝与安全错误 | S3；事件生产消费和失败关闭均有实现 | 用户贯通 |

## 8. 主代理静态逻辑验证

| 要求 | 生产方→消费方 | 当前结论/辅助检查 | 待体验 |
|---|---|---|---|
| S1 | credential DTO→p_secret→内部resolve→适配器 | 证据不足；编码后逐字段查日志、响应和request缓存 | 管理员页面保存后不回显Key |
| S2/S3 | service管理SQL→AssetService/Voice快照→实际POST/WSS | 证据不足；不能以保存成功代替读取配置 | 已有模型不被改换，禁用后拒绝 |
| S4 | checks控制器→验证器 | 未实现；查无收费调用分支，离线编译仅辅助 | 页面明确检查范围 |

## 9. 用户终点验收与修复

准备管理员/普通用户、既有服务和秘密配置；不重复索要COS或厂商Key。用户：查看原配置→保存无关显示名称→检查→启用→在声音选择器看到→普通用户直接访问管理接口被拒绝→停用后选择器及新调用受限。真实TTS沿播报计划一次授权验收复用，不额外生成图片。失败由主代理修复相关链路并静态复验，再由用户复验受影响操作。真实结果未执行；提交安排见C1。

## 10. 跨模块交接与项目贯通

输出Service与内部解析给[官方声音](2026-09-19-admin-official-voices.md)，输出状态变更给[生命周期](2026-09-19-admin-public-asset-lifecycle.md)/播报。原制作只消费同一可用服务查询，不重做制作；菜单完成不代表实际TTS读取正确。最终由用户从管理保存到平台内真实试听证明接通。

## 11. 执行记录（2026-09-21）

- [x] S-A：新增 `official-services` 管理 API、菜单与 Vue 页面；分页/权限、ETag、非秘密 DTO、适配器地址/模型/参数白名单及凭证配置状态已接通。新凭证以部署主密钥 AES-GCM 加密写入 `p_secret`，响应、页面和日志路径均不回显明文。
- [x] S-B：新增仅内部可访问的服务解析接口，逐次复核服务 ACTIVE、冻结修订、任务或 Voice 版本绑定及可解密凭证。制作 Worker 在提交前按任务快照解析图像服务；官方 TTS 在每个提交前解析固定服务修订，移除对全局 `DASHSCOPE_API_KEY` 的调用依赖。
- [x] S-C：停用后的新制作领取/提交和新 TTS 提交均失败关闭；已冻结任务/Voice 只匹配原服务修订，不会静默换模型或回退任意全局 Key。新增菜单迁移和 session ticket 服务修订迁移已落库文件，未执行。
- [x] 静态检查：`mvn -B -ntp -pl ruoyi-modules/ruoyi-system -am compile -DskipTests`、`mvn -B -ntp -pl ruoyi-modules/ruoyi-session -am compile -DskipTests`、Vue `npm run typecheck`、`PYTHONPATH=src python -m pytest -q tests/test_worker_lease.py`（2 passed）、`python -m compileall -q src`、相关 Mapper XML 解析及 `git diff --check` 均通过。
- [ ] 用户终点验收：按第9节验证管理员保存/检查/启停、普通用户 403、停用阻断新调用及一次已授权真实 TTS；本轮未启动任何服务、数据库或浏览器，不将静态检查记为终点验收。

## 12. 2026-09-22 本地角色制作接入修复

- 用户创建的任务 `102089642576183338` 已入 Outbox 并由 Worker 领取，但没有生成 `p_generation_attempt`；其图像官方服务 `930010002` 当时为 `AVATAR_GENERATION` + `DASHSCOPE_BEIJING`，Worker 在真实厂商调用前拒绝该 TTS 适配器。旧任务已耗尽本地重试并进入死信，不自动重放、不切换冻结修订。
- 管理页面现展示适配器并支持编辑现有服务；编辑保留原地址、模型、参数和凭证引用，只对错误适配器按能力修正。服务解析再次校验存储配置，制作服务列表和提交查询不再把错误图像适配器当作可用服务。
- 经现有管理员接口将本地服务 `930010002` 修正为 `DASHSCOPE_IMAGE`，修订从 3 升为 4，仍为 ACTIVE，原凭证引用不变。新后端重启后页面“检查”通过（只验证本地配置/凭证可解析，不调用厂商）；Avatar 制作页可选该服务。Vue typecheck、system Maven compile/package、Python compileall、启动健康检查和媒体 Worker 心跳均通过；媒体虚拟环境未装 pytest，未运行其定向测试。
- 随后用户创建任务 `102089642576183351`；Worker 日志及厂商回执显示实际 HTTP 200、异步任务成功，用户的千问监控也显示请求。首次核对时已有 7 个动作候选，“挥手”步骤显示 `REFERENCE_UNAVAILABLE`；该中间状态及后续恢复见下方记录。
- 本地验收中候选“预览”出现空白：已生成 COS 图集经只读签名 GET 返回 200、PNG 首帧有内容，但 COS 未返回 `Access-Control-Allow-Origin`；前端 `ActionPreview` 原先强制 `crossOrigin=anonymous` 导致画布不绘制。去除该不必要限制、增加加载失败提示，并规范化 `loop` 数值布尔入参；Vue typecheck 通过，现有候选在本地页面重新点击“预览”已显示画面。此处只验证预览，不等于整套制作/发布验收。
- 同任务“挥手”已提交千问并保存厂商任务 ID 后，Worker 的每次领取仍重复读取 COS 参考图；一次短暂读取失败被错误当成提交前故障，旧 SQL 仅按当前 lease epoch 排除已有 attempt，将 `attempt_no` 错进到 2，导致已付费任务与步骤脱钩。修复后恢复领取复用数据库原 `request_hash`，不读取参考图、不再提交新生成；preflight 释放与过期释放按整个 attempt_no 排除已有 attempt，并阻断同一步骤的旧 attempt 被误跳过后再次付费。Python 定向 4 个 unittest、system Maven compile/package、Mapper XML 解析通过。
- 用户明确授权后，仅将任务 `102089642576183351` 的“挥手”步骤在严格状态校验下重新关联原 attempt 1 并唤醒原事件；Worker 使用原千问任务 ID 查询/下载、加工、上传 COS 并回写，日志无新增生成提交。数据库核对任务为 `SUCCEEDED / REVIEW_REQUIRED`、八个步骤均 `SUCCEEDED`，“挥手”图集 `AVAILABLE`。页面八动作预览、确认采用、组装与发布仍由用户验收；旧任务未恢复。
- 八动作首次“确认采用”均被几何门禁拒绝。数据库与 COS 回读表明，八个 `touches_border_*` 均由单个角点的低 Alpha 抠图残留触发，人物主体未碰边；几何边界检测已改为仅统计 Alpha ≥ 128 的有效像素。Python 处理器聚焦测试 3 项通过；版本 `102089642576183351` 的八个图集按新规则复验均通过，QA 行集在严格守卫下修正为通过并保留原告警为 advisory，未调用厂商或重新生成。逐动作确认、组装和发布仍待用户页面验收。
- 官方 TTS 新建表单原先直接要求管理员手填 `p_secret` 内部 ID，用户无法从已脱敏的页面获知该值。页面已改为按服务名称选择“已保存凭证”，去重复用现有安全存储，唯一凭证时自动选中；不回显密钥，也不再暴露数据库标识的输入要求。Vue `npm run typecheck` 通过。
