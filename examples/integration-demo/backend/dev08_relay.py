"""Bounded local DEV-08 Relay and read-only Tool; expose only behind a temporary HTTPS tunnel."""

from __future__ import annotations

import base64
import hmac
import json
import os
import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from flask import Flask, Response, jsonify, request, stream_with_context


MAX_PROVIDER_CALLS = 8
MAX_OUTPUT_TOKENS = 256
MAX_INPUT_BYTES = 65536
PROVIDER_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"


@dataclass(frozen=True)
class Settings:
    relay_token: str
    tool_token: str
    provider_key: str
    allowed_user: str
    application_id: str

    @classmethod
    def from_environment(cls) -> "Settings":
        key_file = os.environ.get("DEV08_PROVIDER_KEY_FILE", "").strip()
        provider_key = (Path(key_file).read_text(encoding="utf-8-sig").strip() if key_file
                        else os.environ.get("DASHSCOPE_API_KEY", "").strip())
        values = [os.environ.get("DEV08_RELAY_TOKEN", "").strip(),
                  os.environ.get("DEV08_TOOL_TOKEN", "").strip(), provider_key,
                  os.environ.get("DEV08_ALLOWED_USER", "").strip(),
                  os.environ.get("DEV08_APPLICATION_ID", "").strip()]
        if not all(values) or "\n" in provider_key or "\r" in provider_key \
            or not re.fullmatch(r"[1-9][0-9]*", values[-1]):
            raise RuntimeError("DEV-08 Relay environment is incomplete")
        return cls(*values)


def event(kind: str, **data: object) -> str:
    return f"event: {kind}\ndata: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"


def provider_messages(messages: list[dict]) -> list[dict]:
    result: list[dict] = []
    for message in messages:
        role = message.get("role")
        if role in ("system", "user"):
            result.append({"role": role, "content": message.get("content", "")})
        elif role == "assistant":
            item: dict = {"role": role, "content": message.get("content", "")}
            calls = message.get("toolCalls") or []
            if calls:
                item["tool_calls"] = [
                    {"id": call["id"], "type": "function", "function": {
                        "name": call["name"], "arguments": call["arguments"],
                    }} for call in calls
                ]
            result.append(item)
        elif role == "tool":
            result.append({"role": role, "tool_call_id": message["toolCallId"],
                           "content": message.get("content", "")})
        else:
            raise ValueError("invalid message role")
    return result


def create_app(settings: Settings | None = None) -> Flask:
    settings = settings or Settings.from_environment()
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 131072
    lock = threading.Lock()
    history: OrderedDict[str, list[list[dict]]] = OrderedDict()
    latest_turn: dict[str, str] = {}
    provider_calls = 0

    def bearer(expected: str) -> bool:
        return hmac.compare_digest(request.headers.get("Authorization", ""), f"Bearer {expected}")

    def relay_authenticated() -> bool:
        return bearer(settings.relay_token) and request.headers.get("X-LN-Protocol-Version") == "1" \
            and bool(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request.headers.get("X-Request-Id", "")))

    @app.get("/health")
    def health() -> Response:
        return jsonify({"ok": True})

    @app.get("/ln-relay/v1/capabilities")
    def capabilities() -> Response:
        if not relay_authenticated():
            return jsonify({"code": "UNAUTHORIZED"}), 401
        return jsonify({"protocol": "LN_RELAY", "protocolVersion": "1",
                        "capabilities": {"llm": True, "asr": False, "tool": True, "cancel": False}})

    @app.get("/tool/lookup")
    def lookup() -> Response:
        if not bearer(settings.tool_token):
            return jsonify({"code": "UNAUTHORIZED"}), 401
        try:
            external_user = base64.urlsafe_b64decode(
                request.headers.get("X-LN-External-User-Id-B64", "") + "=="
            ).decode("utf-8")
        except (ValueError, UnicodeError):
            return jsonify({"code": "IDENTITY_INVALID"}), 403
        session_id = request.headers.get("X-LN-Session-Id", "")
        if external_user not in (settings.allowed_user, f"__ln_debug__:{session_id}") \
            or request.headers.get("X-LN-Application-Id") != settings.application_id \
            or not re.fullmatch(r"[1-9][0-9]*", session_id):
            return jsonify({"code": "IDENTITY_DENIED"}), 403
        item = request.args.get("item")
        if item == "timeout-test":
            time.sleep(7)
        elif item != "demo-1":
            return jsonify({"code": "ITEM_NOT_FOUND"}), 404
        return jsonify({"name": "演示商品", "stock": 7, "internalNote": "不应返回到模型或浏览器"})

    @app.post("/ln-relay/v1/chat/completions")
    def chat() -> Response:
        nonlocal provider_calls
        if not relay_authenticated():
            return jsonify({"code": "UNAUTHORIZED"}), 401
        body = request.get_json(silent=True)
        if not isinstance(body, dict) or body.get("requestId") != request.headers["X-Request-Id"] \
            or body.get("model") != "qwen-flash" or body.get("stream") is not True \
            or str(body.get("applicationId", "")) != settings.application_id \
            or not re.fullmatch(r"[1-9][0-9]*", str(body.get("sessionId", ""))) \
            or not isinstance(body.get("messages"), list) or not isinstance(body.get("tools"), list) \
            or not isinstance(body.get("parameters"), dict) \
            or not re.fullmatch(r"[1-9][0-9]*", str(body.get("turnId", ""))) \
            or not isinstance(body.get("externalUserId"), str):
            return jsonify({"code": "REQUEST_INVALID"}), 400
        user = body["externalUserId"]
        turn_id = body["turnId"]
        if not user or len(user) > 256 or len(body["tools"]) > 20:
            return jsonify({"code": "REQUEST_INVALID"}), 400
        try:
            current = provider_messages(body["messages"])
            definitions = [
                {"type": "function", "function": {"name": tool["name"],
                 "parameters": tool["inputSchema"]}} for tool in body["tools"]
            ]
        except (KeyError, TypeError, ValueError):
            return jsonify({"code": "REQUEST_INVALID"}), 400
        with lock:
            previous = [part for turn in history.get(user, []) for part in turn]
        full = current[:1] + previous + current[1:] if current and current[0]["role"] == "system" else previous + current
        if len(json.dumps(full, ensure_ascii=False).encode("utf-8")) > MAX_INPUT_BYTES:
            return jsonify({"code": "REQUEST_TOO_LARGE"}), 413
        with lock:
            latest_turn[user] = turn_id
        # Deterministic manual probes for stop/replacement; they never call the paid provider.
        last_user = next((m.get("content") for m in reversed(current) if m["role"] == "user"), "")
        if last_user in ("DEV08_SLOW_TEST", "DEV08_FAST_TEST", "DEV08_TOOL_TIMEOUT_TEST", "DEV08_TOOL_DENIED_TEST"):
            def probe():
                if last_user in ("DEV08_TOOL_TIMEOUT_TEST", "DEV08_TOOL_DENIED_TEST"):
                    if any(message["role"] == "tool" for message in current):
                        yield event("text.delta", text="查询未成功，请稍后重试。")
                    else:
                        name = "lookup_demo_item" if last_user == "DEV08_TOOL_TIMEOUT_TEST" else "not_bound_tool"
                        yield event("tool_call.delta", id="dev08_probe", name=name,
                                    arguments=json.dumps({"item": "timeout-test"}))
                    yield event("response.completed", usage={"inputTokens": 0, "outputTokens": 0})
                    return
                yield event("text.delta", text="测试流")
                if last_user == "DEV08_SLOW_TEST":
                    time.sleep(5)
                yield event("response.completed", usage={"inputTokens": 0, "outputTokens": 0})
            return Response(stream_with_context(probe()), content_type="text/event-stream; charset=utf-8",
                            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
        with lock:
            if provider_calls >= MAX_PROVIDER_CALLS:
                return jsonify({"code": "TEST_BUDGET_EXHAUSTED"}), 429
            provider_calls += 1
        payload: dict = {
            "model": "qwen-flash", "messages": full, "stream": True,
            "stream_options": {"include_usage": True}, "max_tokens": MAX_OUTPUT_TOKENS,
        }
        if definitions:
            payload["tools"] = definitions
        temperature = body["parameters"].get("temperature")
        if isinstance(temperature, (int, float)) and not isinstance(temperature, bool) and 0 <= temperature <= 2:
            payload["temperature"] = temperature

        def generate():
            text_parts: list[str] = []
            calls: dict[int, dict[str, str]] = {}
            provider_id = None
            usage = None
            finish = None
            try:
                upstream = Request(PROVIDER_URL, json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                   {"Authorization": f"Bearer {settings.provider_key}",
                                    "Content-Type": "application/json", "Accept": "text/event-stream"},
                                   method="POST")
                deadline = time.monotonic() + 45
                with urlopen(upstream, timeout=30) as response:
                    for raw in response:
                        if time.monotonic() > deadline:
                            raise TimeoutError()
                        line = raw.decode("utf-8").strip()
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if data == "[DONE]":
                            break
                        chunk = json.loads(data)
                        provider_id = chunk.get("id") or provider_id
                        usage = chunk.get("usage") or usage
                        for choice in chunk.get("choices") or []:
                            finish = choice.get("finish_reason") or finish
                            delta = choice.get("delta") or {}
                            content = delta.get("content")
                            if isinstance(content, str) and content:
                                text_parts.append(content)
                                yield event("text.delta", text=content)
                            for call in delta.get("tool_calls") or []:
                                index = call.get("index")
                                if not isinstance(index, int) or not 0 <= index < 4:
                                    raise ValueError("invalid Tool index")
                                part = calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
                                part["id"] = call.get("id") or part["id"]
                                function = call.get("function") or {}
                                part["name"] += function.get("name") or ""
                                part["arguments"] += function.get("arguments") or ""
                if finish not in ("stop", "tool_calls"):
                    raise ValueError("incomplete provider response")
                for part in calls.values():
                    if not part["id"] or not part["name"] or len(part["arguments"]) > 32768:
                        raise ValueError("invalid Tool proposal")
                    yield event("tool_call.delta", **part)
                if not calls:
                    with lock:
                        if latest_turn.get(user) == turn_id:
                            history.setdefault(user, []).append(
                                [message for message in current if message["role"] != "system"]
                                + [{"role": "assistant", "content": "".join(text_parts)}]
                            )
                            history[user] = history[user][-4:]
                            history.move_to_end(user)
                            if len(history) > 64:
                                removed, _ = history.popitem(last=False)
                                latest_turn.pop(removed, None)
                final: dict = {}
                if isinstance(usage, dict):
                    final["usage"] = {"inputTokens": usage.get("prompt_tokens"),
                                      "outputTokens": usage.get("completion_tokens")}
                if isinstance(provider_id, str) and re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", provider_id):
                    final["providerRequestId"] = provider_id
                yield event("response.completed", **final)
            except (HTTPError, URLError, TimeoutError, ValueError, KeyError, json.JSONDecodeError):
                yield event("error", code="UPSTREAM_FAILED")

        return Response(stream_with_context(generate()), content_type="text/event-stream; charset=utf-8",
                        headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})

    return app


if __name__ == "__main__":
    create_app().run(host="127.0.0.1", port=8030, threaded=True, debug=False)
