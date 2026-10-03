"""Thin overlays over pinned official inference. Heavy imports stay in model processes."""
import hashlib
import importlib
import io
import json
import logging
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
import wave
from pathlib import Path
from .build_inputs import verify_source, verify_weights, verify_resources
from .executor import audio_metadata
from .native_providers import NoRedirect, read_bounded, remaining, pcm_wav
from .providers import ProviderFailure


def reference_audio(request, deadline, max_duration_ms=None):
    url, digest, size = (request.get(name) for name in ("referenceAudioUrl", "referenceAudioSha256", "referenceAudioBytes"))
    binding = request["binding"]
    if not binding.get("referenceAssetId") or not binding.get("referenceText") or not isinstance(url, str) \
            or not isinstance(digest, str) or len(digest) != 64 or type(size) is not int or not 44 < size <= 5242880:
        raise ProviderFailure("VOICE_REFERENCE_UNAVAILABLE", "BEFORE_DISPATCH")
    target = urllib.parse.urlsplit(url)
    if target.scheme != "https" or target.username or target.fragment or target.port not in (None, 443) \
            or target.hostname not in os.getenv("LN_VOICE_REFERENCE_HOSTS", "").split(","):
        raise ProviderFailure("VOICE_REFERENCE_UNAVAILABLE", "BEFORE_DISPATCH")
    try:
        with urllib.request.build_opener(NoRedirect).open(url, timeout=remaining(deadline, 5)) as response:
            data = read_bounded(response, size, deadline)
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError()
        audio_metadata(data, size)
        if max_duration_ms is not None:
            with wave.open(io.BytesIO(data), "rb") as audio:
                if audio.getnframes() * 1000 > max_duration_ms * audio.getframerate():
                    raise ValueError()
        return data
    except Exception:
        raise ProviderFailure("VOICE_REFERENCE_UNAVAILABLE", "BEFORE_DISPATCH") from None


def encode_chunks(chunks, rate, deadline):
    import torch
    import torchaudio
    pcm = bytearray()
    for chunk in chunks:
        remaining(deadline)
        value = chunk.detach().float().cpu().reshape(1, -1)
        if not torch.isfinite(value).all():
            raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE")
        if rate != 24000:
            value = torchaudio.functional.resample(value, rate, 24000)
        if (value.numel() * 2) + len(pcm) + 44 > 5242880:
            raise ProviderFailure("VOICE_AUDIO_INVALID", "DEFINITIVE")
        pcm.extend((value.clamp(-1, 1) * 32767).round().to(torch.int16).numpy().astype("<i2").tobytes())
    remaining(deadline)
    return pcm_wav(pcm)


def mixed_g2p(text, zh, en):
    # Keep the v1 Chinese phone inventory; its legacy branch ignores en_callable.
    parts = re.split(r"([A-Za-z]+(?:[ '-]+[A-Za-z]+)*)", text)
    return " ".join((en if i % 2 else zh)(part)[0] for i, part in enumerate(parts) if part.strip()), None


class KokoroBackend:
    def __init__(self, weights, descriptor):
        from kokoro import KModel, KPipeline
        from loguru import logger
        import torch
        logger.disable("kokoro")
        logger.disable("misaki")
        torch.set_num_threads(int(os.getenv("LN_VOICE_CPU_THREADS", "2")))
        model = KModel(repo_id="hexgrad/Kokoro-82M", config=str(weights / "config.json"),
                       model=str(weights / "kokoro-v1_0.pth")).to("cpu").eval()
        en = KPipeline(lang_code="a", repo_id="hexgrad/Kokoro-82M", model=model)
        zh = KPipeline(lang_code="z", repo_id="hexgrad/Kokoro-82M", model=model)
        chinese_g2p = zh.g2p
        zh.g2p = lambda text: mixed_g2p(text, chinese_g2p, en.g2p)
        self.pipelines, self.weights = {"zh-CN": zh, "en-US": en}, weights
        for voice in descriptor["voices"]:
            for language in voice["languages"]:
                self.pipelines[language].load_voice(str(weights / "voices" / (voice["id"] + ".pt")))

    def synthesize(self, request):
        binding = request["binding"]
        if binding.get("referenceAssetId"):
            raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED", "BEFORE_DISPATCH")
        pipeline = self.pipelines[binding["language"]]
        voice = str(self.weights / "voices" / (binding["providerVoiceRef"] + ".pt"))
        # Official non-English pipeline truncates >510 phonemes. Reject before inference instead.
        if binding["language"] == "zh-CN" and len(pipeline.g2p(request["text"])[0]) > 510:
            raise ProviderFailure("VOICE_PARAMETER_INVALID", "BEFORE_DISPATCH")
        output = pipeline(request["text"], voice=voice, speed=binding["parameters"].get("speed", 1))
        return encode_chunks((item.audio for item in output), 24000, request["deadlineAt"])


class CosyVoiceBackend:
    def __init__(self, weights, descriptor):
        from cosyvoice.cli.cosyvoice import CosyVoice3
        import torch
        if not torch.cuda.is_available():
            raise ValueError("CosyVoice3 runtime requires its configured GPU")
        self.model = CosyVoice3(model_dir=str(weights), load_trt=False, load_vllm=False, fp16=False)
        if self.model.frontend.text_frontend != "wetext" or not all(
                getattr(self.model.frontend, name, None) for name in ("zh_tn_model", "en_tn_model")):
            raise ValueError("Required local text frontend is not ready")
        self.rate = self.model.sample_rate
        self.max_reference_ms = descriptor["referenceMaxDurationMs"]

    def synthesize(self, request):
        data = reference_audio(request, request["deadlineAt"], self.max_reference_ms)
        binding = request["binding"]
        # Only a temporary server-owned file is passed to official prompt processing.
        with tempfile.TemporaryDirectory(prefix="ln-voice-reference-") as directory:
            path = Path(directory) / "reference.wav"
            path.write_bytes(data)
            prompt = "You are a helpful assistant.<|endofprompt|>" + binding["referenceText"]
            output = self.model.inference_zero_shot(request["text"], prompt, str(path), stream=False,
                         speed=binding["parameters"].get("speed", 1), text_frontend=True)
            return encode_chunks((item["tts_speech"] for item in output), self.rate, request["deadlineAt"])


def load_backend(name, source, weights, lock, descriptor):
    if lock["providerType"] != name or lock["weights"]["revision"] != descriptor["modelRevision"]:
        raise ValueError("Declared model does not match locked runtime inputs")
    source = verify_source(source, lock)
    weights = verify_weights(weights, lock)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    sys.path.insert(0, str(source))
    if name == "COSYVOICE3":
        resource_lock = Path(os.environ["LN_VOICE_TEXT_RESOURCE_LOCK"])
        resource_data = resource_lock.read_text(encoding="utf-8")
        if hashlib.sha256(resource_data.encode()).hexdigest() != lock["textResourceLockSha256"]:
            raise ValueError("Text resource manifest does not match runtime lock")
        resources = verify_resources(os.environ["LN_VOICE_TEXT_RESOURCE_DIR"], json.loads(resource_data))
        os.environ["LN_VOICE_WETEXT_DIR"] = str(resources)
        sys.path.insert(1, str(source / "third_party/Matcha-TTS"))
    module = importlib.import_module("kokoro" if name == "KOKORO" else "cosyvoice.cli.cosyvoice")
    loaded_path = Path(module.__file__).resolve()
    if not loaded_path.is_relative_to(source):
        raise ValueError("Model imported outside pinned official checkout")
    logging.getLogger().setLevel(logging.ERROR)
    backend = (KokoroBackend if name == "KOKORO" else CosyVoiceBackend)(weights, descriptor)
    return backend, str(loaded_path)
