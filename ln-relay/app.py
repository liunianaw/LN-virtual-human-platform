"""Local-only LN_RELAY v1 development adapter for DashScope realtime TTS."""

from __future__ import annotations

import base64
import binascii
import hmac
import io
import json
import os
import threading
import time
import uuid
import wave
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlencode

import websocket
from flask import Flask, Response, jsonify, request


PROTOCOL_NAME = "LN_RELAY"
PROTOCOL_VERSION = "1"
DEFAULT_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
DEFAULT_MODEL = "qwen3-tts-flash-realtime"
DEFAULT_VOICE = "Cherry"
DEFAULT_PORT = 8024
DEFAULT_MAX_TEXT_CHARS = 1000
DEFAULT_MAX_AUDIO_BYTES = 20 * 1024 * 1024


class RelayError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


@dataclass(frozen=True)
class RelayConfig:
    access_token: str
    dashscope_api_key: str
    endpoint: str
    model: str
    voice: str
    timeout_seconds: float
    max_text_chars: int
    max_audio_bytes: int

    @classmethod
    def from_environment(cls) -> "RelayConfig":
        def required(name: str) -> str:
            value = os.environ.get(name, "").strip()
            if not value:
                raise RuntimeError(f"{name} must be set before starting ln-relay")
            return value

        config = cls(
            access_token=required("RELAY_ACCESS_TOKEN"),
            dashscope_api_key=required("DASHSCOPE_API_KEY"),
            endpoint=os.environ.get("LN_RELAY_DASHSCOPE_ENDPOINT", DEFAULT_ENDPOINT).strip(),
            model=os.environ.get("LN_RELAY_TTS_MODEL", DEFAULT_MODEL).strip(),
            voice=os.environ.get("LN_RELAY_TTS_VOICE", DEFAULT_VOICE).strip(),
            timeout_seconds=float(os.environ.get("LN_RELAY_TIMEOUT_SECONDS", "45")),
            max_text_chars=int(os.environ.get("LN_RELAY_MAX_TEXT_CHARS", str(DEFAULT_MAX_TEXT_CHARS))),
            max_audio_bytes=int(os.environ.get("LN_RELAY_MAX_AUDIO_BYTES", str(DEFAULT_MAX_AUDIO_BYTES))),
        )
        if config.timeout_seconds <= 0 or config.max_text_chars <= 0 or config.max_audio_bytes <= 44:
            raise RuntimeError("LN_RELAY timeout and limits must be positive")
        return config

    def websocket_url(self) -> str:
        separator = "&" if "?" in self.endpoint else "?"
        return f"{self.endpoint}{separator}{urlencode({'model': self.model})}"


class RequestStates:
    """Keeps only process-local cancellation state; it never persists text or keys."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._states: dict[str, str] = {}

    def begin(self, request_id: str) -> None:
        with self._lock:
            self._states[request_id] = "PROCESSING"

    def finish(self, request_id: str) -> None:
        with self._lock:
            self._states[request_id] = "FINISHED"

    def cancel(self, request_id: str) -> str:
        with self._lock:
            state = self._states.get(request_id)
            if state is None:
                return "UNKNOWN"
            if state == "FINISHED":
                return "ALREADY_FINISHED"
            # DashScope realtime TTS has no request-scoped cancellation endpoint.
            return "NOT_SUPPORTED"


def event(event_type: str, **payload: Any) -> str:
    return json.dumps({"event_id": str(uuid.uuid4()), "type": event_type, **payload}, ensure_ascii=False)


def error_response(error: RelayError, request_id: str | None = None) -> tuple[Response, int]:
    body: dict[str, Any] = {"code": error.code, "message": error.message}
    if request_id:
        body["requestId"] = request_id
    return jsonify(body), error.status


def assert_wav(audio: bytes, max_audio_bytes: int) -> int:
    if not audio or len(audio) > max_audio_bytes or not audio.startswith(b"RIFF") or audio[8:12] != b"WAVE":
        raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游没有返回有效 WAV 音频")
    try:
        with wave.open(io.BytesIO(audio), "rb") as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 24000:
                raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游 WAV 格式不符合单声道 PCM16/24kHz 约束")
            return int((wav.getnframes() * 1000) / wav.getframerate())
    except RelayError:
        raise
    except (EOFError, wave.Error) as exc:
        raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游没有返回可解析的 WAV 音频") from exc


def synthesize(config: RelayConfig, text: str) -> tuple[bytes, int]:
    """Call DashScope without recording request content or provider credentials."""
    ws: websocket.WebSocket | None = None
    audio_parts: list[bytes] = []
    deadline = time.monotonic() + config.timeout_seconds
    try:
        ws = websocket.create_connection(
            config.websocket_url(),
            timeout=config.timeout_seconds,
            header=[f"Authorization: Bearer {config.dashscope_api_key}", "User-Agent: LN-Relay/1"],
        )
        ws.send(event("session.update", session={
            "voice": config.voice,
            "mode": "commit",
            "language_type": "Chinese",
            "response_format": "wav",
            "sample_rate": 24000,
        }))
        ws.send(event("input_text_buffer.append", text=text))
        ws.send(event("input_text_buffer.commit"))

        while time.monotonic() < deadline:
            ws.settimeout(max(0.1, deadline - time.monotonic()))
            raw_event = ws.recv()
            if not raw_event:
                break
            message = json.loads(raw_event)
            message_type = message.get("type")
            if message_type == "response.audio.delta":
                encoded = message.get("delta")
                if not isinstance(encoded, str):
                    raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游音频分片缺失")
                try:
                    audio_parts.append(base64.b64decode(encoded, validate=True))
                except (ValueError, binascii.Error) as exc:
                    raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游音频分片无效") from exc
                if sum(map(len, audio_parts)) > config.max_audio_bytes:
                    raise RelayError(502, "UPSTREAM_AUDIO_INVALID", "上游音频超过 Relay 限制")
            elif message_type == "response.audio.done":
                audio = b"".join(audio_parts)
                return audio, assert_wav(audio, config.max_audio_bytes)
            elif message_type == "error":
                raise RelayError(502, "UPSTREAM_TTS_FAILED", "百炼实时 TTS 请求失败")

        raise RelayError(504, "UPSTREAM_TTS_TIMEOUT", "百炼实时 TTS 响应超时")
    except RelayError:
        raise
    except (OSError, websocket.WebSocketException, json.JSONDecodeError) as exc:
        raise RelayError(502, "UPSTREAM_TTS_UNAVAILABLE", "无法连接百炼实时 TTS") from exc
    finally:
        if ws is not None:
            try:
                ws.send(event("session.finish"))
            except (OSError, websocket.WebSocketException):
                pass
            ws.close()


def create_app(config: RelayConfig | None = None) -> Flask:
    config = config or RelayConfig.from_environment()
    states = RequestStates()
    app = Flask(__name__)

    def authenticated_request() -> str:
        authorization = request.headers.get("Authorization", "")
        expected = f"Bearer {config.access_token}"
        if not hmac.compare_digest(authorization, expected):
            raise RelayError(401, "UNAUTHORIZED", "Relay Bearer 认证失败")
        if request.headers.get("X-LN-Protocol-Version") != PROTOCOL_VERSION:
            raise RelayError(400, "PROTOCOL_VERSION_INVALID", "X-LN-Protocol-Version 必须为 1")
        request_id = request.headers.get("X-Request-Id", "").strip()
        if not request_id or len(request_id) > 128:
            raise RelayError(400, "REQUEST_ID_INVALID", "X-Request-Id 必须是 1 至 128 个字符")
        return request_id

    @app.errorhandler(RelayError)
    def handle_relay_error(error: RelayError) -> tuple[Response, int]:
        return error_response(error, request.headers.get("X-Request-Id"))

    @app.get("/ln-relay/v1/capabilities")
    def capabilities() -> Response:
        authenticated_request()
        return jsonify({
            "protocol": PROTOCOL_NAME,
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"llm": False, "asr": False, "tts": True, "cancel": True},
            "tts": {"outputMimeType": "audio/wav", "sampleRate": 24000, "channels": 1, "sampleBits": 16},
            "idempotency": {"supported": False},
        })

    @app.post("/ln-relay/v1/audio/speech")
    def audio_speech() -> Response:
        request_id = authenticated_request()
        body = request.get_json(silent=True)
        if not isinstance(body, dict):
            raise RelayError(400, "REQUEST_INVALID", "TTS 请求必须为 JSON 对象")
        if body.get("requestId") != request_id:
            raise RelayError(400, "REQUEST_ID_INVALID", "请求体 requestId 必须与 X-Request-Id 一致")
        def valid_operation_id(value: Any) -> bool:
            return (isinstance(value, str) and bool(value.strip())) or (isinstance(value, int) and not isinstance(value, bool) and value > 0)

        if not all(valid_operation_id(body.get(key)) for key in ("applicationId", "sessionId", "turnId", "segmentId")):
            raise RelayError(400, "REQUEST_INVALID", "TTS 请求缺少平台操作标识")
        if not isinstance(body.get("ordinal"), int) or isinstance(body.get("ordinal"), bool) or body["ordinal"] < 0:
            raise RelayError(400, "REQUEST_INVALID", "TTS 请求缺少非负 ordinal")
        if "parameters" in body and not isinstance(body["parameters"], dict):
            raise RelayError(400, "REQUEST_INVALID", "parameters 必须为对象")
        text = body.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > config.max_text_chars:
            raise RelayError(400, "TEXT_INVALID", f"text 必须为 1 至 {config.max_text_chars} 个字符")
        voice_alias = body.get("voiceAlias")
        if voice_alias != config.voice:
            raise RelayError(400, "VOICE_ALIAS_INVALID", "voiceAlias 与已登记的 Relay 音色不一致")
        model_alias = body.get("modelAlias")
        if model_alias not in (None, "", config.model):
            raise RelayError(400, "MODEL_ALIAS_INVALID", "modelAlias 与已登记的 Relay 模型不一致")

        states.begin(request_id)
        try:
            audio, duration_ms = synthesize(config, text)
            response = Response(audio, mimetype="audio/wav")
            response.headers["X-Request-Id"] = request_id
            response.headers["X-LN-Audio-Duration-Ms"] = str(duration_ms)
            response.headers["X-LN-Usage-Characters"] = str(len(text))
            return response
        finally:
            states.finish(request_id)

    @app.post("/ln-relay/v1/requests/<request_id>/cancel")
    def cancel(request_id: str) -> Response:
        authenticated_request()
        return jsonify({"requestId": request_id, "state": states.cancel(request_id)})

    return app


if __name__ == "__main__":
    relay_config = RelayConfig.from_environment()
    create_app(relay_config).run(
        host="127.0.0.1",
        port=int(os.environ.get("LN_RELAY_PORT", str(DEFAULT_PORT))),
        debug=False,
        threaded=True,
    )
