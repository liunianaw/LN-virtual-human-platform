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
    parsed = parser.parse_args(arguments)
    if parsed.interval < 0:
        parser.error("--interval must be zero or greater")
    if parsed.max_heartbeats is not None and parsed.max_heartbeats < 0:
        parser.error("--max-heartbeats must be zero or greater")
    return parsed


def main(arguments: Sequence[str] | None = None) -> None:
    """Keep the M1 heartbeat mode; event mode is explicit and requires every runtime credential."""
    args = parse_args(arguments)
    emit("ready")
    if args.event_file is not None:
        outcome = _process_event(args.event_file)
        emit("generation_" + outcome)
        return
    if args.once:
        return

    sent = 0
    while args.max_heartbeats is None or sent < args.max_heartbeats:
        time.sleep(args.interval)
        emit("heartbeat")
        sent += 1


def _process_event(path: Path) -> str:
    from .cos_writer import TencentCosObjectWriter
    from .generation import GenerationWorker
    from .platform_http import SystemGenerationPlatform
    from ruoyi_media.providers import QwenImageProvider

    worker_id = os.environ.get("RUOYI_MEDIA_WORKER_ID", "")
    if not worker_id:
        raise ValueError("RUOYI_MEDIA_WORKER_ID is not configured")
    if os.environ.get("M2_IMAGE_PROVIDER_ENABLED") != "true":
        raise ValueError("M2_IMAGE_PROVIDER_ENABLED must be exactly true before processing a generation event")
    try:
        event: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("event file is not readable JSON") from error
    if not isinstance(event, dict):
        raise ValueError("event file must contain a JSON object")
    worker = GenerationWorker(
        worker_id=worker_id,
        platform=SystemGenerationPlatform.from_environment(),
        provider=QwenImageProvider.from_environment(),
        objects=TencentCosObjectWriter.from_environment(),
    )
    return worker.handle(event).value.lower()


if __name__ == "__main__":
    main()
