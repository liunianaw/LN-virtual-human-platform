# 框架准备记录

编制日期：2026-09-08；更新日期：2026-09-11。

当前进度与跨会话接续统一见[项目整体说明书](../项目整体说明书.md)，协作规则见[AGENTS.md](../AGENTS.md)。本页保留准备过程的历史记录，较早的“未裁剪/未提交”等描述仅代表当时状态。2026-09-11核对远端：裁剪提交68a600d已通过PR #1合并main，合并提交804cbe9；M1运行验收仍待完成。

## 阶段结论

2026-09-10 仓库位置与若依历史处理：项目仓库现位于工作空间内的LN-virtual‑human‑platform子目录。已保留原有全部一级目录，创建ruoyi-media、avatar-sdk及规划中的二级目录骨架；根Git仓库使用main分支，origin为https://github.com/liunianaw/LN-virtual-human-platform.git。若依前后端源码由根仓库直接跟踪；负责人确认后续直接修改且不再同步若依上游，两个若依目录原.git元数据及临时备份已经删除。尚未裁剪若依模块，也未创建业务构建文件或正式代码。

2026-09-10 布局规划记录：负责人已将若依后端、前端放在根目录的RuoYi-Cloud与RuoYi-Cloud-Vue3，要求现有一级目录全部保留。架构V1.4与阶段方案V0.2（旧稿已清理，可查Git历史）已按此修订；规划新增ruoyi-media、avatar-sdk，Java模块仍在RuoYi-Cloud内部，原来的根目录ruoyi-ui/展开若依模块方案被覆盖。当时两个若依工作区无源码修改，各自.git保留；该条为初始化前的历史记录。下列较早条目同为历史记录。

2026-09-09 若依主体修订：正式工程以已拉取RuoYi-Cloud的原模块结构为基础，Vue3源码引入ruoyi-ui；ruoyi-system直接承载平台业务，新增ruoyi-session、ruoyi-media和avatar-sdk。取代此前apps/services/packages布局建议。第一阶段包括骨架、形象制作发布与Voice配置试听，不做声音克隆；当时的初始化与验收方案待审阅（旧稿已清理，可查Git历史），裁剪在正式编码前确认。架构稿已更新V1.3。

2026-09-09 文档一致性修订：需求 V1.3、架构 V1.2、核心接口 V0.2、数据库 V0.2 与结构化字段字典已同步。明确 B/S 权限、调试签发来源、会话 HTTP 幂等、三类运行状态、WSS首帧认证、Webhook签名及留存规则；新增业务表48张、字段716个。制作路线验收保持有效，下一步细化工程实施计划并验证骨架构建；尚未初始化正式业务仓库或执行数据库迁移。

2026-09-09：已编写[接口设计说明书01](../接口设计说明书01.md)，结合需求、架构、数据库及本地源码规划核心接口。包含制作发布、应用配置、会话授权、实时事件、转接和SDK契约；本轮未修改上游/验证业务代码、未建表或调用收费服务。

2026-09-09：已进入数据库设计，字段与关系方案见[数据库设计说明书](../数据库设计说明书.md)。本阶段只交付设计，不表示数据库已经初始化。

负责人已手动验收虚拟人制作路线，反馈单动作单次 API 请求约 0.4 元；提示词仍需细化。这是进入工程搭建的依据，不代表平台业务、所有素材质量指标或性能测试已完成。

## 已拉取源码

当前源码位置：Java后端在RuoYi-Cloud/，Vue3后台在RuoYi-Cloud-Vue3/，直接作为改造主体；upstream/仅保留LiveTalking、MuseTalk、TalkingHead三个参考项目。参考项目保留各仓库历史与LICENSE；若依源码按负责人要求已去除嵌套Git，保留LICENSE和本页来源SHA。后端构建及模块组织均从RuoYi-Cloud/pom.xml出发，不把模块移动到项目根目录。

| 用途 | 官方仓库 | 分支 | 当前提交 |
|---|---|---|---|
| Java 微服务基础 | https://github.com/yangzongzhuan/RuoYi-Cloud | springboot3 | 4d93505f7f87ebd358c56c766bdec59be01f74c6 |
| Vue 3 / TypeScript 后台 | https://github.com/yangzongzhuan/RuoYi-Cloud-Vue3 | typescript | 52a100c8439951dfb860809f3cce48a0752a8c6a |

均为浅克隆，LICENSE 为 MIT，已核对工作区无修改。拉取时未验证构建；当前构建结果见裁剪记录。登录联调尚未验证，因此这些SHA是上游源码基线，不是生产验收版本。

后端 POM 声明：RuoYi 3.6.8、Java 17、Spring Boot 3.5.16、Spring Cloud 2025.0.2、Spring Cloud Alibaba 2025.0.0.0。

前端 package.json 声明：RuoYi 3.6.8、Vue 3.5.26、TypeScript 5.6.3、Vite 6.4.1、Element Plus 2.13.1、Pinia 3.0.4。本轮已生成package-lock.json并完成构建，详见裁剪记录。

## 其他开源组件如何引入

| 组件 | 引入方式与时机 |
|---|---|
| FastAPI / Uvicorn / Pydantic / aio-pika / Pillow | media-service 建立时通过 Python 依赖文件锁定安装，复用现有 validation 处理代码 |
| Flyway / Spring AMQP / WebClient | Java 服务搭建时由 Maven 管理 |
| VitePress / html2canvas | 文档站、页面采集模块建立时由 npm 引入；html2canvas 仍是待验证候选 |
| MySQL / Redis / RabbitMQ / Nacos / Nginx | Compose 配置中核对并固定镜像版本；本轮尚未拉取镜像 |
| FFmpeg | 媒体处理需要时安装匹配平台的构建，记录版本及构建许可 |
| MuseTalk / LiveTalking / TalkingHead | 已拉取至 upstream，按模块抽取或参考无模型能力；详见 opensource-reuse.md，不整包部署推理运行时 |

## 下一实施顺序

以下为旧第一阶段方案的历史摘要，不再驱动当前执行；当前顺序与验收标准从[项目整体说明书](../项目整体说明书.md)进入对应执行计划：

1. 审阅布局、初始化顺序和验收标准；按负责人要求在正式编码前确认若依模块裁剪。
2. 在现有RuoYi-Cloud和RuoYi-Cloud-Vue3中建立正式基线，验证构建及版本，补齐配置与启动入口。
3. 基础设施与两库迁移 → 后台登录与权限 → 所有计划服务的基础入口，验收工程骨架M1。
4. 上传与官方生成配置 → 任务/额度/可靠消息 → ruoyi-media生成加工 → 后台预览及Avatar验收发布。
5. 独立Voice配置、官方及第三方TTS → 最小SPEAK_ONLY调试 → 声音与speaking动作、停止。
6. 真实业务演示及重复提交、故障恢复、越权和清理验收，完成M2。

现有validation保留供手动验证；开源功能按实际需求移植到若依及新增模块，详细来源见开源复用清单。2026-09-11：根仓库已初始化，模块裁剪已执行；未进行付费调用。构建与配置记录见[若依裁剪记录](ruoyi-module-trimming.md)。
