"""Controlled avatar-generation worker boundary.

Message transport, platform HTTP clients, and COS clients deliberately remain
adapters.  The engine accepts only a versioned task event and requires ports
that enforce platform-side claim, attempt, and result semantics.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Any, Mapping, Protocol
from uuid import uuid4

from ruoyi_media.media import ACTIONS, process_action_board
from ruoyi_media.providers.qwen_image import (
    ImageGenerationRequest,
    ProviderRejected,
    ProviderUncertain,
    QwenImageProvider,
    safe_provider_details,
)

_LEASE_RENEWAL_SECONDS = 30


class StepStatus(StrEnum):
    FAILED = "FAILED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    UNKNOWN = "UNKNOWN"


class WorkerOutcome(StrEnum):
    IGNORED_STALE = "IGNORED_STALE"
    NO_WORK = "NO_WORK"
    REPORTED_FAILED = "REPORTED_FAILED"
    REPORTED_SUCCEEDED = "REPORTED_SUCCEEDED"
    REPORTED_UNKNOWN = "REPORTED_UNKNOWN"


class StaleLeaseError(RuntimeError):
    """Raised by the platform when another lease epoch now owns the step."""


@dataclass(frozen=True)
class AvatarGenerationRequested:
    schema_version: int
    event_id: str
    trace_id: str
    task_id: str
    account_id: str

    @classmethod
    def from_message(cls, message: Mapping[str, object]) -> "AvatarGenerationRequested":
        payload = message.get("payload")
        body = payload if isinstance(payload, Mapping) else message
        event_type = message.get("eventType", message.get("type"))
        if event_type != "AVATAR_GENERATION_REQUESTED":
            raise ValueError("unsupported worker event type")
        schema_version = message.get("schemaVersion", 1)
        if schema_version != 1:
            raise ValueError("unsupported worker event schema")
        event_id = _identifier(message.get("eventId"), "eventId")
        trace_id = _identifier(message.get("traceId"), "traceId")
        task_id = _identifier(body.get("taskId"), "taskId")
        account_id = _identifier(message.get("accountId", body.get("accountId")), "accountId")
        return cls(schema_version, event_id, trace_id, task_id, account_id)


@dataclass(frozen=True)
class ClaimedActionStep:
    account_id: str
    task_id: str
    step_id: str
    action: str
    attempt_no: int
    lease_epoch: int
    lease_expires_at: datetime
    reference_png: bytes
    layout_guide_png: bytes | None
    prompt: str
    model: str
    parameters: dict[str, object]
    output_prefix: str


@dataclass(frozen=True)
class PreparedAttempt:
    attempt_id: str
    provider_request_key: str


@dataclass(frozen=True)
class StoredObject:
    object_key: str
    sha256: str
    size_bytes: int
    content_type: str


@dataclass(frozen=True)
class GenerationStepResult:
    schema_version: int
    event_id: str
    trace_id: str
    account_id: str
    task_id: str
    step_id: str
    attempt_id: str
    lease_epoch: int
    status: StepStatus
    objects: tuple[StoredObject, ...]
    manifest: dict[str, object] | None
    usage: dict[str, object] | None
    error: dict[str, object] | None

    def to_message(self) -> dict[str, object]:
        return {
            "schemaVersion": self.schema_version,
            "eventId": self.event_id,
            "traceId": self.trace_id,
            "type": "generation.step.result",
            "accountId": self.account_id,
            "taskId": self.task_id,
            "stepId": self.step_id,
            "attemptId": self.attempt_id,
            "leaseEpoch": self.lease_epoch,
            "status": self.status,
            "output": {
                "manifest": self.manifest,
                "objects": [
                    {
                        "objectKey": item.object_key,
                        "sha256": item.sha256,
                        "sizeBytes": item.size_bytes,
                        "contentType": item.content_type,
                    }
                    for item in self.objects
                ],
            },
            "usage": self.usage,
            "error": self.error,
        }


class GenerationPlatformPort(Protocol):
    """Internal platform adapter; every mutating method rejects a stale lease."""

    def claim_next_action_step(self, event: AvatarGenerationRequested, worker_id: str) -> ClaimedActionStep | None: ...

    def prepare_attempt(self, claim: ClaimedActionStep, request_hash: str) -> PreparedAttempt: ...

    def progress(
        self,
        claim: ClaimedActionStep,
        attempt: PreparedAttempt,
        state: StepStatus,
        provider_request_id: str | None,
        details: dict[str, object] | None = None,
    ) -> None: ...

    def submit_result(self, result: GenerationStepResult) -> None: ...


class OutputObjectWriter(Protocol):
    """Uploads only to a platform-assigned task prefix; it never writes business rows."""

    def upload(self, object_key: str, source: Path, content_type: str) -> StoredObject: ...


class GenerationWorker:
    """One event / one claimed step; retries are platform-scheduled, never implicit."""

    def __init__(
        self,
        worker_id: str,
        platform: GenerationPlatformPort,
        provider: QwenImageProvider,
        objects: OutputObjectWriter,
    ) -> None:
        self._worker_id = _identifier(worker_id, "workerId")
        self._platform = platform
        self._provider = provider
        self._objects = objects

    def handle(self, message: Mapping[str, object]) -> WorkerOutcome:
        event = AvatarGenerationRequested.from_message(message)
        try:
            claim = self._platform.claim_next_action_step(event, self._worker_id)
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE
        if claim is None:
            return WorkerOutcome.NO_WORK
        _validate_claim(claim, event)
        if claim.lease_expires_at <= datetime.now(UTC):
            return WorkerOutcome.IGNORED_STALE

        request_hash = _request_hash(claim)
        try:
            attempt = self._platform.prepare_attempt(claim, request_hash)
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE
        try:
            with self._keep_lease(claim, attempt):
                return self._generate_claim(event, claim, attempt)
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE

    @contextmanager
    def _keep_lease(self, claim: ClaimedActionStep, attempt: PreparedAttempt):
        # Dispatch is recorded before HTTP; renew during provider/COS work so
        # a slow request or timeout can still report its terminal state.
        self._platform.progress(claim, attempt, StepStatus.RUNNING, None)
        stopped = threading.Event()
        def renew():
            while not stopped.wait(_LEASE_RENEWAL_SECONDS):
                try:
                    self._platform.progress(claim, attempt, StepStatus.RUNNING, None)
                    print(json.dumps({"event": "generation_waiting", "taskId": claim.task_id, "action": claim.action}), flush=True)
                except Exception as error:
                    print(json.dumps({"event": "lease_renewal_failed", "taskId": claim.task_id, "errorType": type(error).__name__}), flush=True)
                    return
        thread = threading.Thread(target=renew, daemon=True)
        thread.start()
        try:
            yield
        finally:
            stopped.set()
            thread.join(timeout=35)

    def _generate_claim(self, event, claim, attempt):
        print(json.dumps({"event": "generation_dispatch", "taskId": claim.task_id, "stepId": claim.step_id,
                          "attemptId": attempt.attempt_id, "action": claim.action, "model": claim.model}), flush=True)
        try:
            generated = self._provider.generate(
                ImageGenerationRequest(
                    model=claim.model,
                    prompt=claim.prompt,
                    reference_png=claim.reference_png,
                    layout_guide_png=claim.layout_guide_png,
                    parameters=claim.parameters,
                )
            )
        except ProviderRejected as error:
            return self._report_failure(event, claim, attempt, "PROVIDER_REJECTED", {"errorCode": error.code}, retryable=False)
        except ProviderUncertain as error:
            print(json.dumps({"event": "generation_provider_unknown", "taskId": claim.task_id, **error.details}), flush=True)
            return self._report_unknown(event, claim, attempt, error.details)

        try:
            self._platform.progress(claim, attempt, StepStatus.RUNNING, generated.request_id)
            objects, manifest = self._process_and_upload(claim, generated.image_png)
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE
        except Exception as error:
            # A confirmed provider request with an incomplete local artifact is
            # unknown, never automatically resubmitted as another paid call.
            diagnostic = {"event": "generation_postprocessing_failed", "taskId": claim.task_id,
                          "requestId": generated.request_id, "errorType": type(error).__name__}
            if str(error) in {
                "action board perimeter must use a saturated chroma background",
                "action board must be exactly 1536 x 1536; it is never resized",
                "COS output object write failed",
            }:
                diagnostic["reason"] = str(error)
            print(json.dumps(diagnostic), flush=True)
            if isinstance(error, ValueError):
                return self._report_failure(event, claim, attempt, "ACTION_PROCESSING_INVALID",
                                            {"requestId": generated.request_id}, retryable=False)
            return self._report_unknown(event, claim, attempt, {"reason": type(error).__name__, "providerRequestId": generated.request_id})

        result = GenerationStepResult(
            schema_version=1,
            event_id=uuid4().hex,
            trace_id=event.trace_id,
            account_id=claim.account_id,
            task_id=claim.task_id,
            step_id=claim.step_id,
            attempt_id=attempt.attempt_id,
            lease_epoch=claim.lease_epoch,
            status=StepStatus.SUCCEEDED,
            objects=tuple(objects),
            manifest=manifest,
            usage=generated.usage,
            error=None,
        )
        try:
            self._platform.submit_result(result)
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE
        return WorkerOutcome.REPORTED_SUCCEEDED

    def _process_and_upload(self, claim: ClaimedActionStep, image_png: bytes) -> tuple[list[StoredObject], dict[str, object]]:
        # Keep the paid result for local repair if processing or upload fails.
        # This directory contains private artifacts, never provider credentials.
        artifact_root = Path(os.environ.get("RUOYI_MEDIA_ARTIFACT_DIR", str(Path(tempfile.gettempdir()) / "ruoyi-media-generation")))
        directory = artifact_root / f"{claim.task_id}-{claim.step_id}-{claim.lease_epoch}"
        directory.mkdir(parents=True, exist_ok=True)
        board_path = directory / "action-board.png"
        board_path.write_bytes(image_png)
        print(json.dumps({"event": "processing_start", "taskId": claim.task_id, "sourcePath": str(board_path)}), flush=True)
        package_directory = directory / "package"
        manifest = process_action_board(board_path, package_directory, claim.action)
        print(json.dumps({"event": "processing_completed", "taskId": claim.task_id, "action": claim.action}), flush=True)
        objects: list[StoredObject] = []
        for name in (*(f"frame-{index:02d}.png" for index in range(6)), "atlas.png", "manifest.json"):
            object_key = _output_key(claim.output_prefix, name)
            expected_content_type = "application/json" if name == "manifest.json" else "image/png"
            stored = self._objects.upload(object_key, package_directory / name, expected_content_type)
            _validate_stored_object(stored, object_key, package_directory / name, expected_content_type)
            objects.append(stored)
        return objects, manifest

    def _report_failure(
        self,
        event: AvatarGenerationRequested,
        claim: ClaimedActionStep,
        attempt: PreparedAttempt,
        code: str,
        details: dict[str, object],
        retryable: bool,
    ) -> WorkerOutcome:
        error = {"code": code, "retryable": retryable, "details": safe_provider_details(details)}
        return self._submit_terminal(event, claim, attempt, StepStatus.FAILED, error)

    def _report_unknown(
        self,
        event: AvatarGenerationRequested,
        claim: ClaimedActionStep,
        attempt: PreparedAttempt,
        details: dict[str, object],
    ) -> WorkerOutcome:
        error = {"code": "UPSTREAM_RESULT_UNKNOWN", "retryable": False, "details": safe_provider_details(details)}
        return self._submit_terminal(event, claim, attempt, StepStatus.UNKNOWN, error)

    def _submit_terminal(
        self,
        event: AvatarGenerationRequested,
        claim: ClaimedActionStep,
        attempt: PreparedAttempt,
        status: StepStatus,
        error: dict[str, object],
    ) -> WorkerOutcome:
        try:
            self._platform.progress(claim, attempt, status, None, error)
            self._platform.submit_result(
                GenerationStepResult(
                    schema_version=1,
                    event_id=uuid4().hex,
                    trace_id=event.trace_id,
                    account_id=claim.account_id,
                    task_id=claim.task_id,
                    step_id=claim.step_id,
                    attempt_id=attempt.attempt_id,
                    lease_epoch=claim.lease_epoch,
                    status=status,
                    objects=(),
                    manifest=None,
                    usage=None,
                    error=error,
                )
            )
        except StaleLeaseError:
            return WorkerOutcome.IGNORED_STALE
        return WorkerOutcome.REPORTED_UNKNOWN if status is StepStatus.UNKNOWN else WorkerOutcome.REPORTED_FAILED


def _identifier(value: object, field: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 128:
        raise ValueError(f"{field} must be a non-empty bounded string")
    return value


def _validate_claim(claim: ClaimedActionStep, event: AvatarGenerationRequested) -> None:
    if claim.action not in ACTIONS:
        raise ValueError("claim action is not one of the eight standard actions")
    if claim.task_id != event.task_id or claim.account_id != event.account_id or claim.lease_epoch < 1:
        raise ValueError("claim does not match the controlled task event")
    if not claim.output_prefix or claim.output_prefix.startswith("/") or ".." in claim.output_prefix.split("/"):
        raise ValueError("claim output prefix is invalid")


def _request_hash(claim: ClaimedActionStep) -> str:
    material = {
        "action": claim.action,
        "layoutGuideSha256": hashlib.sha256(claim.layout_guide_png).hexdigest() if claim.layout_guide_png else None,
        "model": claim.model,
        "parameters": claim.parameters,
        "prompt": claim.prompt,
        "referenceSha256": hashlib.sha256(claim.reference_png).hexdigest(),
        "taskId": claim.task_id,
    }
    return hashlib.sha256(json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def _output_key(prefix: str, name: str) -> str:
    if "/" in name or name in {"", ".", ".."}:
        raise ValueError("output filename is invalid")
    return prefix.rstrip("/") + "/" + name


def _validate_stored_object(stored: StoredObject, expected_key: str, source: Path, content_type: str) -> None:
    expected_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    if (
        stored.object_key != expected_key
        or stored.sha256 != expected_hash
        or stored.size_bytes != source.stat().st_size
        or stored.content_type != content_type
    ):
        raise ValueError("object writer returned a different object contract")
