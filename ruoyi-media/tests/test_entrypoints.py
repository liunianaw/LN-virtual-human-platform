"""Offline checks for the M1 media-service entrypoints."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"


class ApiHealthTests(unittest.TestCase):
    def test_health_payload_reports_ready_without_dependencies(self) -> None:
        from ruoyi_media.api.app import health_payload

        self.assertEqual(
            health_payload(),
            {"service": "ruoyi-media", "status": "ready"},
        )


class WorkerEntrypointTests(unittest.TestCase):
    def test_once_probe_reports_worker_ready(self) -> None:
        environment = os.environ | {"PYTHONPATH": str(SRC_ROOT)}
        completed = subprocess.run(
            [sys.executable, "-m", "ruoyi_media.worker", "--once"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            check=False,
            encoding="utf-8",
            env=environment,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        event = json.loads(completed.stdout.strip())
        self.assertEqual(event["service"], "ruoyi-media-worker")
        self.assertEqual(event["status"], "ready")
        self.assertEqual(event["event"], "ready")

    def test_finite_run_emits_a_heartbeat_after_ready(self) -> None:
        environment = os.environ | {"PYTHONPATH": str(SRC_ROOT)}
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "ruoyi_media.worker",
                "--interval",
                "0",
                "--max-heartbeats",
                "1",
            ],
            cwd=PROJECT_ROOT,
            capture_output=True,
            check=False,
            encoding="utf-8",
            env=environment,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        events = [json.loads(line) for line in completed.stdout.splitlines()]
        self.assertEqual([event["event"] for event in events], ["ready", "heartbeat"])


if __name__ == "__main__":
    unittest.main()
