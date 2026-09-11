# 若依模块裁剪记录

日期：2026-09-11。执行分支：chore/ruoyi-module-trimming。本轮使用原若依工程修改，保留全部一级目录；不创建第二套Java或Vue主框架。

后续Git状态补记：2026-09-11通过Git和GitHub CLI核对，裁剪提交68a600d已推送并通过PR #1合并main，合并提交804cbe9。下文“本轮未提交或推送”是裁剪执行结束时的历史边界；当前进度统一见[工作交接](handoff/README.md)。

## 已落实的范围

| 部分 | 当前处理 |
| --- | --- |
| gateway/auth/system/job | 默认构建、默认部署；保留登录、角色菜单、字典、参数、日志、在线用户和定时任务 |
| gen | 源码保留，Maven devtools profile、Compose devtools profile、前端devtools模式按需启用 |
| monitor | 源码保留，Maven monitoring profile、Compose monitoring profile按需启用 |
| file | 独立服务及Feign接口、部署目录、启动入口移除；已有账号头像上传由system直接调用COS适配 |
| Seata、动态数据源 | 移除Seata模块与依赖；保留Druid连接池，改为单数据源配置 |
| 部门、岗位 | 删除对外Controller、页面和API；保留内部Service/Mapper及兼容表；新建/导入用户不接受组织字段，已有角色数据范围保留 |
| 公告、表单构建、推广页 | 删除业务源码/顶部请求与对应页面，首页替换为管理快捷入口 |
| 通用组件 | 保留若依权限、布局、表格、上传、编辑器等；通用上传必须显式指定action，编辑器未配置时禁止上传 |
| 操作审计 | 默认不收集请求体/响应体，仍记录操作行为；尚未完成全部运行日志的内容治理 |

旧库菜单停用脚本为 RuoYi-Cloud/sql/trim-existing-framework.sql，需显式执行并重新登录；不会自动清除现有业务数据。前端也过滤已移除页面与默认关闭的生成器，避免旧菜单造成失效入口。源SQL保留作为参考，正式迁移不直接重放源SQL。

COS仅完成账号头像适配：校验真实PNG/JPEG内容、尺寸和大小，规范化图片，保存账号所属对象键，返回短期签名地址；跨账号对象不能签名/删除。未配置COS不会阻止登录，但不能上传头像。旧公网头像URL不会被当成可操作对象。配置见[运行模板说明](../RuoYi-Cloud/config/nacos/README.md)。

正式业务文件上传、p_file文件账本、异常遗留对象清理和Avatar素材权限不在本轮完成范围。当前账号头像DB写入异常时可能留下对象，正式文件账本接管时需补偿；不把账号头像接口当成通用素材上传API。

## 构建与启用

在 RuoYi-Cloud 下：

    mvn -B -ntp clean verify
    mvn -B -ntp -Pdevtools,monitoring clean verify

Windows路径适配：Maven通过.mvn/jvm.config固定UTF-8；当前Node24在本仓库特殊连字符路径下递归清理未生效，npm各build命令增加仅针对dist的逐项清理，避免旧页面产物混入发布目录。

默认四个Java入口：RuoYiGatewayApplication、RuoYiAuthApplication、RuoYiSystemApplication、RuoYiJobApplication。后续新增session后共五个Java服务。job保留原有调度行为：任务定义与日志写sys_job/sys_job_log，内存Quartz在启动时恢复任务；Quartz JDBC schema留存但没有自动启用。

在 RuoYi-Cloud-Vue3 下（Node24可运行当前测试）：

    npm ci
    npm test
    npm run typecheck
    npm run build:prod
    npm run dev

开发工具前端使用 npm run dev:tools 或 npm run build:devtools。后端、网关模板、工具库及菜单权限也必须按配置说明同时启用；前端开关不替代后端鉴权。

Docker脚本使用 Compose v2；先构建，再在docker目录运行 sh copy.sh，仅复制默认jar和前端。可附加devtools、monitoring复制对应jar。sh deploy.sh modules包括job；可选模块分别使用同名参数启动。脚本不自动导入旧SQL。Windows下在各模块运行对应jar或现有bin脚本。

## 验收边界

本轮验证默认/可选Maven构建、Vue类型/生产构建、菜单过滤、头像内容/归属和COS调用适配回归。验证结果：

- 默认Maven构建17模块成功；启用devtools、monitoring共20模块成功。
- 后端7项测试通过，前端2项菜单测试通过；vue-tsc类型检查通过。
- 前端生产和开发工具构建成功，连续切换后确认默认dist不含生成器产物。
- YAML/POM静态解析、Compose可选模块和Swagger路由映射检查通过。
- 菜单调整SQL在内存SQLite兼容测试中重复执行结果一致，job及用户权限未被停用；尚未对MySQL实库执行。
- 当前system/gateway的jar不含已移除类，job入口保留；git diff --check通过。

自动审批拦截了旧file/Seata生成缓存的批量删除操作，仅留下未跟踪target及空目录，不再属于Maven模块，也不被Docker脚本复制。源码删除已落实。

尚未验证：真实MySQL/Redis/Nacos登录、菜单运行权限、COS上传预览及容器启动。本机未发现Docker命令，Compose仅作静态结构检查。本轮没有读写真实云存储、运行付费模型、执行SQL、提交或推送Git。

下一步是M1：正式数据库迁移基线、基础设施及环境配置、默认服务启动和后台登录/菜单权限联调。之后再接上传参考图、制作任务、加工、预览与Avatar发布，声音仅配置/试听，不做克隆。
