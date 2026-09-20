# 管理员官方服务配置与管理执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.1。确认状态：范围依据用户本轮决定，具体计划待确认；本轮只编写文档，不授权编码或运行时操作。

## 1. 当前执行状态

| 项目 | 当前事实与证据 |
|---|---|
| 阶段/基线 | 计划讨论稿；实施前以 2026-09-21 当前源码和 `41c85b8`、`d6f8b7b` 为基线复核。 |
| 已有能力 | 图像服务查询及任务快照；官方TTS适配器；没有完整服务管理页面/CRUD |
| 静态核对 | 已盘点以下调用点；新管理与动态配置链路未实现，结论为证据不足 |
| 用户验收 | 本计划未执行；已完成角色制作不重验 |
| 剩余/下一步 | 确认计划后按切片实现；本轮无代码/配置发布/收费调用 |

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
