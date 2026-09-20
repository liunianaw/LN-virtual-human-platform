"""A slow provider must renew its lease and report UNKNOWN without a retry."""

import time
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from unittest.mock import Mock, patch
from pathlib import Path
from types import SimpleNamespace

from ruoyi_media.providers.qwen_image import ProviderUncertain
from ruoyi_media.worker.generation import (
    ClaimedActionStep, GenerationWorker, PreparedAttempt, StepStatus, WorkerOutcome,
)


class WorkerLeaseTest(unittest.TestCase):
    def test_postprocessing_failure_keeps_paid_source(self):
        claim = SimpleNamespace(task_id="2", step_id="3", lease_epoch=1, action="idle")
        worker = GenerationWorker("worker", Mock(), Mock(), Mock())
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict("os.environ", {"RUOYI_MEDIA_ARTIFACT_DIR": directory}), patch(
                "ruoyi_media.worker.generation.process_action_board", side_effect=ValueError("invalid board")
            ):
                with self.assertRaises(ValueError):
                    worker._process_and_upload(claim, b"paid-source")
            self.assertEqual((Path(directory) / "2-3-1/action-board.png").read_bytes(), b"paid-source")

    def test_slow_unknown_call_is_renewed_and_reported_once(self):
        platform = Mock()
        platform.claim_next_action_step.return_value = ClaimedActionStep(
            account_id="1", task_id="2", step_id="3", action="idle", attempt_no=1,
            lease_epoch=1, lease_expires_at=datetime.now(UTC) + timedelta(seconds=300),
            reference_png=b"reference", layout_guide_png=None, prompt="idle", model="qwen-image-3.0-pro",
            parameters={}, output_prefix="avatar-generation/1/2/3/1",
        )
        platform.prepare_attempt.return_value = PreparedAttempt("4", "request-key")
        provider = Mock()
        def slow_call(*_):
            time.sleep(0.08)
            raise ProviderUncertain({"reason": "TimeoutError"})
        provider.submit_async.side_effect = slow_call
        worker = GenerationWorker("worker", platform, provider, Mock())
        with patch("ruoyi_media.worker.generation._LEASE_RENEWAL_SECONDS", 0.01):
            result = worker.handle({"eventId": "event", "traceId": "trace", "eventType": "AVATAR_GENERATION_REQUESTED",
                                    "accountId": "1", "payload": {"taskId": "2"}})
        self.assertEqual(result, WorkerOutcome.REPORTED_UNKNOWN)
        self.assertGreaterEqual(sum(call.args[2] is StepStatus.RUNNING for call in platform.progress.call_args_list), 2)
        provider.submit_async.assert_called_once()
        platform.submit_result.assert_called_once()
        self.assertEqual(platform.submit_result.call_args.args[0].status, StepStatus.UNKNOWN)
        calls_after_return = platform.progress.call_count
        time.sleep(0.03)
        self.assertEqual(platform.progress.call_count, calls_after_return)
