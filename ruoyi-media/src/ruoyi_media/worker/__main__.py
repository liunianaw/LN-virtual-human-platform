"""Observable worker entrypoint with an explicit fail-closed M2 event mode."""

from __future__ import annotations

import argparse
import json
import os
import time
from collections.abc import Sequence
from pathlib import Path
from typing import Any


def emit(event: str) -> None:
    """Write one machine-readable worker lifecycle event to standard output."""
    print(
        json.dumps(
            {
                "event": event,
                "service": "ruoyi-media-worker",
                "status": "ready",
            },
            sort_keys=True,
        ),
        flush=True,
    )


def parse_args(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the ruoyi-media worker.")
    parser.add_argument(
        "--interval",
        type=float,
        default=30.0,
        help="seconds between heartbeat events (default: 30)",
    )
    parser.add_argument(
        "--max-heartbeats",
        type=int,
        default=None,
        help="exit after emitting this many heartbeat events",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="emit readiness once and exit; useful as a health probe",
    )
    parser.add_argument(
        "--event-file",
        type=Path,
        help="process exactly one versioned AVATAR_GENERATION_REQUESTED JSON event using configured system/COS/provider adapters",
    )
    parser.add_argument(
        "--consume-outbox",
        action="store_true",
        help="poll the platform outbox and process leased Avatar generation events",
    )
    parsed = parser.parse_args(arguments)
    if parsed.interval < 0:
        parser.error("--interval must be zero or greater")
    if parsed.max_heartbeats is not None and parsed.max_heartbeats < 0:
        parser.error("--max-heartbeats must be zero or greater")
    if parsed.consume_outbox and parsed.event_file is not None:
        parser.error("--consume-outbox cannot be combined with --event-file")
    if parsed.consume_outbox and parsed.interval <= 0:
        parser.error("--consume-outbox requires --interval greater than zero")
    return parsed


def main(arguments: Sequence[str] | None = None) -> None:
    """Run the backward-compatible heartbeat, one-event, or outbox-consumer mode."""
    args = parse_args(arguments)
    emit("ready")
    if args.event_file is not None:
        outcome = _process_event(args.event_file)
        emit("generation_" + outcome)
        return
    if args.once:
        return
    if args.consume_outbox:
        _consume_outbox(args.interval)
        return

    sent = 0
    while args.max_heartbeats is None or sent < args.max_heartbeats:
        time.sleep(args.interval)
        emit("heartbeat")
        sent += 1


def _process_event(path: Path) -> str:
    worker = _generation_worker()
    try:
        event: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("event file is not readable JSON") from error
    if not isinstance(event, dict):
        raise ValueError("event file must contain a JSON object")
    return worker.handle(event).value.lower()


def _generation_worker():
    from .cos_writer import TencentCosObjectWriter
    from .generation import GenerationWorker
    from .platform_http import SystemGenerationPlatform
    from ruoyi_media.providers import QwenImageProvider

    worker_id = os.environ.get("RUOYI_MEDIA_WORKER_ID", "")
    if not worker_id:
        raise ValueError("RUOYI_MEDIA_WORKER_ID is not configured")
    if os.environ.get("M2_IMAGE_PROVIDER_ENABLED") != "true":
        raise ValueError("M2_IMAGE_PROVIDER_ENABLED must be exactly true before processing a generation event")
    return GenerationWorker(
        worker_id=worker_id,
        platform=SystemGenerationPlatform.from_environment(),
        provider=QwenImageProvider.from_environment(),
        objects=TencentCosObjectWriter.from_environment(),
    )


def _consume_outbox(interval: float) -> None:
    """Poll one leased event at a time; no provider call is retried implicitly."""
    from .generation import WorkerOutcome
    from .platform_http import PreflightClaimFailure, SystemGenerationPlatform

    worker = _generation_worker()
    worker_id = os.environ["RUOYI_MEDIA_WORKER_ID"]
    platform = SystemGenerationPlatform.from_environment()
    while True:
        try:
            event = platform.claim_outbox(worker_id)
            if event is None:
                emit("outbox_empty")
            elif event.message["eventType"] != "AVATAR_GENERATION_REQUESTED":
                # Do not discard an event owned by another consumer.  It stays
                # leased until the platform routes it to a compatible worker.
                emit("outbox_unsupported")
            else:
                try:
                    _drain_avatar_event(worker, platform, worker_id, event.outbox_id, event.message, WorkerOutcome)
                except PreflightClaimFailure as error:
                    # The platform released a never-submitted step to READY.
                    # Keep the outbox unacknowledged; a later lease can resume
                    # it after the preflight condition is repaired.
                    emit("generation_preflight_released_" + error.code.lower())
        except Exception as error:
            # Keep the process observable without recording provider payloads,
            # credentials, or reference metadata in the worker logs.
            emit("outbox_error_" + type(error).__name__.lower())
        time.sleep(interval)
        emit("heartbeat")


def _drain_avatar_event(worker, platform, worker_id: str, outbox_id: str, message: dict[str, object], outcome_type) -> None:
    """Process all ready actions for one task before acknowledging its outbox event.

    `GenerationWorker.handle` intentionally claims only one action.  Keeping
    the outbox lease while it reports successive successful actions avoids
    acknowledging the task after the first of its eight required actions.
    """
    while True:
        outcome = worker.handle(message)
        emit("generation_" + outcome.value.lower())
        if outcome is outcome_type.REPORTED_SUCCEEDED:
            continue
        if outcome in {outcome_type.NO_WORK, outcome_type.REPORTED_FAILED, outcome_type.REPORTED_UNKNOWN}:
            platform.mark_outbox_sent(outbox_id, worker_id)
            emit("outbox_sent")
        return


if __name__ == "__main__":
    main()
