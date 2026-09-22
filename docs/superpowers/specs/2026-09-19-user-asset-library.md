# 用户个人资产查看、版本采用与删除执行计划书

前置说明书：[编写与执行规范](../../../执行计划书编写与执行规范.md) · [项目整体说明书](../../../项目整体说明书.md) · [需求](../../../项目需求说明书.md) · [数据库](../../../数据库设计说明书.md) · [架构](../../../项目架构说明书.md) · [接口约定（本计划及共享契约）](platform-console-shared-contract.md)
共享契约：[平台内使用共享契约](platform-console-shared-contract.md)。
日期：2026-09-19。版本：0.1。确认状态：用户于 2026-09-22 以 `/goal` 确认并授权按本计划编码；运行时操作仍不在本轮授权范围。

## 1. 当前执行状态

| 项目 | 事实 |
|---|---|
| 阶段/版本 | U-A～U-C 静态实现完成；以 `40d9bd5` 为实施前基线。 |
| 已有能力 | 用户确认角色制作完成；已有角色详情/版本、公共列表、预览、发布、删除接口 |
| 已完成 | 独立“我的角色”目录、版本采用到应用配置、本人引用摘要、ETag/幂等删除申请与清理队列接线 |
| 静态/体验结论 | Controller→Service→Mapper→Vue 及应用配置跳转已走查；`vue-tsc` 和 system/session 离线编译通过。未启动服务或执行数据库迁移。 |
| 下一步 | 用户按第9节进行两账号与真实清理终点验收；不重新生成角色。 |

## 2. 目标、范围和执行边界

普通用户不必寻找制作任务ID，即可在“我的角色”查看自己的已有角色及版本、预览、在自己的应用中采用、看引用与删除未被使用的资产。公共角色只允许查看和引用，不能复制修改。官方声音目录链接到声音选择/应用调试；私有声音及其列表/编辑/删除本轮明确延期，旧数据不删除。范围不包含角色制作重写、复杂历史对比或视觉美化；C5静态边界适用。

## 3. 已确认决策与待决事项

| 决策 | 来源 | 结果 |
|---|---|---|
| 制作已完成 | 用户本轮前序明确确认 | 制作入口导航至现有页面，保留已验收行为 |
| 私有资产本人拥有 | 用户决定、FR-AV-06 | 用户A不能读B私有资产；管理员运维权限不改变可见性 |
| 采用版本是更新应用 | FR-AV-06、FR-APP-02 | 不通过修改资产current指针改变所有应用 |
| 引用时禁止删除 | C3 | 显示可理解引用与处理入口，不让用户手填SQL |

无新增业务待决项。新“我的角色”是已有资产的管理入口，不另造角色归属或第二套版本状态。

## 4. 现有代码链路与强制复用

| 实际源码/函数 | 当前行为 | 复用方式与差异 |
|---|---|---|
| [avatar/index.vue](../../../RuoYi-Cloud-Vue3/src/views/avatar/index.vue)→api/asset/avatar.ts | 任务列表、公共库、动作制作与预览 | 保留制作部分；新增独立资产目录/详情入口，不按任务数量分页资产 |
| AssetController.avatarDetail/previewAvatarVersion→AvatarPublicationService.detail/preview | 按权限读版本和签名资源 | 原样复用并核对归属，禁止绕过服务层直接签文件 |
| AvatarPublicationMapper.selectAccessibleAvatar/Versions | 角色和版本读取 | 补私有目录查询，不误用当前账号任务表当资产库 |
| deleteAvatar及C3删除内核 | 引用检查/标记删除已有 | 适配按钮、ETag与清理状态；最终清理由生命周期计划实现 |
| SDK及现有预览 | 完整制作预览已有 | 直接调用已有能力，不另写第三套播放器 |

## 5. 完整业务流程

| 步骤 | 用户操作/输入 | 处理/输出 | 反馈/失败 |
|---|---|---|---|
| 1 | 打开我的角色 | 按登录account_id分页查p_avatar | 无资产提示去现有制作页，不显示他人私有 |
| 2 | 点击详情/版本 | 返回状态、版本及可用操作，读原预览接口 | 草稿/制作中与可用版本分开 |
| 3 | 点击采用此版本 | 选择本人应用，将avatarVersionId传应用配置表单 | 展示将变更的应用，不自动发布 |
| 4 | 在应用页确认 | 应用计划校验并发布新config | 新会话采用，旧会话保持 |
| 5 | 查看引用 | C3返回本人应用名称及会话计数 | 提供关闭调试/修改应用入口 |
| 6 | 删除自己未引用角色 | If-Match/幂等申请→DELETING→可靠清理 | 有引用拒绝；失败显示待清理，可刷新，不能重复制作来解决 |

## 6. 接口、数据与状态契约

个人角色接口沿现有 /system/asset/** → system /asset/**、AjaxResult，以免重写制作。新接口为GET /system/asset/avatars（内部/asset/avatars）：C+system:asset:list，query pageNum/pageSize/status/keyword（keyword省略或1～100），服务端固定本人PRIVATE；response.data={items,total,pageNum,pageSize}。

新增GET /system/asset/avatars/{avatarId}/references（内部去/system）：C+system:asset:list，输出counts/applications摘要，仅本人资产，复用C3。已有详情、预览、DELETE路径不变；DELETE返回data.status=DELETING/DELETED并提供HTTP202语义，服务端补If-Match/Idempotency-Key；更新前端兼容数值外壳，不混用新API字符串OK。

| 字段 | 类型/来源/消费 |
|---|---|
| avatarId | ID必填，资产表主键→列表→详情路径；不得用taskId代替 |
| versionId/currentVersionId | ID或null，版本查询/当前指针→预览/应用选择；必须同avatar |
| visibility/status | 服务端枚举→展示与按钮；前端值不决定权限 |
| revision | 字符串→ETag→删除；由后端锁内校验 |
| applicationId | 用户从自己的应用列表选→跳转表单 | 应用后端重新校验所属，不信任路由参数 |
| previewUrl/expiresAt | 服务端签名→已有预览 | 过期重新读预览接口，不改桶为公开 |

例：GET /system/asset/avatars?pageNum=1&pageSize=20 → {"code":200,"data":{"items":[{"avatarId":"21001","name":"我的角色","visibility":"PRIVATE","status":"PUBLISHED","currentVersionId":"22001","revision":"3"}],"total":1,"pageNum":1,"pageSize":20}}。采用动作只携带 {"applicationId":"4001","avatarVersionId":"22001"} 到应用编辑页，不创建新制作任务；最终发布请求见应用计划。

数据复用p_avatar/version/action/file/reference。查询不产生新资产行；状态沿已有制作和C3，无额外“已采用”主状态，采用关系属于p_app_config。菜单/索引必要变更追加迁移。删除/引用事务C3；URL生成不持有跨网络长事务，缓存只保有期内地址不作永久权限。

不变量U1本人目录与公共目录分开；U2采用不重制不改旧Session；U3有引用不删且共享文件不误删；U4完整页不受最近100条任务上限影响。错误：无权403/404安全处理；删除中阻止再绑定；预览过期重新授权；新版本发布冲突交应用页处理。

## 7. 编码顺序与完整能力切片

| 切片 | 依赖 | 完整行为/落点 | 不变量与静态退出 | 下游 |
|---|---|---|---|---|
| U-A | 已完成角色接口 | 目录→详情→版本→原预览，新增Vue资产页/列表Mapper | U1/U4；按资产而非任务分页，所有查询带归属 | 应用选择器 |
| U-B | 应用A-A | 选版本→本人应用→确认发布→返回采用结果 | U2；不触发制作且版本ID传递无Number转换 | 播报 |
| U-C | 生命周期L-C | 引用提示→删除申请→清理结果 | U3；复用同事务/消费者，页面状态与数据库一致 | 用户资产管理终点 |

## 8. 主代理静态逻辑验证

| 不变量 | 两端路径 | 当前结论/辅助检查 | 用户体验 |
|---|---|---|---|
| U1/U4 | 资产列表Controller→Mapper→列表/预览 | 新列表未实现，证据不足；必要类型检查 | 两账号目录隔离，大量任务不影响资产可见 |
| U2 | 详情versionId→应用表单→配置事务 | 待接线；查无生成POST | 采用不收费、不覆盖旧版本 |
| U3 | references/delete→C3锁/文件清理 | 命令已有，消费端待补 | 引用提示准确，允许删除后真正完成 |

## 9. 用户终点验收与修复

准备用户A/B已有角色、公共角色、A的应用；不新生成。A从我的角色打开旧资产→预览→选择版本给应用→查看引用→引用中删除被拒→解除全部有效引用后申请删除→看到最终状态。B访问A私有详情/资源被拒；公共角色可查看但没有修改/删除权。只在用户选定可处置资产上验删除。失败修复当前管理链，避免重跑角色制作。当前未执行；提交遵C1。

## 10. 跨模块交接与项目贯通

[应用配置](2026-09-19-user-application-config.md)接收明确版本，[生命周期](2026-09-19-admin-public-asset-lifecycle.md)提供统一安全清理；官方声音目录由[声音计划](2026-09-19-admin-official-voices.md)维护。此计划不承接延期私有声音，也不能因能浏览角色就声称平台播报完成。

## 11. 执行记录

2026-09-22：完成 U-A～U-C 静态实现。`GET /system/asset/avatars` 固定为当前账号 PRIVATE 资产分页；详情、引用摘要和版本采用均复用既有角色/应用配置约束。删除要求 `If-Match` 与 `Idempotency-Key`，有引用时拒绝，成功后进入 `DELETING`、既有清理调度和 Outbox，不把物理清理前报为已删除。前端用新“我的角色”页进入应用配置，传递字符串版本 ID，不创建制作任务。

静态证据：`npm run typecheck`（RuoYi-Cloud-Vue3）通过；`mvn -B -ntp -pl ruoyi-modules/ruoyi-system,ruoyi-modules/ruoyi-session -am test -DskipTests` 通过；`git diff --check` 通过。未启动服务、浏览器、数据库或外部服务，用户终点验收仍未执行。
