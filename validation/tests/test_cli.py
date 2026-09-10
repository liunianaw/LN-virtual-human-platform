import json
import os
import tempfile
import unittest
from pathlib import Path
from avatar_lab.cli import load_client, resolve
from avatar_lab.ledger import Ledger


class CLITests(unittest.TestCase):
    def test_config_host_validated_without_exposing_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / 'config.local.json'
            config.write_text(json.dumps({'api_key': 'private-key', 'api_host': 'https://evil.cn'}))
            with self.assertRaises(ValueError) as error:
                load_client(config, {})
            self.assertNotIn('private-key', str(error.exception))

    def test_missing_key_does_not_make_network_call(self):
        with self.assertRaises(ValueError):
            load_client(Path('missing-file.json'), {})

    def test_reconciliation_requires_evidence_and_cannot_rewrite_charge(self):
        with tempfile.TemporaryDirectory() as tmp:
            ledger = Ledger(Path(tmp) / 'ledger.db')
            item = ledger.reserve('base')
            with self.assertRaises(ValueError):
                resolve(ledger, item, 'rejected', '', '')
            resolve(ledger, item, 'charged', 'request-123', '控制台确认输出一张')
            self.assertEqual(ledger.summary()['committed_fen'], 50)
            with self.assertRaises(ValueError):
                resolve(ledger, item, 'rejected', 'request-123', 'again')
