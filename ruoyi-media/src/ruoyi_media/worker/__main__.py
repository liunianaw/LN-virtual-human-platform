"""Observable M1 worker skeleton with no broker, database, or provider dependency."""

from __future__ import annotations

import argparse
import json
import time
from collections.abc import Sequence


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
    parser = argparse.ArgumentParser(description="Run the ruoyi-media M1 worker skeleton.")
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
    parsed = parser.parse_args(arguments)
    if parsed.interval < 0:
        parser.error("--interval must be zero or greater")
    if parsed.max_heartbeats is not None and parsed.max_heartbeats < 0:
        parser.error("--max-heartbeats must be zero or greater")
    return parsed


def main(arguments: Sequence[str] | None = None) -> None:
    """Emit readiness followed by periodic heartbeats until interrupted or bounded."""
    args = parse_args(arguments)
    emit("ready")
    if args.once:
        return

    sent = 0
    while args.max_heartbeats is None or sent < args.max_heartbeats:
        time.sleep(args.interval)
        emit("heartbeat")
        sent += 1


if __name__ == "__main__":
    main()
