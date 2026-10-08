"""Installed adapters own native protocols; the executor owns bounded admission."""
import io
import wave
from dataclasses import dataclass
from typing import Protocol


class ProviderFailure(Exception):
    def __init__(self, code: str, stage: str = "BEFORE_DISPATCH"):
        self.code, self.stage = code, stage


@dataclass
class SynthesisResult:
    audio: bytes
    provider_request_id: str | None = None
    cost_source: str = "SELF_HOSTED"


class Provider(Protocol):
    capability: dict

    def ready(self, binding=None, execution=None) -> bool: ...
    def synthesize(self, text: str, binding: dict, deadline: float, execution=None) -> bytes | SynthesisResult: ...


class FakeProvider:
    """Deterministic protocol fixture, never advertised as a real speech model."""
    capability = {
        "schemaVersion": 1, "providerType": "TEST_TONE", "adapterVersion": "1",
        "capabilityVersion": "test-tone-v1", "modelId": "test-tone", "modelRevision": "1",
        "languages": ["zh-CN", "en-US"],
        "voices": [{"id": "tone", "displayName": "协议测试音（非语音模型）", "languages": ["zh-CN", "en-US"]}],
        "maxInputChars": 200, "parameters": {}, "referenceVoice": False,
        "audioStreaming": False, "cancelMode": "QUEUED_ONLY", "queryMode": "PROCESS",
        "fallbackTarget": True,
    }

    def ready(self, binding=None, execution=None) -> bool:
        return True

    def synthesize(self, text: str, binding: dict, deadline: float, execution=None) -> bytes:
        output = io.BytesIO()
        with wave.open(output, "wb") as audio:
            audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
            audio.writeframes(b"\x00\x00" * 2400)
        return output.getvalue()


def installed_providers():
    """Only trusted adapters shipped in this module may be registered."""
    import os
    from .native_providers import QwenProvider, ModelProvider
    providers = {"TEST_TONE": FakeProvider()} if os.getenv("LN_VOICE_ENABLE_FAKE") == "true" else {}
    if os.getenv("LN_VOICE_ENABLE_QWEN") == "true":
        if os.getenv("LN_VOICE_QWEN_ROUTE") != "executor":
            raise ValueError("Qwen executor requires the mutually exclusive executor route")
        providers["DASHSCOPE_QWEN_TTS"] = QwenProvider()
    for name in ("KOKORO", "COSYVOICE3"):
        if os.getenv("LN_VOICE_ENABLE_" + name) == "true":
            providers[name] = ModelProvider(name)
    return providers
