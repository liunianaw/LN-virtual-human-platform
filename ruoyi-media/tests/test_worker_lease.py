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
    AvatarGenerationRequested, ClaimedActionStep, GenerationWorker, PreparedAttempt, StepStatus, WorkerOutcome, _request_hash,
)
from ruoyi_media.worker.platform_http import PlatformHttpSettings, SystemGenerationPlatform


class WorkerLeaseTest(unittest.TestCase):
    def test_submitted_attempt_recovery_does_not_read_reference_or_change_hash(self):
        platform = SystemGenerationPlatform(PlatformHttpSettings("http://127.0.0.1", "test-token"))
        original_hash = "AB" * 32
        platform._post = Mock(return_value={
            "accountId": 1, "taskId": 2, "stepId": 3, "action": "wave",
            "attemptNo": 1, "leaseEpoch": 4, "leaseSeconds": 300,
            "model": "qwen-image-3.0-pro", "parametersJson": "{}",
            "outputPrefix": "avatar-generation/1/2/3/4", "officialServiceId": 9,
            "serviceRevision": 1, "recoveryOnly": True, "requestHash": original_hash,
        })
        platform._read_reference = Mock(side_effect=AssertionError("recovery must not read COS reference"))
        event = AvatarGenerationRequested(1, "event", "trace", "2", "1")
        claim = platform.claim_next_action_step(event, "worker")
        self.assertEqual(_request_hash(claim), original_hash)
        self.assertEqual(claim.reference_png, b"")
        platform._read_reference.assert_not_called()

    def test_submitted_attempt_only_queries_existing_provider_task(self):
        platform = Mock()
        platform.claim_next_action_step.return_value = ClaimedActionStep(
            account_id="1", task_id="2", step_id="3", action="wave", attempt_no=1,
            lease_epoch=4, lease_expires_at=datetime.now(UTC) + timedelta(seconds=300),
            reference_png=b"", layout_guide_png=None, prompt="wave", model="qwen-image-3.0-pro",
            parameters={}, output_prefix="avatar-generation/1/2/3/4", request_hash="ab" * 32,
        )
        platform.prepare_attempt.return_value = PreparedAttempt(
            "4", "request-key", recovery_only=True,
            receipt={"providerTaskId": "existing-task", "stage": "PENDING"},
        )
        provider = Mock()
        provider.query_async.return_value = {"providerTaskId": "existing-task", "stage": "RUNNING"}
        worker = GenerationWorker("worker", platform, provider, Mock())
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"RUOYI_MEDIA_ARTIFACT_DIR": directory}):
            outcome = worker.handle({"eventId": "event", "traceId": "trace", "eventType": "AVATAR_GENERATION_REQUESTED",
                                     "accountId": "1", "payload": {"taskId": "2"}})
        self.assertEqual(outcome, WorkerOutcome.WAITING)
        provider.query_async.assert_called_once()
        provider.submit_async.assert_not_called()
        platform.prepare_attempt.assert_called_once()
        self.assertEqual(platform.prepare_attempt.call_args.args[1], "ab" * 32)

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
