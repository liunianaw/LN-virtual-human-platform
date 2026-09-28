# DEV-08 本地验收 Relay 与查询 Tool

`dev08_relay.py` 将平台的 `LN_RELAY/1` 请求转给现有项目使用的 DashScope Key，并提供一个只读的演示商品查询 Tool。程序只监听 `127.0.0.1:8030`；平台要求为它配置可验证的公网 HTTPS 入口，不能直接填写本机地址或公网 IP。

## 启动前配置

通过进程环境提供以下值，不写入 Git、页面、日志或命令输出：

| 环境变量 | 用途 |
| --- | --- |
| `DEV08_PROVIDER_KEY_FILE` | 本机模型厂商 Key 文件的绝对路径；设置后优先于 `DASHSCOPE_API_KEY`，供本次新 Key 使用 |
| `DASHSCOPE_API_KEY` | 未设置 Key 文件时使用的现有模型厂商 Key |
| `DEV08_RELAY_TOKEN` | 平台访问此 Relay 的随机 Bearer Token，至少 32 字节 |
| `DEV08_TOOL_TOKEN` | 平台访问只读 Tool 的独立随机 Bearer Token |
| `DEV08_ALLOWED_USER` | 此次验收的业务用户 ID；Tool 同时接受与服务端 Session ID 对应的 `__ln_debug__:<sessionId>` |
| `DEV08_APPLICATION_ID` | 此次验收的 Application 十进制 ID |

用项目 `tools/local-relay/.venv/Scripts/python.exe` 运行 `dev08_relay.py`，或在装有 Flask 3 的环境中运行。`GET /health` 只检查进程；`GET /ln-relay/v1/capabilities` 需要 Relay Token 和 `X-LN-Protocol-Version: 1`、`X-Request-Id`；不会调用模型。平台 Relay base URL 为公网入口加 `/ln-relay/v1`；Tool URL 为公网入口加 `/tool/lookup`。

模型固定为 `qwen-flash`，每次最多输出 256 token，进程生命周期最多提交 8 次模型请求。达到上限后返回 `TEST_BUDGET_EXHAUSTED`。一次重启会重置此上限，因此验收期间只启动一个实例，不自动重启。Tool 仅接受 `GET ?item=demo-1`，并核对单独 Token、平台注入的用户/Application/Session 身份；返回 `name`、`stock` 与供平台输出白名单验证的 `internalNote`。发布 Tool 时将输出 Schema 限定为 `name` 和 `stock`，前端字段仅选择 `name` 和 `stock`。

运行不花费模型费用的离线检查：

```powershell
& 'tools/local-relay/.venv/Scripts/python.exe' -m unittest discover -s 'examples/integration-demo/backend' -p 'test_dev08_relay.py' -v
```

## 已保存的本机测试配置（2026-09-27）

本目录是后续开发者接入模块的持续验收项目，保留代码、脚本和下面的平台配置。测试资源属于本机账号 1；更换数据库或部署环境后，需重新创建对应资源，不能直接复用这些 ID。

| 资源 | 当前值 |
| --- | --- |
| Application | `test`，ID `102089642576183297`，当前 CHAT 配置 ID `102097029416616270`；固定官方 Voice Cherry、LLM 与 ASR Relay |
| LLM Relay | `DEV08-本机验收-LLM`，版本 `102097029416615940`，base URL `https://liunianaw.online/dev08/ln-relay/v1`；仅授权上面的 Application |
| ASR Relay | `DEV09-本机验收-ASR`，版本 `102097029416616260`，复用上面的 HTTPS `/dev08/ln-relay/v1` 与已加密 Relay Token；仅授权 `test` Application |
| Prompt Skill | `DEV08-验收提示`，版本 `102097029416615937`，要求回答末尾带 `DEV08_SKILL_OK` |
| 查询 Tool Skill | `DEV08-只读商品查询`，版本 `102097029416615949`，`lookup_demo_item`，`GET https://liunianaw.online/dev08/tool/lookup?item=demo-1`；浏览器结果字段仅 `name`、`stock` |
| HTTPS 入口 | `liunianaw.online` 现有站点的 aaPanel Nginx extension include：`/www/server/panel/vhost/nginx/extension/124.220.61.136/dev08-relay.conf`。仅新增 `/dev08/`；反向代理至服务器 loopback `127.0.0.1:18030`，再由 SSH 反向隧道连接本机 `127.0.0.1:8030` |
| 测试身份 | BUSINESS `externalUserId=dev08-user-a`；DEBUG 身份由当前后台登录签发 |
| 本机凭证 | 工作区外 `D:\BigData\LN-virtual‑human‑platform\key.txt`（Qwen Key）、`sshkey.txt`（SSH）；`.m1-runtime-logs/dev08-{relay,tool}-token.dpapi`、`dev08-app-secret.dpapi` 为当前 Windows 用户 DPAPI 密文。绝不提交明文或 `.m1-runtime-logs` |
| 测试容量 | `platform_db.p_account_limit` 账号 1 的本机测试行，`max_sessions=20`、`max_turns=10`，供后续测试复用 |

在仓库根目录运行 `& 'examples/integration-demo/backend/Start-Dev08Demo.ps1'`，脚本复用已运行的 Relay/隧道，缺少时读取本机加密凭证并在隐藏窗口启动。服务器 Nginx include 已安装并执行过 `nginx -t` 与 reload；不需要在 aaPanel 的“反向代理”列表再建一项。若服务器重装或站点配置不再包含 extension 目录，再核对站点配置后运行 `install_dev08_nginx.py`。本机 SSH `known_hosts` 已固定服务器指纹；不要关闭该校验。

本机 Java DNS 会返回代理保留地址。启动 system/session 进行真实 Relay 与官方 TTS 调用前，准备 `%TEMP%\dev08-java-hosts.txt`（`127.0.0.1 localhost`、`124.220.61.136 liunianaw.online`、当前本机解析得到的 `dashscope.aliyuncs.com` 地址三行），在同一 PowerShell 进程设置 `$env:JAVA_TOOL_OPTIONS='-Djdk.net.hosts.file='+$env:TEMP+'\dev08-java-hosts.txt'`，然后运行现有 `RuoYi-Cloud/bin/Start-LocalPlatform.ps1`。该 Java hosts 文件没有普通 DNS 回退；添加官方域名之前应使用无密钥 HEAD 探针核对可连通性。后续公网 IP 或代理地址改变时，先重验 TLS 与 DNS，再更新临时 hosts 文件；不要修改系统级 hosts。

`accept_dev08_ws.py` 用一次性 `DEV08_WS_TICKET`、`DEV08_WS_URL` 连接 Gateway WSS 并发送 `DEV08_CHAT_PROMPT`；`accept_dev08_boundaries.py` 使用同样的票据检查 stop 和替换。浏览器允许 Origin 为 `http://127.0.0.1`。新票据通过当前 Session Token 的 `POST /api/v1/runtime/connection-tickets` 签发；`dev08-{debug,business}-token.dpapi` 是短期令牌，过期时须重新签发。`DEV08_SLOW_TEST`、`DEV08_FAST_TEST` 是 Relay 内置的无模型费用边界探针，不能当成正常业务提问。测试脚本在认证后才启用探针。

`accept_dev08_page.py` 用 `DEV08_ADMIN_JWT` 打开真实 `/system/applications` 页面，自动进入 `test` 的 CHAT 调试，使用 `DEV08_FAST_TEST` 完成零模型费用的角色/WSS/文本流检查；截图写在由 `DEV08_SCREENSHOT` 指定的忽略目录。它需要本机 Vite、Gateway、system、session 和正式 Avatar 资源都可用。Vite 现在代理 `/api/v1/realtime` WebSocket 与 `/api/v1/runtime/media`，生产环境由 Gateway 直接提供路径。

`accept_dev08_sdk.py` 使用短期 BUSINESS Token 环境变量 `DEV08_BUSINESS_TOKEN` 在真实 Chrome 中加载 `avatar-sdk/dist`，调用无 Vue `SessionClient.chat('DEV08_FAST_TEST')` 并核对流式事件。验收后应关闭该短期 BUSINESS Session。测试 Token 只在进程内传递，不写入脚本、页面静态文件或截图。

DEV-09 复用上述 `test` Application、BUSINESS Session 与 HTTPS Relay。`dev08_relay.py` 在已认证后接受 `DEV09_SENTENCES_TEST`，零 LLM 费用返回“你好。测试完毕。”供官方 TTS 两段验收；ASR 转发真实 `qwen3-asr-flash`，单 Relay 进程最多两次真实 ASR 请求，重启计数会重置。`accept_dev09_ws.py` 使用 `DEV09_BUSINESS_TOKEN` 验证 BUSINESS 独立播报、CHAT 两段顺序媒体和 `DEV09_MODE=STOP` 的迟到音频隔离；`DEV09_SPEECH_TEXT` 与 `DEV09_EXPECTED_SEGMENTS` 可收窄测试。`accept_dev09_page.py` 使用 `DEV09_ADMIN_JWT`、`DEV09_MICROPHONE_WAV`、`DEV09_SCREENSHOT` 验证真实 Chrome 中的录音、识别确认及两段播放；`DEV09_BLOCK_AUTOPLAY=1` 且不设置麦克风文件时，模拟首次播放受阻和点击恢复。测试语音只放在忽略目录，测试后删除；不要提交 Token、音频、截图或本机日志。

DEV-10 的无费用探针需在启动 Relay 前设置 `LN_DEV10_PROBE_MODE=1`；此时能力探测额外声明 `image:true`，仅供新建的测试 Relay 版本和测试 Application 配置使用，既有 Relay 版本不会自动改变。启用 Page/Hybrid、图片和 Tool 能力后，可在 CHAT 调试页选择显式/AI 按需，发送 `DEV10_PAGE_TEST`、`DEV10_HYBRID_TEST` 或 `DEV10_HIGHLIGHT_TEST`；Relay 只按已认证请求返回固定的采集/高亮 Tool 建议及收到的图片/结果计数，不调用付费模型。非探针请求携带图片时拒绝 `IMAGE_PROBE_ONLY`；此模式不证明 `qwen-flash` 的真实图片识别能力。验证实际识别前须另配支持图片的模型 Relay 和费用授权。当前 DEV-10 尚未执行服务、浏览器或模型终点验收。

## 已通过的验收与费用边界

- Flyway session V6 已迁移；system/session/auth/gateway/media API 健康检查为 200。Relay、Tool 的平台无费用连接检查通过；公网 `/dev08/health` 与原站根路径均为 200。
- DEBUG 与 BUSINESS 短期授权均能获取运行时状态、一次性 ticket 并经 Gateway WSS 建立 `ln-avatar.v1` 连接。真实 Qwen 流式回答含 Prompt 标记；真实 `lookup_demo_item` Tool 返回 `name=演示商品`、`stock=7`，`internalNote` 未出现在浏览器事件。BUSINESS 同一用户跨连接复述暗号“青山47”，多轮记忆由此 Relay 的进程内缓存提供。
- `turn.stop` 持久化为 `INTERRUPTED/USER_STOP`，新轮替换旧轮持久化为 `INTERRUPTED/REPLACED`，在途 LLM operation 保留 `UNKNOWN`，新轮完成。跨业务用户读取 Session 返回 404。测试 Relay 的无效身份和鉴权由离线聚焦测试覆盖。
- 未绑定 Tool 提议未产生 Tool operation；`timeout-test` 在 Tool 的 5 秒上限后仅返回明确失败文本，Tool operation 为 `UNKNOWN`。短暂停用 Application 时运行时新请求被拒 403，随后已恢复 `ACTIVE`；原短期 Token 因授权 epoch 改变而被拒 401，符合撤销语义。当前 Application 修订号为 5。
- 真实 Chrome 调试页已显示正式角色，拿到一次性 ticket、连接 WSS、发送 `DEV08_FAST_TEST` 并显示“测试流”，无页面异常。验收发现并修复 DEBUG Session ID 的 JS 数字精度、CHAT 正式角色包读取、角色包伪成功错误响应与 Vite 本机 WebSocket 代理。截图在 `.m1-runtime-logs/dev08-page.png`，由启动脚本产生的多余 DEBUG Session 已关闭。
- 真实 Chrome 加载 `avatar-sdk/dist` 后，BUSINESS `SessionClient.chat()` 收到 `connection.ready`、`request.ack`、`turn.started`、`text.delta`、`turn.completed`，显示“测试流”；该探针的短期 BUSINESS Session 已关闭。
- 最初四个真实 CHAT turn 对应 DEBUG 3 次 LLM + 1 次 Tool、BUSINESS 2 次 LLM；当时 `s_outbox` 有 12 条 `CALL_FACT_RECORDED` 并已投递。随后无费用的停止、替换、Tool 越权/超时及浏览器探针也写入了各自调用事实，运营表当前至少有 LLM 10 次成功/2 次 UNKNOWN、Tool 1 次成功/1 次 UNKNOWN。付费模型操作合计输入 1273、输出 84 token；此前本地直接 Key 与 Relay 探针另有输入 198、输出 25 token。模型固定 `qwen-flash`，每次至多输出 256 token；Relay 单进程至多提交 8 次付费请求，重启会重置计数。每次追加测试前先核对用户授权的总费用上限；当前授权上限为 10 元。

按[百炼 `qwen-flash` 华北 2（北京）公开原价](https://help.aliyun.com/zh/model-studio/qwen-flash)中单次输入不超过 128k token 的档位计算，上述 1471 输入、109 输出 token 的名义费用约 ¥0.0004；实际账单以百炼控制台为准。本机 Relay 进程曾为增加无费用探针而重启，单进程 8 次限制不能替代这份累计记录。

DEV-09 于 2026-09-27 在本机完成四次真实 ASR、官方 TTS 独立播报、CHAT 两段、停止后迟到合成、调试页真实录音与模拟自动播放恢复；ASR、媒体读取均为 HTTP 200，浏览器无脚本错误。账号 1 为测试授予 500 `TTS_CHAR`；各成功合成段预占后结算，停止后成功合成不退额且不暴露媒体。早期网络/格式故障的三笔预占共 14 字保留 `REVIEW_REQUIRED`，一笔修复前停止轮事实保留 `UNKNOWN`，交由 DEV-11 核对。用户提供的百炼一小时导出日志显示旧 TTS 请求为 `ClientDisconnect` 400、ASR 为 200；适配器等到 `response.done → session.finish → session.finished` 再返回成功后，用户确认最新 TTS 为 200。临时音频清理时区修复部署后，12 条待删对象转为 `DELETED`，12 个文件均已删除。用户授权本轮真实语音调用费用上限 10 元，实际账单以百炼控制台为准。

已有一次性测试 Secret 以 DPAPI 保留，Relay/Tool Token 与本机平台配置继续保留。短期 Session/Token 自然到期后重新创建即可；不要把正在使用的测试 Application、Skills、Relay 或 `/dev08/` 入口删除。浏览器探针会创建短期 DEBUG Session，失败时检查后将其关闭；保留测试项目不要求保留每次的临时 Session。需要上线长期使用时，应将进程、隧道、凭证与审计交给正式服务管理，而不是依赖交互式本机进程。
