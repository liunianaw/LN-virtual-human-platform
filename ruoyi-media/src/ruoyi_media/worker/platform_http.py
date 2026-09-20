"""Fail-closed HTTP implementation of the system generation Worker port."""

from __future__ import annotations

import io
import json
import os
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Mapping
from urllib.parse import urlsplit

from PIL import Image, ImageOps
from ruoyi_media.providers.avatar_prompts import prompt as validated_action_prompt

from .generation import (
    AvatarGenerationRequested,
    ClaimedActionStep,
    GenerationStepResult,
    GenerationPlatformPort,
    PreparedAttempt,
    StepStatus,
    StaleLeaseError,
)


_SAFE_TOKEN = re.compile(r"[A-Za-z0-9._:-]{1,128}")
_MAX_JSON_BYTES = 1024 * 1024
_MAX_REFERENCE_BYTES = 10 * 1024 * 1024
_ACTION_PROMPTS = {
    "idle": "idle, relaxed neutral pose",
    "speaking": "speaking, natural explanatory gesture",
    "listening": "listening attentively, receptive pose",
    "thinking": "thinking, reflective pose",
    "nod": "nodding, affirmative gesture",
    "shake_head": "shaking head, negative gesture",
    "wave": "waving hello with one hand",
    "happy": "happy, warm welcoming pose",
}


class PlatformConfigurationError(ValueError):
    """The internal platform connection was not explicitly configured."""


class PlatformTransportError(RuntimeError):
    """The platform did not return a usable Worker API response."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):  # type: ignore[no-untyped-def]
        return None


class _CosRedirect(urllib.request.HTTPRedirectHandler):
    """Follow only the HTTPS COS redirects emitted for system-signed reads."""

    def redirect_request(self, request, fp, code, message, headers, new_url):  # type: ignore[no-untyped-def]
        parsed = urlsplit(new_url)
        if (
            parsed.scheme != "https"
            or not (parsed.hostname or "").endswith(".myqcloud.com")
            or parsed.username
            or parsed.password
            or parsed.port not in (None, 443)
        ):
            return None
        return super().redirect_request(request, fp, code, message, headers, new_url)


@dataclass(frozen=True)
class PlatformHttpSettings:
    base_url: str
    internal_token: str

    @classmethod
    def from_environment(cls) -> "PlatformHttpSettings":
        base_url = os.environ.get("RUOYI_MEDIA_INTERNAL_PLATFORM_URL", "")
        internal_token = os.environ.get("RUOYI_MEDIA_INTERNAL_TOKEN", "")
        if not internal_token or internal_token.strip() != internal_token or any(character.isspace() for character in internal_token):
            raise PlatformConfigurationError("platform internal token is not configured")
        return cls(base_url=validate_platform_url(base_url), internal_token=internal_token)


def validate_platform_url(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise PlatformConfigurationError("platform URL must be an HTTP(S) origin without credentials")
    return value.rstrip("/")


class SystemGenerationPlatform(GenerationPlatformPort):
    """Maps the media worker protocol to `/asset/internal/generation/*`."""

    def __init__(self, settings: PlatformHttpSettings) -> None:
        self._settings = settings
        self._http = urllib.request.build_opener(_NoRedirect())
        self._cos_http = urllib.request.build_opener(_CosRedirect())

    @classmethod
    def from_environment(cls) -> "SystemGenerationPlatform":
        return cls(PlatformHttpSettings.from_environment())

    def release_preflight_claim(self, claim: ClaimedActionStep, code: str) -> None:
        """Return a leased, never-submitted step to READY for a repaired Worker."""
        self._post("/asset/internal/generation/claim-release", {
            **self._lease_payload(claim),
            "errorCode": _safe_token(code, "errorCode"),
        })

    def has_active_steps(self, event: AvatarGenerationRequested) -> bool:
        data = self._post("/asset/internal/generation/task-active", {
            "accountId": _positive_int(event.account_id, "accountId"),
            "taskId": _positive_int(event.task_id, "taskId"),
        })
        if type(data) is not bool:
            raise PlatformTransportError("platform task-active response is invalid")
        return data

    def claim_next_action_step(self, event: AvatarGenerationRequested, worker_id: str) -> ClaimedActionStep | None:
        self._worker_id = _safe_token(worker_id, "workerId")
        data = self._post("/asset/internal/generation/claim", {
            "accountId": _positive_int(event.account_id, "accountId"),
            "taskId": _positive_int(event.task_id, "taskId"),
            "workerId": self._worker_id,
        })
        if data is None:
            return None
        if not isinstance(data, Mapping):
            raise PlatformTransportError("platform claim response is invalid")
        account_id = str(_positive_int(data.get("accountId"), "accountId"))
        task_id = str(_positive_int(data.get("taskId"), "taskId"))
        if account_id != event.account_id or task_id != event.task_id:
            raise PlatformTransportError("platform claim does not match event")
        action = data.get("action")
        if not isinstance(action, str) or action not in _ACTION_PROMPTS:
            raise PlatformTransportError("platform claim action is invalid")
        lease_seconds = data.get("leaseSeconds")
        if type(lease_seconds) is not int or not 1 <= lease_seconds <= 300:
            raise PlatformTransportError("platform lease duration is invalid")
        claim = ClaimedActionStep(
            account_id=account_id,
            task_id=task_id,
            step_id=str(_positive_int(data.get("stepId"), "stepId")),
            action=action,
            attempt_no=_positive_int(data.get("attemptNo"), "attemptNo"),
            lease_epoch=_positive_int(data.get("leaseEpoch"), "leaseEpoch"),
            lease_expires_at=datetime.now(UTC) + timedelta(seconds=lease_seconds),
            reference_png=b"",
            layout_guide_png=None,
            prompt="",
            model="",
            parameters={},
            output_prefix="",
        )
        try:
            model = _safe_token(_required_string(data.get("model"), "model"), "model")
            parameters = _parameters(data.get("parametersJson"))
            output_prefix = _output_prefix(_required_string(data.get("outputPrefix"), "outputPrefix"))
            reference_png = self._read_reference(_required_string(data.get("referenceUrl"), "referenceUrl"))
            reference_png, layout_guide_png = _generation_references(reference_png)
        except PlatformTransportError as error:
            # The platform lease is already RUNNING at this point, but no
            # provider request or attempt exists.  Return it to READY so an
            # operator can repair the preflight condition and resume the
            # user-requested task without replaying a paid provider request.
            self.release_preflight_claim(claim, _preflight_error_code(error))
            raise PreflightClaimFailure(_preflight_error_code(error)) from error
        return ClaimedActionStep(
            account_id=claim.account_id,
            task_id=claim.task_id,
            step_id=claim.step_id,
            action=claim.action,
            attempt_no=claim.attempt_no,
            lease_epoch=claim.lease_epoch,
            lease_expires_at=claim.lease_expires_at,
            reference_png=reference_png,
            layout_guide_png=layout_guide_png,
            prompt=_action_prompt(action),
            model=model,
            parameters=parameters,
            output_prefix=output_prefix,
        )

    def prepare_attempt(self, claim: ClaimedActionStep, request_hash: str) -> PreparedAttempt:
        data = self._post("/asset/internal/generation/attempts", {
            **self._lease_payload(claim),
            "requestHash": request_hash,
        })
        if not isinstance(data, Mapping):
            raise PlatformTransportError("platform attempt response is invalid")
        receipt = data.get("receiptJson")
        if isinstance(receipt, str):
            receipt = json.loads(receipt)
        if receipt is not None and not isinstance(receipt, dict):
            raise PlatformTransportError("platform attempt receipt is invalid")
        return PreparedAttempt(
            attempt_id=str(_positive_int(data.get("attemptId"), "attemptId")),
            provider_request_key=_safe_token(_required_string(data.get("providerRequestKey"), "providerRequestKey"), "providerRequestKey"),
            recovery_only=data.get("recoveryOnly") is True,
            receipt=receipt,
        )

    def progress(
        self,
        claim: ClaimedActionStep,
        attempt: PreparedAttempt,
        state: StepStatus,
        provider_request_id: str | None,
        details: dict[str, object] | None = None,
    ) -> None:
        if state not in {StepStatus.RUNNING, StepStatus.POLLING, StepStatus.UNKNOWN, StepStatus.FAILED}:
            raise ValueError("unsupported progress state")
        self._post("/asset/internal/generation/progress", {
            **self._lease_payload(claim),
            "attemptId": _positive_int(attempt.attempt_id, "attemptId"),
            "state": state.value,
            "providerRequestId": provider_request_id,
            "errorCode": _error_code(details),
        })

    def save_receipt(self, claim: ClaimedActionStep, attempt: PreparedAttempt, receipt: dict[str, object]) -> None:
        self._post("/asset/internal/generation/receipt", {
            **self._lease_payload(claim),
            "attemptId": _positive_int(attempt.attempt_id, "attemptId"),
            "receipt": receipt,
        })

    def submit_result(self, result: GenerationStepResult) -> None:
        common = {
            "accountId": _positive_int(result.account_id, "accountId"),
            "taskId": _positive_int(result.task_id, "taskId"),
            "stepId": _positive_int(result.step_id, "stepId"),
            "attemptId": _positive_int(result.attempt_id, "attemptId"),
            "leaseEpoch": result.lease_epoch,
        }
        if result.status is StepStatus.SUCCEEDED:
            self._post("/asset/internal/generation/results/succeeded", {
                **common,
                "workerId": self._worker_id_from_result(result),
                "objects": [
                    {
                        "objectKey": item.object_key,
                        "sha256": item.sha256,
                        "sizeBytes": item.size_bytes,
                        "contentType": item.content_type,
                    }
                    for item in result.objects
                ],
                "manifest": result.manifest or {},
            })
            return
        if result.status not in {StepStatus.UNKNOWN, StepStatus.FAILED}:
            raise ValueError("unsupported terminal state")
        self._post("/asset/internal/generation/results/terminal", {
            **common,
            "workerId": self._worker_id_from_result(result),
            "state": result.status.value,
        })

    def _worker_id_from_result(self, result: GenerationStepResult) -> str:
        worker_id = getattr(self, "_worker_id", None)
        if not isinstance(worker_id, str):
            raise PlatformTransportError("platform worker identity is not bound")
        return worker_id

    def _post(self, path: str, payload: dict[str, object]) -> object:
        request = urllib.request.Request(
            self._settings.base_url + path,
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json", "X-LN-Internal-Token": self._settings.internal_token},
            method="POST",
        )
        try:
            with self._http.open(request, timeout=30) as response:
                body = response.read(_MAX_JSON_BYTES + 1)
        except urllib.error.HTTPError as error:
            body = error.read(_MAX_JSON_BYTES + 1)
            self._raise_response_error(error.code, body)
            raise AssertionError("unreachable")
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise PlatformTransportError("platform Worker API is unavailable") from error
        return self._unwrap(body)

    def _unwrap(self, body: bytes) -> object:
        if len(body) > _MAX_JSON_BYTES:
            raise PlatformTransportError("platform response is too large")
        try:
            payload = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise PlatformTransportError("platform response is not JSON") from error
        if not isinstance(payload, Mapping):
            raise PlatformTransportError("platform response is invalid")
        if payload.get("code") == 409 or payload.get("msg") == "STALE_LEASE":
            raise StaleLeaseError()
        if payload.get("code") != 200:
            raise PlatformTransportError("platform rejected Worker request")
        return payload.get("data")

    def _raise_response_error(self, status: int, body: bytes) -> None:
        if status == 409:
            raise StaleLeaseError()
        self._unwrap(body)
        raise PlatformTransportError("platform rejected Worker request")

    def _read_reference(self, value: str) -> bytes:
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or not (parsed.hostname or "").endswith(".myqcloud.com")
            or parsed.username
            or parsed.password
        ):
            raise PlatformTransportError("platform reference URL is not a Tencent COS HTTPS URL")
        request = urllib.request.Request(value, headers={"Accept": "image/png,image/jpeg"})
        try:
            with self._cos_http.open(request, timeout=60) as response:
                length = response.headers.get("Content-Length")
                if length is not None and (not length.isdecimal() or int(length) > _MAX_REFERENCE_BYTES):
                    raise PlatformTransportError("reference image is too large")
                source = response.read(_MAX_REFERENCE_BYTES + 1)
        except PlatformTransportError:
            raise
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as error:
            raise PlatformTransportError("reference image is unavailable") from error
        if len(source) > _MAX_REFERENCE_BYTES:
            raise PlatformTransportError("reference image is too large")
        try:
            with Image.open(io.BytesIO(source)) as image:
                converted = image.convert("RGBA")
                output = io.BytesIO()
                converted.save(output, format="PNG")
        except (OSError, ValueError) as error:
            raise PlatformTransportError("reference image is invalid") from error
        return output.getvalue()

    def _lease_payload(self, claim: ClaimedActionStep) -> dict[str, object]:
        return {
            "accountId": _positive_int(claim.account_id, "accountId"),
            "taskId": _positive_int(claim.task_id, "taskId"),
            "stepId": _positive_int(claim.step_id, "stepId"),
            "workerId": self._worker_id_from_result_placeholder(),
            "leaseEpoch": claim.lease_epoch,
        }

    def _worker_id_from_result_placeholder(self) -> str:
        worker_id = getattr(self, "_worker_id", None)
        if not isinstance(worker_id, str):
            raise PlatformTransportError("platform worker identity is not bound")
        return worker_id


def _positive_int(value: object, field: str) -> int:
    try:
        parsed = int(value)  # JSON may carry Java IDs as either number or string.
    except (TypeError, ValueError) as error:
        raise PlatformTransportError(f"platform {field} is invalid") from error
    if parsed <= 0:
        raise PlatformTransportError(f"platform {field} is invalid")
    return parsed


def _required_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise PlatformTransportError(f"platform {field} is invalid")
    return value


def _safe_token(value: str, field: str) -> str:
    if not _SAFE_TOKEN.fullmatch(value):
        raise PlatformTransportError(f"platform {field} is invalid")
    return value


def _parameters(value: object) -> dict[str, object]:
    # The System side normally returns an object or a JSON object string.
    # Existing task snapshots can contain a JSON string that was itself stored
    # inside JSON, so unwrap at most one additional safe serialization layer.
    for _ in range(2):
        if not isinstance(value, str):
            break
        try:
            value = json.loads(value)
        except json.JSONDecodeError as error:
            raise PlatformTransportError("platform parameters are invalid") from error
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise PlatformTransportError("platform parameters are invalid")
    return dict(value)


def _action_prompt(action: str) -> str:
    return (
        "第一张图只提供角色身份，第二张图只提供六个角色的排布、大小与脚底位置。"
        "按第二张图的两行三列布局重新绘制六个连续动作帧，禁止改成单行或改变人物数量。"
        "参考图中的原场景和矩形照片背景不属于排版约束，必须全部移除；"
        "每格只保留角色，整张图共用一块连续、均匀的纯品红背景，不得出现六张带原背景的照片拼贴。"
        + validated_action_prompt(action)
    )


def _generation_references(source: bytes) -> tuple[bytes, bytes]:
    """Reuse avatar_lab.provider prepare_reference/layout_guide geometry."""
    with Image.open(io.BytesIO(source)) as opened:
        oriented = ImageOps.exif_transpose(opened).convert("RGBA")
        fitted = ImageOps.contain(oriented, (820, 1230), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (1024, 1536), "#ff00ff")
    canvas.paste(fitted, ((1024 - fitted.width) // 2, (1536 - fitted.height) // 2), fitted)
    cell = canvas.resize((512, 768), Image.Resampling.LANCZOS)
    guide = Image.new("RGB", (1536, 1536))
    for index in range(6):
        guide.paste(cell, ((index % 3) * 512, (index // 3) * 768))
    reference_bytes, guide_bytes = io.BytesIO(), io.BytesIO()
    canvas.save(reference_bytes, format="PNG")
    guide.save(guide_bytes, format="PNG")
    return reference_bytes.getvalue(), guide_bytes.getvalue()


def _output_prefix(value: str) -> str:
    if value.startswith("/") or ".." in value.split("/") or not value.strip("/"):
        raise PlatformTransportError("platform output prefix is invalid")
    return value.rstrip("/")


def _error_code(details: dict[str, object] | None) -> str | None:
    if not details:
        return None
    code = details.get("code")
    if not isinstance(code, str):
        code = details.get("errorCode")
    return code if isinstance(code, str) and _SAFE_TOKEN.fullmatch(code[:64]) else None


class PreflightClaimFailure(RuntimeError):
    """A leased step was conclusively closed before a provider request."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def _preflight_error_code(error: PlatformTransportError) -> str:
    message = str(error)
    if message == "platform parameters are invalid":
        return "PARAMETERS_INVALID"
    if message in {"platform model is invalid", "platform output prefix is invalid"}:
        return "CLAIM_CONFIGURATION_INVALID"
    if message == "platform reference URL is not a Tencent COS HTTPS URL":
        return "REFERENCE_URL_REJECTED"
    if message == "reference image is too large":
        return "REFERENCE_TOO_LARGE"
    if message == "reference image is invalid":
        return "REFERENCE_INVALID"
    return "REFERENCE_UNAVAILABLE"
