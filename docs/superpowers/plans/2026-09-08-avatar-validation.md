# Avatar 制作验证工具 Implementation Plan

**Goal:** 无 Key 时完成离线可测的验证工具，配置 Key 后逐项生成、人工验收并预览。
**Architecture:** Python CLI 直接调用已选千问 API，SQLite 仅作为本地试验预算账本；Vue 3 + TypeScript 验收播放器通过用户选择的素材文件工作，无浏览器密钥。不是平台生产数据库或正式 SDK。
**Tech Stack:** Python 标准库 + Pillow；Vue 3、Vite、TypeScript；unittest、Node 测试。
**Spec:** 根目录《制作验证方案.md》。按用户要求在当前新工作区直接实现。

## Constraints

- 模型固定 qwen-image-2.0-pro-2026-04-22，北京地域，20 元累计上限，单图 50 分。
- 同步 HTTP 单图调用，不自动重试；未知结果保留预占且阻止新生成。
- 不部署任何模型；真实素材和费用报告不以测试替身冒充。
- 原创基准图通过后才生成动作；三个代表动作通过后补齐其他动作。
- Key 仅本地 config.local.json 或环境变量；不得由页面提供或返回到页面。

## Implementation tasks

1. `validation/tests/test_workflow.py`：预算持久化、超时、重复请求、并发预占、参考图门禁、错误回复、素材篡改阻止测试；先执行确认缺失模块失败。
2. `validation/avatar_lab/{ledger,provider,workflow,prompts}.py`：实现 `Ledger.reserve(job)`、`Workflow.generate(job)`、`Workflow.accept(id)`，预算以整数分存储，所有生成通过同一全局账本。
3. `validation/tests/test_media.py` 与 `avatar_lab/media.py`：测试有色边缘、透明像素、错误尺寸、空格子及完全相同帧；实现 `package(source, target, action)`，生成 RGBA 帧、联系表、GIF 和待人工复核的 manifest。
4. `validation/avatar_lab/__main__.py`：提供 doctor、generate、accept、package、status、resolve；文档给出准确执行顺序。所有付费入口都受预算保护。
5. `validation/preview`：Vue 文件选择器加载 manifest 与帧，Canvas 单时钟播放；`player.mjs` 的状态机测试覆盖音频暂停/结束、单次动作结束、停止清队列。
6. 验证 Python 全部测试、前端构建与播放状态测试、CLI 无 Key 行为；进行实际浏览器空状态检查。真实模型调用与视觉效果保持未测状态。

## Checks

2026-09-08进度：代码任务1–6已实现；Python26项、播放状态4项、Vue类型检查与构建通过。全身基准图成功，待机三次尝试（一笔异常保守保留预算、两张布局错误）未通过，按预定上限停止；真实八动作验收未完成。最新构图改为全身，参考需求V1.2。

运行 `python -m unittest discover -s tests -v`（validation 目录）。
运行 `npm test` 与 `npm run build`（validation/preview 目录）。
交付 `validation/README.md`，根方案更新代码与验证状态。
