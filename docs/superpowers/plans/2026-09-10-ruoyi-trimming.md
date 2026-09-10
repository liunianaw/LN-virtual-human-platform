# 若依模块裁剪实施计划

**Goal:** 在现有若依源码上执行已确认的裁剪，保留 ruoyi-job，并以腾讯云 COS 替代独立文件服务。

**Architecture:** gateway/auth/system/job 为现有默认 Java 服务；gen、monitor 通过构建及部署 profile 按需启用。保留组织兼容表与内部 Mapper，但关闭部门、岗位及部门授权入口；system 内直接使用 COS 存储适配器。session/media/SDK 的正式业务实现属于后续第一阶段工作。

**Tech Stack:** Java 17、Spring Cloud、Vue3/TypeScript、Druid 单数据源、腾讯云 COS Java SDK。

**Spec:** 本轮已确认的裁剪清单；用户修订为保留 ruoyi-job，对象存储为腾讯云 COS。

## 约束

- 保留所有现有一级目录，使用当前仓库目录完成改造；保留 validation 及其本地配置，不调用收费模型和云存储。
- 不改变已有账号/菜单权限体系，不把部门数据权限作为后续账号隔离机制。
- 本轮完成裁剪与已有功能关联修复；邮箱认证、业务文件元数据/Flyway基线、Avatar制作与声音业务按第一阶段计划继续实现，不在裁剪中声称已完成。
- 工程原始 SQL 保留出处，新准备脚本区分运行配置、工具配置与旧库裁剪，不对现有数据库自动执行 SQL。

## 执行清单

- [x] 记录原工程 Maven/Vue 构建结果，固定 Windows Unicode 路径所需的 JVM 编码设置。
- [x] 修改根/common/modules POM：删除 Seata；保留 job；gen/monitor 分别进入 devtools/monitoring profile；保留连接池，移除动态数据源及主从注解。
- [x] 给文件校验、对象键归属、COS 适配和操作审计增加行为回归检查；日志默认采集行为先失败后通过，存储和COS适配最终回归通过。
- [x] 迁移文件能力到 system 的 storage 包，接 COS 官方 SDK；个人头像改为直接调用本地服务，保存对象键并按需生成短期读链接；移除 file 服务和 Feign 关联。
- [x] 关闭部门、岗位及角色部门授权端点；清理用户/角色/个人中心的组织关联；保留必要内部兼容服务及表。
- [x] 移除公告及前端顶部公告请求、表单构建和推广入口；保留注册页面、job 页面、公共组件；gen 入口仅开发工具模式可用；替换首页。
- [x] 同步 Nacos 配置模板、Compose profile、复制/启动脚本、菜单裁剪 SQL；保留 Quartz/job 相关配置与表。
- [x] 执行默认及可选模块 Maven 构建、Vue 生产构建/类型检查、存储与裁剪相关测试、残留引用检查；修复本轮导致的问题。
- [x] 更新架构、数据库和第一阶段说明，记录保留 job、COS 选型与实际验收边界。

## 验证命令

```powershell
Set-Location RuoYi-Cloud
mvn -B -ntp verify
mvn -B -ntp -Pdevtools,monitoring verify
Set-Location ../RuoYi-Cloud-Vue3
npm ci
npm run build:prod
npx vue-tsc --noEmit
```

确认删除入口不存在、job 构建与前端路由保留、可选工具不进入默认服务集。未配置真实 MySQL/Redis/Nacos/COS 时，只报告构建及受控回归结果，不报告登录或云存储联调已通过。

## 执行结果（2026-09-11）

- 默认 Maven clean verify：17模块成功；最后一次 verify成功。可选 -Pdevtools,monitoring clean verify：20模块成功。
- 后端7项回归通过（日志1、头像4、COS适配2）；菜单2项通过，vue-tsc通过。
- 前端devtools/default构建成功；连续切换实测devtools包含生成器、default不含生成器。补上Windows Unicode路径下Node24的dist逐项清理。
- YAML/POM静态解析、Compose profile与网关文档路径检查通过；菜单SQL在SQLite兼容测试中重复执行结果一致，job/user菜单未变化。这不是MySQL实库执行结果。
- 默认jar检查确认不含已移除Controller、旧文件Feign及旧服务发现文档注册器；job入口保留。git diff --check通过。
- 自动审批拒绝清理旧file/Seata生成缓存的批量删除操作，未跟踪target缓存暂留；源码及构建引用已移除。没有继续执行该删除。
- 未运行真实基础设施、登录、COS联调或SQL；没有提交/推送。
