# LN Relay（本机开发）

这个目录是供 M2 本机验收使用的最小开发者 Relay。它只监听 `127.0.0.1`，实现 `LN_RELAY/1` 的 TTS、能力握手与取消状态接口；不会保存文本、百炼 Key 或音频文件。

## 启动

先在当前 PowerShell 会话中设置以下环境变量（不要将实际值写入仓库）：

- `DASHSCOPE_API_KEY`：百炼**北京地域** API Key。
- `RELAY_ACCESS_TOKEN`：仅供 system/session 调用本 Relay 的随机专用 Bearer，不是百炼 Key。

可选覆盖：`LN_RELAY_PORT`（默认 `8024`）、`LN_RELAY_TTS_MODEL`（默认 `qwen3-tts-flash-realtime`）、`LN_RELAY_TTS_VOICE`（默认 `Cherry`）、`LN_RELAY_TIMEOUT_SECONDS`（默认 `45`）。

安装依赖一次后，最短启动命令为：

```powershell
python -m pip install -r .\requirements.txt
python .\app.py
```

服务地址为 `http://127.0.0.1:8024/ln-relay/v1`。启动不会产生任何百炼调用；只有受认证的 `POST /audio/speech` 才会调用实时 TTS。

## 平台适配契约

每个请求均需：`Authorization: Bearer <RELAY_ACCESS_TOKEN>`、`X-LN-Protocol-Version: 1`、稳定的 `X-Request-Id`。`POST /audio/speech` 的 JSON 至少包含与请求头相同的 `requestId`，以及 `applicationId`、`sessionId`、`turnId`、`segmentId`、非负整数 `ordinal`、`text`、`voiceAlias: "Cherry"`；可选 `modelAlias` 若提供，必须为 `qwen3-tts-flash-realtime`。

成功时返回经 Relay 校验的单声道 PCM16/24kHz `audio/wav`，并附带 `X-Request-Id`、实际音频时长和提交字符数。`POST /requests/{requestId}/cancel` 对已经完成的请求返回 `ALREADY_FINISHED`；百炼当前没有按请求取消接口，进行中的请求返回 `NOT_SUPPORTED`，不会假称已取消。
