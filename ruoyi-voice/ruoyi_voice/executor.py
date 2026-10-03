"""One process, finite queue/results; durable one-shot permits live in Session."""
import hashlib
import io
import json
import math
import struct
import threading
import time
import uuid
import wave
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from .providers import ProviderFailure, SynthesisResult


class Problem(Exception):
    def __init__(self, code, status=400):
        self.code, self.status = code, status


def validate_binding(binding, cap):
    if any(binding.get(name) != cap[name] for name in ("providerType", "modelId", "capabilityVersion", "modelRevision")) \
            or binding.get("language") not in cap["languages"]:
        raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED", "DEFINITIVE")
    if binding.get("referenceAssetId"):
        if not cap["referenceVoice"] or not binding.get("referenceText"):
            raise ProviderFailure("VOICE_REFERENCE_UNAVAILABLE", "DEFINITIVE")
    elif not any(v["id"] == binding.get("providerVoiceRef") and binding["language"] in v["languages"] for v in cap["voices"]):
        raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED", "DEFINITIVE")
    for name, value in binding.get("parameters", {}).items():
        rule = cap["parameters"].get(name)
        if rule is None or type(value) not in (int, float) or not math.isfinite(value) or not rule["minimum"] <= value <= rule["maximum"]:
            raise ProviderFailure("VOICE_PARAMETER_INVALID", "DEFINITIVE")


def audio_metadata(data: bytes, maximum: int) -> dict:
    try:
        if not 44 < len(data) <= maximum or data[:4] != b"RIFF" or int.from_bytes(data[4:8], "little") + 8 != len(data):
            raise ValueError()
        if data[8:12] != b"WAVE":
            raise ValueError()
        offset, fmt, pcm = 12, False, False
        while offset < len(data):
            if offset + 8 > len(data):
                raise ValueError()
            kind, size = data[offset:offset+4], int.from_bytes(data[offset+4:offset+8], "little")
            start, end = offset + 8, offset + 8 + size
            if end > len(data):
                raise ValueError()
            if kind == b"fmt ":
                if fmt or size != 16 or struct.unpack("<HHIIHH", data[start:end]) != (1, 1, 24000, 48000, 2, 16):
                    raise ValueError()
                fmt = True
            if kind == b"data":
                if pcm or not size or size % 2:
                    raise ValueError()
                pcm = True
            offset = end + size % 2
        if offset != len(data) or not fmt or not pcm:
            raise ValueError()
        with wave.open(io.BytesIO(data), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 24000, "NONE"):
                raise ValueError()
            frames = wav.getnframes()
            if frames < 1 or len(wav.readframes(frames)) != frames * 2:
                raise ValueError()
        return {"mimeType": "audio/wav", "codec": "PCM_S16LE", "sampleRateHz": 24000,
                "channels": 1, "sampleWidthBits": 16, "byteLength": len(data),
                "durationMs": max(1, frames * 1000 // 24000), "sha256": hashlib.sha256(data).hexdigest()}
    except (ValueError, EOFError, wave.Error):
        raise Problem("VOICE_AUDIO_INVALID") from None


@dataclass
class Attempt:
    request: dict
    signature: str = ""
    state: str = "QUEUED"
    created: float = field(default_factory=time.monotonic)
    audio: bytes | None = None
    event: dict | None = None
    cancelled: bool = False
    delivered: bool = False
    deliveries: int = 0


class Executor:
    def __init__(self, providers, authorize, event, concurrency=1, capacity=8, ttl=900, max_bytes=134217728, queue_timeout=10):
        if not 1 <= concurrency <= 16 or not 1 <= capacity <= 128 or not 1 <= ttl <= 3600 or not 1048576 <= max_bytes <= 536870912:
            raise ValueError("Invalid bounded execution configuration")
        if not 1 <= queue_timeout <= 60: raise ValueError("Invalid queue timeout")
        self.providers, self.authorize, self.emit = providers, authorize, event
        self.boot = str(uuid.uuid4())
        self.pool = ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="voice")
        self.slots = threading.BoundedSemaphore(concurrency + capacity)
        self.lock, self.attempts = threading.RLock(), {}
        self.ttl, self.max_bytes, self.used = ttl, max_bytes, 0
        self.queue_timeout = queue_timeout

    def submit(self, request, key):
        allowed = {"schemaVersion", "traceId", "taskId", "attemptId", "taskRevision", "requestHash", "voiceVersionId",
                   "dispatchToken", "text", "textHash", "inputCharCount", "deadlineAt", "maxAudioBytes"}
        if set(request) != allowed or request["schemaVersion"] != 1 or key != request["attemptId"]:
            raise Problem("VOICE_PARAMETER_INVALID")
        for name in ("taskId", "attemptId", "voiceVersionId"):
            if not isinstance(request[name], str) or not request[name].isascii() or not request[name].isdigit() or not 1 <= len(request[name]) <= 19 or int(request[name]) <= 0 or int(request[name]) > 9223372036854775807:
                raise Problem("VOICE_PARAMETER_INVALID")
        text = request["text"]
        if not isinstance(text, str) or not text.strip() or len(text) > 200 or len(text) != request["inputCharCount"]:
            raise Problem("VOICE_PARAMETER_INVALID")
        text_hash = hashlib.sha256(text.encode()).hexdigest()
        expected = hashlib.sha256("\n".join((request["taskId"], key, request["voiceVersionId"], text_hash)).encode()).hexdigest()
        if request["textHash"] != text_hash or request["requestHash"] != expected:
            raise Problem("VOICE_REQUEST_CONFLICT", 409)
        if type(request["taskRevision"]) is not int or request["taskRevision"] not in (1, 2) or not isinstance(request["dispatchToken"], str) or not 1 <= len(request["dispatchToken"]) <= 128:
            raise Problem("VOICE_PARAMETER_INVALID")
        if not isinstance(request["maxAudioBytes"], int) or not 44 < request["maxAudioBytes"] <= 5242880:
            raise Problem("VOICE_PARAMETER_INVALID")
        deadline = datetime.fromisoformat(request["deadlineAt"].replace("Z", "+00:00")).timestamp()
        with self.lock:
            signature = hashlib.sha256(json.dumps(request, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            old = self.attempts.get(key)
            if old:
                if old.signature != signature:
                    raise Problem("VOICE_REQUEST_CONFLICT", 409)
                return self.status(key)
            if not time.time() < deadline <= time.time() + 180:
                raise Problem("VOICE_CANCELLED", 409)
            if len(self.attempts) >= 4096 or not self.slots.acquire(blocking=False):
                raise Problem("VOICE_QUEUE_FULL", 429)
            item = Attempt(dict(request), signature)
            self.attempts[key] = item
            self.pool.submit(self._run, key, item, deadline)
            return self.status(key)

    def status(self, key):
        with self.lock:
            item = self.attempts.get(key)
            if not item:
                raise Problem("VOICE_OUTCOME_UNKNOWN", 404)
            return {"attemptId": key, "state": item.state, "queryMode": "PROCESS", "workerBootId": self.boot,
                    "event": item.event, "resultAvailable": item.audio is not None, "callbackAttempts": item.deliveries, "reviewRequired": not item.delivered and item.deliveries >= 12}

    def cancel(self, key):
        with self.lock:
            item = self.attempts.get(key)
            if not item:
                raise Problem("VOICE_OUTCOME_UNKNOWN", 404)
            item.cancelled = True
            return {"attemptId": key, "cancelMode": "QUEUED_ONLY", "state": item.state}

    def read_audio(self, key):
        with self.lock:
            item = self.attempts.get(key)
            if not item or item.audio is None:
                raise Problem("VOICE_RESULT_EXPIRED", 410)
            return item.audio

    def _run(self, key, item, deadline):
        stage, code, metadata = "BEFORE_DISPATCH", None, None
        try:
            with self.lock:
                if item.cancelled or time.time() >= deadline:
                    raise ProviderFailure("VOICE_CANCELLED")
                if time.monotonic() - item.created >= self.queue_timeout:
                    raise ProviderFailure("VOICE_QUEUE_FULL")
                # From here a lost response is uncertain. Never ask for a second permit.
                item.state = "DISPATCHING"
            stage = "DISPATCHING"
            grant = self.authorize(key, {"taskRevision": item.request["taskRevision"],
                "workerInstanceId": "voice", "workerBootId": self.boot, "dispatchToken": item.request["dispatchToken"]})
            if not grant.get("authorized"):
                raise ProviderFailure("VOICE_CANCELLED", "BEFORE_DISPATCH")
            binding = grant["binding"]
            execution = grant.get("execution")
            provider = self.providers.get(binding["providerType"])
            if provider is None or not provider.ready(binding, execution):
                raise ProviderFailure("VOICE_PROVIDER_NOT_READY", "DEFINITIVE")
            cap = provider.capability
            # Old test fixtures omit modelId; production grants always carry a complete binding.
            validated = dict(binding, modelId=binding.get("modelId", cap["modelId"]))
            if binding["capabilityVersion"] in getattr(provider, "compatible_capability_versions", ()):
                validated["capabilityVersion"] = cap["capabilityVersion"]
            validate_binding(validated, cap)
            if len(item.request["text"]) > cap["maxInputChars"]:
                raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED", "DEFINITIVE")
            if time.time() >= deadline:
                raise ProviderFailure("VOICE_CANCELLED", "BEFORE_DISPATCH")
            result = provider.synthesize(item.request["text"], binding, deadline, execution)
            data = result.audio if isinstance(result, SynthesisResult) else result
            metadata = audio_metadata(data, item.request["maxAudioBytes"])
            with self.lock:
                if self.used + len(data) > self.max_bytes:
                    raise ProviderFailure("VOICE_QUEUE_FULL", "DEFINITIVE")
                item.audio = data
                self.used += len(data)
                item.state = "SUCCEEDED"
        except ProviderFailure as error:
            code, stage = error.code, error.stage
            item.state = "CANCELLED" if code == "VOICE_CANCELLED" else "FAILED"
        except Problem as error:
            code, stage, item.state = error.code, "DEFINITIVE", "FAILED"
        except Exception:
            code, item.state = "VOICE_OUTCOME_UNKNOWN", "UNKNOWN"
        finally:
            with self.lock:
                item.event = {"eventId": str(uuid.uuid4()), "attemptId": key, "taskRevision": item.request["taskRevision"],
                    "requestHash": item.request["requestHash"], "workerInstanceId": "voice", "workerBootId": self.boot,
                    "state": item.state, "errorCode": code, "failureStage": stage, "audio": metadata,
                    "providerRequestId": result.provider_request_id if isinstance(locals().get("result"), SynthesisResult) else None,
                    "costSource": result.cost_source if isinstance(locals().get("result"), SynthesisResult) else ("TEST" if "provider" in locals() and binding["providerType"].startswith("TEST_") else "UNKNOWN"),
                    "modelRevision": binding["modelRevision"] if "binding" in locals() else None}
                item.request.pop("text", None)
                item.request.pop("dispatchToken", None)
            self.slots.release()
            self._deliver(key, item)

    def _deliver(self, key, item):
        with self.lock:
            if item.delivered or item.deliveries >= 12: return
            item.deliveries += 1
        try:
            self.emit(key, item.event)
            item.delivered = True
        except Exception:
            pass  # Maintenance retries facts only; synthesis is never repeated.

    def maintain(self):
        with self.lock:
            finished = [(key, item) for key, item in self.attempts.items() if item.event]
        retried = 0
        for key, item in finished:
            if time.monotonic() - item.created > self.ttl:
                with self.lock:
                    self.used -= len(item.audio or b"")
                    self.attempts.pop(key, None)
            elif not item.delivered and item.deliveries < 12 and retried < 8:
                retried += 1
                self._deliver(key, item)

    def close(self):
        self.pool.shutdown(wait=True, cancel_futures=False)
