import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from avatar_lab.workflow import Workflow
from avatar_lab.cli import main


class ManualModeTests(unittest.TestCase):
    def test_manual_generation_ignores_gates_but_preserves_records(self):
        with tempfile.TemporaryDirectory() as temp:
            class Client:
                calls = 0
                def generate(self, payload):
                    self.calls += 1
                    return {'request_id': 'test-only', 'url': 'https://example.invalid/image'}
                def download(self, url, path):
                    Image.new('RGB', (1024, 1536), '#ff00ff').save(path)
            client = Client()
            flow = Workflow(Path(temp), client, manual=True)
            base = flow.generate('base')
            flow.accept(base)
            accepted = flow.ledger.accepted('base')
            flow.generate('base')
            pending = flow.ledger.reserve('idle', enforce_limits=False)
            flow.ledger.finish(pending, 'unknown', {})
            lost = flow.ledger.reserve('happy', enforce_limits=False)
            flow.ledger.finish(lost, 'charged', {})
            for _ in range(4):
                flow.ledger.reserve('happy', enforce_limits=False)
            with flow.ledger.connect() as db:
                db.execute('UPDATE attempts SET cost=2500 WHERE id=?', (pending,))
            before = flow.ledger.records()
            flow.generate('happy')
            self.assertEqual(client.calls, 3)
            self.assertEqual(flow.ledger.records()[:-1], before)
            self.assertEqual(flow.ledger.accepted('base'), accepted)
            with self.assertRaises(ValueError):
                Workflow(Path(temp), client).generate('base')

    def test_cli_defaults_to_manual_and_can_restore_strict_mode(self):
        with patch('avatar_lab.cli.load_client'), patch('avatar_lab.cli.Workflow') as workflow:
            self.assertEqual(main(['generate', 'wave']), 0)
            self.assertTrue(workflow.call_args.kwargs['manual'])
            self.assertEqual(main(['generate', 'wave', '--strict']), 0)
            self.assertFalse(workflow.call_args.kwargs['manual'])
