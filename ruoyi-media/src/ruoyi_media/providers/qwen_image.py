"""Official Qwen image adapter with runtime-only credentials.

The adapter is deliberately isolated from task orchestration.  It never reads
repository configuration files, persists responses, follows redirects, or
includes an API key in an exception.
"""

from __future__ import annotations

import base64
import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit


_OFFICIAL_HOSTS = re.compile(r"(?:dashscope\.aliyuncs\.com|[a-zA-Z0-9-]+\.cn-beijing\.maas\.aliyuncs\.com)")
_SAFE_TOKEN = re.compile(r"[A-Za-z0-9_.:-]{1,100}")
_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
_MAX_IMAGE_BYTES = 25 * 1024 * 1024


class ProviderConfigurationError(ValueError):
    """Runtime configuration is absent or unsafe."""


class ProviderRejected(RuntimeError):
    """The provider gave a definite pre-generation rejection."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class ProviderUncertain(RuntimeError):
    """The provider may have accepted a paid request; it must be reconciled."""

    def __init__(self, details: dict[str, object]) -> None:
        self.details = safe_provider_details(details)
        super().__init__("provider result is unknown")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):  # type: ignore[no-untyped-def]
        return None


@dataclass(frozen=True)
class QwenImageSettings:
    """Non-secret endpoint plus an injected, process-local bearer credential."""

    api_key: str
    endpoint: str

    @classmethod
    def from_environment(cls) -> "QwenImageSettings":
        api_key = os.environ.get("RUOYI_MEDIA_QWEN_API_KEY", "")
        if not api_key or api_key.strip() != api_key or any(character.isspace() for character in api_key):
            raise ProviderConfigurationError("official image provider credential is not configured")
        endpoint = validate_official_endpoint(
            os.environ.get("RUOYI_MEDIA_QWEN_ENDPOINT", "https://dashscope.aliyuncs.com")
        )
        return cls(api_key=api_key, endpoint=endpoint)


@dataclass(frozen=True)
class ImageGenerationRequest:
    model: str
    prompt: str
    reference_png: bytes
    layout_guide_png: bytes | None
    parameters: dict[str, object]


@dataclass(frozen=True)
class ImageGenerationResult:
    request_id: str
    image_png: bytes
    usage: dict[str, object]


def validate_official_endpoint(endpoint: str) -> str:
    parsed = urlsplit(endpoint)
    if (
        parsed.scheme != "https"
        or not _OFFICIAL_HOSTS.fullmatch(parsed.hostname or "")
        or parsed.username
        or parsed.password
        or parsed.port not in (None, 443)
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise ProviderConfigurationError("official image endpoint must be an approved HTTPS host")
    return endpoint.rstrip("/")


def safe_provider_details(details: dict[str, object]) -> dict[str, object]:
    """Keep only bounded diagnostic fields safe for platform task state."""

    safe: dict[str, object] = {}
    for name in ("requestId", "providerTaskId", "errorCode", "reason"):
        value = details.get(name)
        if isinstance(value, str) and _SAFE_TOKEN.fullmatch(value):
            safe[name] = value
    for name in ("httpStatus", "imageCount", "inputImageCount", "outputImageCount"):
        value = details.get(name)
        if type(value) is int:
            safe[name] = value
    return safe


class QwenImageProvider:
    """Synchronous official image call; callers own attempt and lease state."""

    def __init__(self, settings: QwenImageSettings) -> None:
        self._settings = settings
        self._http = urllib.request.build_opener(_NoRedirect())

    @classmethod
    def from_environment(cls) -> "QwenImageProvider":
        return cls(QwenImageSettings.from_environment())

    def generate(self, request: ImageGenerationRequest) -> ImageGenerationResult:
        payload = _build_payload(request)
        diagnostics: dict[str, object] = {}
        try:
            response_payload = self._post_generation(payload, diagnostics)
            parsed = _parse_generation_response(response_payload, diagnostics)
            image = self._download_image(parsed["imageUrl"], diagnostics)
            return ImageGenerationResult(
                request_id=parsed["requestId"],
                image_png=image,
                usage=parsed["usage"],
            )
        except ProviderRejected:
            raise
        except Exception as error:
            diagnostics["reason"] = type(error).__name__
            raise ProviderUncertain(diagnostics) from None

    def _post_generation(self, payload: dict[str, object], diagnostics: dict[str, object]) -> dict[str, object]:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self._settings.endpoint + "/api/v1/services/aigc/multimodal-generation/generation",
            data=encoded,
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + self._settings.api_key},
        )
        started = time.monotonic()
        print(json.dumps({"event": "provider_http_start", "model": payload["model"],
                          "host": urlsplit(self._settings.endpoint).hostname, "requestBytes": len(encoded)}), flush=True)
        try:
            with self._http.open(request, timeout=300) as response:
                diagnostics["httpStatus"] = response.status
                body = response.read(_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            diagnostics["httpStatus"] = error.code
            body = error.read(_MAX_RESPONSE_BYTES + 1)
        if len(body) > _MAX_RESPONSE_BYTES:
            raise ValueError("response_too_large")
        parsed = json.loads(body)
        if not isinstance(parsed, dict):
            raise ValueError("response_not_object")
        print(json.dumps({"event": "provider_http_response", "seconds": round(time.monotonic() - started, 2),
                          **safe_provider_details({"httpStatus": diagnostics.get("httpStatus"),
                                                   "requestId": parsed.get("request_id"), "errorCode": parsed.get("code")})}), flush=True)
        return parsed

    def _download_image(self, image_url: str, diagnostics: dict[str, object]) -> bytes:
        parsed = urlsplit(image_url)
        if (
            parsed.scheme != "https"
            or not (parsed.hostname or "").endswith(".aliyuncs.com")
            or parsed.username
            or parsed.password
            or parsed.port not in (None, 443)
        ):
            raise ValueError("output_url_not_allowed")
        with self._http.open(image_url, timeout=120) as response:
            diagnostics["httpStatus"] = response.status
            image = response.read(_MAX_IMAGE_BYTES + 1)
        if len(image) > _MAX_IMAGE_BYTES:
            raise ValueError("image_too_large")
        return image


def _build_payload(request: ImageGenerationRequest) -> dict[str, object]:
    if not _SAFE_TOKEN.fullmatch(request.model):
        raise ValueError("invalid_model")
    if not request.prompt or len(request.prompt) > 8_000 or len(request.reference_png) > 10 * 1024 * 1024:
        raise ValueError("invalid_generation_input")
    content: list[dict[str, str]] = [
        {"image": "data:image/png;base64," + base64.b64encode(request.reference_png).decode("ascii")},
    ]
    if request.layout_guide_png is not None:
        if len(request.layout_guide_png) > 10 * 1024 * 1024:
            raise ValueError("layout_guide_too_large")
        content.append({"image": "data:image/png;base64," + base64.b64encode(request.layout_guide_png).decode("ascii")})
    content.append({"text": request.prompt})
    allowed_parameters = {"n", "size", "prompt_extend", "watermark", "seed"}
    parameters = {name: value for name, value in request.parameters.items() if name in allowed_parameters}
    parameters["n"] = 1
    parameters["size"] = "1536*1536"
    parameters["watermark"] = False
    return {
        "model": request.model,
        "input": {"messages": [{"role": "user", "content": content}]},
        "parameters": parameters,
    }


def _parse_generation_response(payload: dict[str, object], diagnostics: dict[str, object]) -> dict[str, object]:
    request_id = payload.get("request_id")
    if isinstance(request_id, str):
        diagnostics["requestId"] = request_id
    error_code = payload.get("code")
    if isinstance(error_code, str):
        diagnostics["errorCode"] = error_code
        if error_code in {"InvalidApiKey", "InvalidParameter", "InvalidParameter.DataInspectionFailed", "AccessDenied", "ModelNotFound"}:
            raise ProviderRejected(error_code)
        raise ValueError("upstream_error")
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        raise ValueError("missing_usage")
    for response_name, diagnostic_name in (
        ("image_count", "imageCount"),
        ("input_image_count", "inputImageCount"),
        ("output_image_count", "outputImageCount"),
    ):
        if type(usage.get(response_name)) is int:
            diagnostics[diagnostic_name] = usage[response_name]
    output_count = usage.get("output_image_count", usage.get("image_count"))
    if type(output_count) is not int or output_count != 1:
        raise ValueError("unexpected_output_count")
    image_urls: list[str] = []
    for choice in _nested_list(payload, "output", "choices"):
        if not isinstance(choice, dict):
            continue
        message = choice.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), list):
            continue
        for item in message["content"]:
            if isinstance(item, dict) and isinstance(item.get("image"), str):
                image_urls.append(item["image"])
    if len(image_urls) != 1 or not isinstance(request_id, str) or not _SAFE_TOKEN.fullmatch(request_id):
        raise ValueError("ambiguous_generation_result")
    return {"requestId": request_id, "imageUrl": image_urls[0], "usage": usage}


def _nested_list(payload: dict[str, object], parent: str, child: str) -> list[object]:
    value = payload.get(parent)
    if not isinstance(value, dict):
        return []
    nested = value.get(child)
    return nested if isinstance(nested, list) else []
