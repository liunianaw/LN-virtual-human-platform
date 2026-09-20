"""Official Qwen image adapter with runtime-only credentials.

The adapter follows the validated workflow: persist the provider receipt before
downloading, and recover downloads without another generation POST.
"""

from __future__ import annotations

import base64
import io
import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from PIL import Image


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


class _ImageRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        _validate_image_url(new_url)
        return super().redirect_request(request, fp, code, message, headers, new_url)


def _validate_image_url(value: str) -> None:
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not (parsed.hostname or "").endswith(".aliyuncs.com")
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise ValueError("output_url_not_allowed")


def persist_receipt(path: Path, receipt: dict[str, object]) -> None:
    """Private recovery data, same role as avatar_lab receipt.local.json."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(receipt, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


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
        self._image_http = urllib.request.build_opener(_ImageRedirect())

    @classmethod
    def from_environment(cls) -> "QwenImageProvider":
        return cls(QwenImageSettings.from_environment())

    def generate(self, request: ImageGenerationRequest, receipt_path: Path | None = None, on_receipt=None) -> ImageGenerationResult:
        if receipt_path is not None and receipt_path.is_file():
            return self.recover(receipt_path, on_receipt)
        raw_path = receipt_path.with_name("provider-response.local.json") if receipt_path else None
        if raw_path is not None and raw_path.is_file():
            # Parsing recovery must not submit another paid request.
            parsed = _parse_generation_response(json.loads(raw_path.read_text(encoding="utf-8")), {})
            persist_receipt(receipt_path, parsed)
            return self._complete_download(parsed, receipt_path, on_receipt)
        payload = _build_payload(request)
        diagnostics: dict[str, object] = {}
        try:
            if receipt_path is not None:
                marker = receipt_path.with_name("provider-dispatched.local.json")
                marker.parent.mkdir(parents=True, exist_ok=True)
                with marker.open("x", encoding="utf-8") as stream:
                    json.dump({"submittedAt": time.time()}, stream)
            response_payload = self._post_generation(payload, diagnostics, raw_path)
            if receipt_path is not None:
                persist_receipt(receipt_path.with_name("provider-response.local.json"), response_payload)
            parsed = _parse_generation_response(response_payload, diagnostics)
            if receipt_path is not None:
                persist_receipt(receipt_path, parsed)
            return self._complete_download(parsed, receipt_path, on_receipt)
        except ProviderRejected:
            raise
        except Exception as error:
            diagnostics["reason"] = type(error).__name__
            if isinstance(error, ProviderUncertain):
                diagnostics.update(error.details)
            raise ProviderUncertain(diagnostics) from None

    def recover(self, receipt_path: Path, on_receipt=None) -> ImageGenerationResult:
        """GET/local processing only. This method never generates an image."""
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        return self._complete_download(receipt, receipt_path, on_receipt)

    def submit_async(self, request: ImageGenerationRequest, receipt_path: Path) -> dict[str, object]:
        """Submit once; any persisted submission prevents an automatic repost."""
        raw_path = receipt_path.with_name("provider-submit.local.json")
        diagnostics: dict[str, object] = {}
        try:
            if raw_path.is_file():
                payload = json.loads(raw_path.read_text(encoding="utf-8"))
            else:
                # Marker closes the crash window before a response is received.
                marker = receipt_path.with_name("provider-dispatched.local.json")
                marker.parent.mkdir(parents=True, exist_ok=True)
                with marker.open("x", encoding="utf-8") as stream:
                    json.dump({"submittedAt": time.time()}, stream)
                payload = self._post_generation(_build_payload(request), diagnostics, raw_path, asynchronous=True)
            rejection = _provider_rejection_code(payload)
            if rejection:
                raise ProviderRejected(rejection)
            output = payload.get("output", {})
            task_id = output.get("task_id") if isinstance(output, dict) else None
            if not isinstance(task_id, str) or not _SAFE_TOKEN.fullmatch(task_id):
                raise ProviderUncertain({**diagnostics, "reason": "missing_task_id"})
            receipt = {"providerTaskId": task_id, "requestId": payload.get("request_id"),
                       "stage": output.get("task_status"), "endpoint": self._settings.endpoint,
                       "submittedAt": json.loads(receipt_path.with_name("provider-dispatched.local.json").read_text(encoding="utf-8"))["submittedAt"]}
            persist_receipt(receipt_path, receipt)
            return receipt
        except (ProviderRejected, ProviderUncertain):
            raise
        except Exception as error:
            raise ProviderUncertain({**diagnostics, "reason": type(error).__name__}) from None

    def query_async(self, receipt_path: Path) -> dict[str, object]:
        """One GET only; scheduling, expiry and service authorization belong to the worker."""
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        task_id = receipt.get("providerTaskId")
        if not isinstance(task_id, str) or not _SAFE_TOKEN.fullmatch(task_id):
            raise ValueError("invalid_provider_task_id")
        if receipt.get("endpoint") != self._settings.endpoint:
            raise ProviderConfigurationError("task endpoint differs from submission endpoint")
        submitted_at = receipt.get("submittedAt")
        if not isinstance(submitted_at, (int, float)) or time.time() - submitted_at >= 86400:
            raise ProviderUncertain({"providerTaskId": task_id, "reason": "task_query_expired"})
        request = urllib.request.Request(self._settings.endpoint + "/api/v1/tasks/" + task_id,
                                        headers={"Authorization": "Bearer " + self._settings.api_key})
        http_error = None
        try:
            with self._http.open(request, timeout=45) as response:
                body = response.read(_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            body = error.read(_MAX_RESPONSE_BYTES + 1)
            http_error = error
        raw_path = receipt_path.with_name("provider-query.local.json")
        temporary = raw_path.with_suffix(".tmp")
        temporary.write_bytes(body)
        temporary.replace(raw_path)
        if http_error is not None and http_error.code not in (401, 403, 404):
            raise http_error
        if len(body) > _MAX_RESPONSE_BYTES:
            raise ValueError("response_too_large")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            if http_error is not None:
                raise ProviderRejected(f"HTTP_{http_error.code}") from None
            raise
        rejection = _provider_rejection_code(payload)
        if rejection:
            raise ProviderRejected(rejection)
        output = payload.get("output", {})
        if not isinstance(output, dict) or output.get("task_id") != task_id:
            raise ProviderUncertain({"providerTaskId": task_id, "reason": "task_response_mismatch"})
        status = output.get("task_status")
        if status not in {"PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN"}:
            raise ProviderUncertain({"providerTaskId": task_id, "reason": "invalid_task_status"})
        receipt["stage"] = status
        if status == "SUCCEEDED":
            receipt.update(_parse_generation_response(payload, {}))
        elif status in {"FAILED", "CANCELED"}:
            receipt["errorCode"] = safe_provider_details({"errorCode": output.get("code")}).get("errorCode")
        persist_receipt(receipt_path, receipt)
        return receipt

    def _complete_download(self, receipt, receipt_path, on_receipt):
        diagnostics = {"requestId": receipt["requestId"]}
        try:
            _validate_image_url(receipt["imageUrl"])
            if on_receipt is not None:
                on_receipt(receipt)
            source = receipt_path.with_name("action-board.png") if receipt_path else None
            if source is not None and source.is_file():
                image = source.read_bytes()
            else:
                image = self._download_image(receipt["imageUrl"], diagnostics)
                if source is not None:
                    temporary = source.with_suffix(".tmp")
                    temporary.write_bytes(image)
                    temporary.replace(source)
            return ImageGenerationResult(receipt["requestId"], image, receipt["usage"])
        except Exception as error:
            diagnostics.update({"reason": type(error).__name__, "errorCode": "OUTPUT_DOWNLOAD_PENDING"})
            raise ProviderUncertain(diagnostics) from None

    def _post_generation(self, payload: dict[str, object], diagnostics: dict[str, object], raw_path: Path | None = None, asynchronous: bool = False) -> dict[str, object]:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self._settings.endpoint + ("/api/v1/services/aigc/image-generation/generation" if asynchronous
                                       else "/api/v1/services/aigc/multimodal-generation/generation"),
            data=encoded,
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + self._settings.api_key,
                     **({"X-DashScope-Async": "enable"} if asynchronous else {})},
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
        if raw_path is not None:
            raw_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = raw_path.with_suffix(".tmp")
            temporary.write_bytes(body)
            temporary.replace(raw_path)
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
        _validate_image_url(image_url)
        for attempt in range(3):
            try:
                with self._image_http.open(image_url, timeout=45) as response:
                    diagnostics["httpStatus"] = response.status
                    image = response.read(_MAX_IMAGE_BYTES + 1)
                break
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                if isinstance(error, urllib.error.HTTPError) and error.code in (401, 403, 404):
                    raise
                if attempt == 2:
                    raise
                time.sleep(attempt + 1)
        if len(image) > _MAX_IMAGE_BYTES:
            raise ValueError("image_too_large")
        with Image.open(io.BytesIO(image)) as opened:
            opened.verify()
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
    error_code = _provider_rejection_code(payload)
    if error_code:
        diagnostics["errorCode"] = error_code
        raise ProviderRejected(error_code)
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


def _provider_rejection_code(payload: dict[str, object]) -> str | None:
    code = payload.get("code")
    if not isinstance(code, str):
        return None
    return code if _SAFE_TOKEN.fullmatch(code) else "UPSTREAM_REJECTED"


def _nested_list(payload: dict[str, object], parent: str, child: str) -> list[object]:
    value = payload.get(parent)
    if not isinstance(value, dict):
        return []
    nested = value.get(child)
    return nested if isinstance(nested, list) else []
