# 虚拟人制作验证工具

更新日期：2026-09-21。

> **用途边界**：此目录是独立的素材与提示词验证工具，不是正式平台业务入口。平台当前实现和验收以[项目整体说明书](../项目整体说明书.md)及对应执行计划为准；历史试验过程已移出工作树，可按 Git 查询。

> **当前手动测试模式**：`generate` 默认跳过待核对请求、三次尝试、20元预算、已验收项目重复生成、已收费未下载和代表动作验收顺序的拦截。直接运行 `python -m avatar_lab generate wave`（或 `idle` 等动作）；自有图片使用 `--reference 图片路径`，否则使用已验收基准图。每次只发送一次请求，不自动重试；历史账本与图片保留。追加 `--strict` 才恢复原限制。账本金额是本地估算，不是厂商实际账单。

本目录用于验证千问图像 API → 全身透明六帧素材 → Vue 播放器。未部署模型，不是完整平台。Key 未配置时，检查、测试和前端预览可以独立运行。

当前代码在 [avatar_lab/prompts.py](avatar_lab/prompts.py) 的 `MODEL` 中使用 `qwen-image-3.0-pro`，并非从 `config.local.json` 选择模型。费用、模型可用性和账号权限均以调用前厂商控制台与明确授权为准，不能从本地账本、脚本默认值或本文件推断。

## 1. 配置 API Key

在此目录的 PowerShell 中运行：

```powershell
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\validation'
.\configure-key.ps1
```

根据本地提示输入 Key，不要把 Key 发到聊天。脚本将其保存到 `config.local.json`，已加入忽略规则；这是本机明文配置文件，勿分享。可直接复制 `config.example.json` 为 `config.local.json` 并在编辑器填入。环境变量 `DASHSCOPE_API_KEY` 优先于文件；配置文件不会送到前端。

申请北京地域的百炼 API Key，并确认所选模型已开通。如使用业务空间专属地址，把 `api_host` 改为控制台提供的 `https://你的业务空间ID.cn-beijing.maas.aliyuncs.com`。配置中不含 `/api/v1` 等路径，程序自动补齐接口路径；不接收第三方转发域名。

## 2. Python 环境

常规方式（Python 3.12 或更新版本）：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$labPython = (Resolve-Path '.\.venv\Scripts\python.exe').Path
& $labPython -m avatar_lab doctor
```

本机已使用 Codex 自带 Python/Pillow 通过离线验证，也可直接使用以下路径，无需重复安装：

```powershell
$labPython = 'C:\Users\29042\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $labPython -m avatar_lab doctor
```

`doctor` 仅检查本地 Key 格式和域名，不进行远程调用；通过不代表模型权限、余额或网络已经通过验证。

## 3. 逐阶段生成

### 手动生成一张独立形象

使用 `portrait` 尝试新的外观，它不覆盖动作参考图。编辑 `portrait-prompt.txt`（UTF-8）描述角色性别、服装、风格等，程序补充全身构图与纯色背景要求。默认手动模式不拦截预算与次数；追加 `--strict` 才按旧实验的累计20元、独立形象最多3次及基准图验收规则执行。

```powershell
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\validation'
$labPython = 'C:\Users\29042\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $labPython -X utf8 -m avatar_lab doctor
notepad.exe .\portrait-prompt.txt
# 保存提示词后执行下面这一条，才会实际调用付费模型：
& $labPython -X utf8 -m avatar_lab generate portrait --prompt-file .\portrait-prompt.txt
& $labPython -X utf8 -m avatar_lab status
```

成功时终端输出原图绝对路径（`workspace/jobs/记录ID/source.png`）。这是带纯色背景的静态全身图，尚不是透明动画；可用图片查看器直接打开。省略 `--prompt-file` 会使用内置讲解员描述。该入口尚未提供网页表单或上传参考照片功能。出现结果未知时先核对记录；下载失败使用 `recover`，不重复生成。Key 自动从现有本地配置读取，无需启动 Vue。

### 原样品的基准图与动作流程

使用自己的图片生成动作，在命令后指定路径（无需覆盖旧基准图）：

```powershell
& $labPython -X utf8 -m avatar_lab generate wave --reference 'D:\图片\我的角色.png'
```

支持PNG、JPG/JPEG、WebP，源文件不超过10MB。自动校正照片方向、等比适配并补留白，不裁剪或修改源图。用于本次请求的参考副本保存在任务目录的 `reference-input.png`；`recipe.json`记录该副本哈希。建议使用单人全身图，背景简单、手脚完整；半身或头像图缺少的服装和身体只能由模型推测补全。此步骤不会自动去除参考照片原有背景。省略参数继续使用此前已验收的基准角色，打包与播放命令不变。

每次 `generate` 只发送一个单图请求。不会自动生成整套，也不会自动重试。

```powershell
# 第一笔付费调用，生成基准角色
& $labPython -m avatar_lab generate base
& $labPython -m avatar_lab status
```

输出给出记录 ID 和原图路径。查看原图，确认脸、手、构图、服装及背景；确实通过后，以实际 ID 替换下例中的“记录ID”：

```powershell
& $labPython -m avatar_lab accept 记录ID --note '已查看基准图，人物和构图通过'
& $labPython -m avatar_lab generate idle
```

每个动作生成后：

```powershell
& $labPython -m avatar_lab package 动作记录ID
# 在预览页导入输出 package 目录下的 manifest.json 和六个 PNG
# 查看 contact-sheet.jpg、preview.gif 和实际播放后再验收
& $labPython -m avatar_lab accept 动作记录ID --note '逐帧与深浅背景检查通过，首尾过渡可接受'
```

随后可按同样方式生成 `wave`、`speaking`、`listening`、`thinking`、`nod`、`shake_head`、`happy`。严格模式要求先验收 idle/wave/speaking 三个代表动作；默认手动模式不拦截此顺序。

不合格素材不执行 `accept`；重新 `generate` 会产生新请求和新记录，每次均可能收费。严格模式才限制每项最多三次及已验收项目重复生成。保留历史账本和图片，通过新记录比较提示词效果。

这里的 CLI 一次生成、加工一个动作，输出六张 PNG 与单动作 manifest。正式平台一次制作任务生成八动作整套版本，并转换为接口01定义的图集/manifest；该转换和平台任务编排尚待实现，不能直接将本工具目录视为正式平台资产包。

## 4. 启动 Vue 预览页

```powershell
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\validation\preview'
npm.cmd ci --registry=https://registry.npmjs.org --cache=.npm-cache
npm.cmd run dev
```

打开 http://127.0.0.1:5173/，停止服务按 `Ctrl+C`。本机本轮已安装依赖，可直接运行最后一条。锁文件固定实际依赖版本。

- 每次选择一个动作的 `manifest.json` 和六张 PNG，可以依次导入多个动作。
- 浏览器检查尺寸、帧数和 SHA-256，素材不上传；刷新后需重新导入。
- 切换深色、浅色、棋盘背景；暂停和下一帧用于检查边缘与形体。
- 单次动作结束回待机，需先导入 idle；循环动作持续播放。
- 导入 idle 和 speaking 后，选择本地音频并点击“播放并测试”。音频实际播放触发 speaking；暂停/缓冲退出 speaking，结束时执行排队动作。
- “停止”清空队列并停止音频。没有本地音频不影响动作预览；这里尚未接入 TTS。

## 5. 超时、下载失败和账单核对

3.0适配修正：已兼容 `usage.output_image_count`，同时支持2.0的 `usage.image_count`。新请求在解析前把响应保存到本任务的 `provider-response.local.json`，其中可能有临时图片链接，仅保留本机，不公开上传。终端错误会显示HTTP状态、请求ID及可用的固定解析原因；旧请求未保存的响应无法由此补回。

`workspace/ledger.db` 是本地 SQLite 试验账本，与正式平台 MySQL 选型无关。它持久保存请求状态和本地费用估算，不按重启重置；并发预算拦截只在严格模式生效。既有预占/记账常量不能据此认作 3.0 模型最新价格，也不回填为此次实测的0.4元。

| 状态 | 含义与处理 |
|---|---|
| reserved | 请求可能执行中，或进程在完成前中断；严格模式阻止新生成，手动模式保留状态但不拦截 |
| unknown | 上游结果不明确，保留账本原有预占；严格模式阻止新生成，手动模式不自动改写核对结论 |
| charged | 已按成功输出或人工核对入账，金额沿用本地估算，实际计费仍以厂商账单为准 |
| rejected | 明确的生成前错误或人工确认无费用，释放预占 |
| retained | 负责人核对后仍无法确认的结果，保留最坏费用占用，允许继续；不声明实际计费 |

生成成功但下载失败时，不重新调用模型：

```powershell
& $labPython -m avatar_lab recover 记录ID
```

此命令只下载已生成图片。上游结果 URL 有效期有限，过期不会自动生成新图。签名 URL 仅保存在忽略的 `receipt.local.json`，不出现在常规状态输出。

未知请求经控制台确认后，用真实请求标识和核对说明处理：

```powershell
& $labPython -m avatar_lab resolve 记录ID --state rejected --request-id 厂商请求ID --note '已在控制台核对，该请求未生成且无费用'
# 已确认输出和计费则使用 --state charged
```

人工确认 charged 后，如果缺少原始响应 URL，严格模式仍阻止同项新生成；默认手动模式不拦截。需要恢复素材时优先取回原始回执；确认无法取回，可执行以下命令明确放弃输出并保留原费用记录。没有可核对请求标识时保留未知事实，结合时间和账号账单核对。

```powershell
& $labPython -m avatar_lab abandon 记录ID --note '已核对计费，原始素材无法取回，放弃该输出'
```

若负责人核对控制台只看到其他已知请求、没有本次记录，可记录核对依据并保留原有预占，不把它写成已收费或已退款。此操作不自动执行；严格模式下仍受三次尝试和20元总上限约束：

```powershell
& $labPython -m avatar_lab retain 记录ID --note '负责人核对控制台未见该请求，按最坏情况保留费用占用'
```

## 6. 验证命令与边界

```powershell
# validation 目录
& $labPython -m unittest discover -s tests -v
# preview 目录
npm.cmd test
npm.cmd run build
```

Python 测试中的第三方响应使用离线替身，验证预算、流程和恢复语义，不是模型可用性证据。`tests/prepare_preview_fixture.py` 只生成标有 UI TEST ONLY 的几何测试素材，用于浏览器文件导入验证，不得当作角色样品。

透明清理从图像边缘估计实际饱和背景色，进行背景剔除，并仅在轮廓边缘处理色溢出，避免修改人物内部嘴唇等近似颜色。自动几何检查可以发现空帧、接触边界和完全重复帧，不能自动判断人物一致性、手指是否正确、身体是否有透明穿孔或循环是否自然，必须人工审阅。真实效果不足时修正生成参数或加工算法，不能把检测通过等同于视觉验收。

云对象存储、真人照片保持、动漫样品、视频参考、真实 TTS、正式 SDK 和平台后台不属于本次代码完成范围，按主方案后续验证。

## 7. 文件组织

- `avatar_lab/`：HTTP 适配、预算账本、阶段门禁、素材加工和命令入口。
- `preview/`：Vue 3 + TypeScript + Canvas 验收播放器。
- `tests/`：离线行为测试与明确标识的浏览器测试素材生成器。
- `workspace/jobs/记录ID/`：原图、提示词、临时回执及打包结果，已忽略。
- `config.local.json`：只在本机创建的 Key 配置，已忽略。
