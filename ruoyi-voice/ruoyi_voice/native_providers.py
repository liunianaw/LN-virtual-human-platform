"""Native protocols stay here. No automatic synthesis retries or fallback."""
import base64
import io
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
from pathlib import Path
from .providers import ProviderFailure, SynthesisResult


def capability(name):
    return next(item for item in json.loads(Path(__file__).with_name("capabilities.json").read_text(encoding="utf-8"))
                if item["providerType"] == name)


def remaining(deadline, maximum=180):
    value = min(maximum, deadline - time.time())
    if value <= 0:
        raise TimeoutError()
    return value


def pcm_wav(pcm):
    if not pcm or len(pcm) % 2 or len(pcm) + 44 > 5242880:
        raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE")
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        audio.writeframes(pcm)
    return output.getvalue()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_bounded(response, limit, deadline):
    data = bytearray()
    while True:
        remaining(deadline)
        chunk = response.read1(min(65536, limit + 1 - len(data)))
        if not chunk:
            return bytes(data)
        data.extend(chunk)
        if len(data) > limit:
            raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE")


def http_call(base, path, credential, deadline, body=None, limit=65536):
    if not credential:
        raise ProviderFailure("VOICE_CREDENTIAL_INVALID", "DEFINITIVE")
    target = urllib.parse.urlsplit(base)
    if target.scheme not in ("http", "https") or target.username or target.query or target.fragment \
            or base not in os.getenv("LN_VOICE_ALLOWED_ENDPOINTS", "").split(","):
        raise ProviderFailure("VOICE_ENDPOINT_NOT_ALLOWLISTED", "DEFINITIVE")
    req = urllib.request.Request(base.rstrip("/") + path,
        None if body is None else json.dumps(body).encode(),
        {"Authorization": "Bearer " + credential, "Content-Type": "application/json"})
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=remaining(deadline)) as response:
            return read_bounded(response, limit, deadline)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            error.close()
            raise ProviderFailure("VOICE_CREDENTIAL_INVALID", "DEFINITIVE") from None
        # Only the authenticated model protocol's explicit terminal facts prove a definitive failure.
        codes = {"VOICE_QUEUE_FULL", "VOICE_PROVIDER_NOT_READY", "VOICE_AUDIO_INVALID", "VOICE_PARAMETER_INVALID",
                 "VOICE_CAPABILITY_UNSUPPORTED", "VOICE_REFERENCE_UNAVAILABLE", "VOICE_PROVIDER_FAILED", "VOICE_CANCELLED"}
        try:
            value = json.loads(read_bounded(error, 65536, deadline))
        except Exception:
            raise TimeoutError() from None
        finally:
            error.close()
        if value.get("code") in codes and value.get("failureStage") in ("BEFORE_DISPATCH", "DEFINITIVE"):
            raise ProviderFailure(value["code"], "DEFINITIVE") from None
        raise TimeoutError() from None


class QwenProvider:
    capability = capability("DASHSCOPE_QWEN_TTS")
    compatible_capability_versions = {"qwen-bridge-v1"}

    def ready(self, binding=None, execution=None):
        # Cloud has no free synthesis readiness probe. This is adapter readiness only.
        if binding is None:
            return False
        try:
            import websockets.sync.client
            return True
        except ImportError:
            return False

    def synthesize(self, text, binding, deadline, execution=None):
        from websockets.sync.client import connect
        if binding["endpoint"] != "wss://dashscope.aliyuncs.com/api-ws/v1/realtime":
            raise ProviderFailure("VOICE_ENDPOINT_NOT_ALLOWLISTED", "DEFINITIVE")
        credential = (execution or {}).get("credential")
        if not credential:
            raise ProviderFailure("VOICE_CREDENTIAL_INVALID", "DEFINITIVE")
        target = binding["endpoint"] + "?" + urllib.parse.urlencode({"model": binding["modelId"]})
        pcm, audio_done, response_done, updated = bytearray(), False, False, False
        provider_id = None
        def send(socket, kind, **payload):
            socket.send(json.dumps({"event_id": str(uuid.uuid4()), "type": kind, **payload}))
        # Proxy auto-discovery is disabled; credentials go only to the pinned TLS endpoint.
        with connect(target, additional_headers={"Authorization": "Bearer " + credential},
                     open_timeout=remaining(deadline, 5), close_timeout=1, max_size=10485760, proxy=None) as socket:
            while True:
                event = json.loads(socket.recv(timeout=remaining(deadline)))
                kind = event.get("type")
                if kind == "session.created":
                    send(socket, "session.update", session={"voice": binding["providerVoiceRef"], "mode": "commit",
                         "language_type": {"zh-CN": "Chinese", "en-US": "English"}[binding["language"]],
                         "response_format": "pcm", "sample_rate": 24000})
                elif kind == "session.updated" and not updated:
                    updated = True
                    send(socket, "input_text_buffer.append", text=text)
                    send(socket, "input_text_buffer.commit")
                elif kind == "response.audio.delta":
                    try:
                        delta = base64.b64decode(event["delta"], validate=True)
                    except (ValueError, KeyError):
                        raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE") from None
                    if len(pcm) + len(delta) + 44 > 5242880:
                        raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE")
                    pcm.extend(delta)
                elif kind == "response.audio.done":
                    audio_done = True
                elif kind == "response.done":
                    if event.get("response", {}).get("status") != "completed" or not audio_done:
                        raise ProviderFailure("VOICE_PROVIDER_FAILED", "DEFINITIVE")
                    response_done = True
                    provider_id = event.get("response", {}).get("id")
                    if provider_id is not None and (not isinstance(provider_id, str) or len(provider_id) > 128):
                        raise ProviderFailure("VOICE_PROVIDER_FAILED", "DEFINITIVE")
                    send(socket, "session.finish")
                elif kind == "session.finished":
                    if not response_done or not audio_done:
                        raise ProviderFailure("VOICE_PROVIDER_FAILED", "DEFINITIVE")
                    return SynthesisResult(pcm_wav(pcm), provider_id, "PROVIDER")
                elif kind == "error":
                    code = str(event.get("error", {}).get("code", ""))
                    problem = "VOICE_CREDENTIAL_INVALID" if code in ("invalid_api_key", "AuthenticationFailed", "InvalidApiKey") else "VOICE_PROVIDER_REJECTED"
                    raise ProviderFailure(problem, "DEFINITIVE")


class ModelProvider:
    def __init__(self, name):
        self.name, self.capability = name, capability(name)

    def ready(self, binding=None, execution=None):
        base = binding["endpoint"] if binding else os.getenv("LN_VOICE_" + self.name + "_URL", "")
        token = (execution or {}).get("credential") if binding else os.getenv("LN_VOICE_" + self.name + "_BEARER", "")
        try:
            value = json.loads(http_call(base, "/internal/model/v1/readiness", token, time.time() + 3))
            if value.get("capability") != self.capability:
                raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED", "DEFINITIVE")
            return value.get("modelReady") is True
        except ProviderFailure:
            if binding is not None:
                raise
            return False
        except Exception:
            return False

    def synthesize(self, text, binding, deadline, execution=None):
        execution = execution or {}
        data = http_call(binding["endpoint"], "/internal/model/v1/synthesize", execution.get("credential"), deadline,
            {"schemaVersion": 1, "text": text, "binding": binding, "deadlineAt": deadline,
             "referenceAudioUrl": execution.get("referenceAudioUrl"),
             "referenceAudioSha256": execution.get("referenceAudioSha256"),
             "referenceAudioBytes": execution.get("referenceAudioBytes")}, limit=5242880)
        return SynthesisResult(data)

    def readiness(self):
        endpoint = os.getenv("LN_VOICE_" + self.name + "_URL", "")
        credential = os.getenv("LN_VOICE_" + self.name + "_BEARER", "")
        try:
            value = json.loads(http_call(endpoint, "/internal/model/v1/readiness", credential, time.time() + 3))
            return {"endpoint": endpoint, "apiReachable": True,
                    "modelReady": value.get("modelReady") is True and value.get("capability") == self.capability}
        except Exception:
            return {"endpoint": endpoint, "apiReachable": False, "modelReady": False}
