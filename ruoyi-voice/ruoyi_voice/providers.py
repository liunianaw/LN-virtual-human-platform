"""Installed adapters own native protocols; the executor owns bounded admission."""
import io
import wave
from typing import Protocol


class ProviderFailure(Exception):
    def __init__(self, code: str, stage: str = "BEFORE_DISPATCH"):
        self.code, self.stage = code, stage


class Provider(Protocol):
    capability: dict

    def ready(self) -> bool: ...
    def synthesize(self, text: str, binding: dict, deadline: float) -> bytes: ...


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

    def ready(self) -> bool:
        return True

    def synthesize(self, text: str, binding: dict, deadline: float) -> bytes:
        output = io.BytesIO()
        with wave.open(output, "wb") as audio:
            audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
            audio.writeframes(b"\x00\x00" * 2400)
        return output.getvalue()


def installed_providers():
    """Only trusted adapters shipped in this module may be registered."""
    import os
    return {"TEST_TONE": FakeProvider()} if os.getenv("LN_VOICE_ENABLE_FAKE") == "true" else {}
