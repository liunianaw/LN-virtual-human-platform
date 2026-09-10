import tempfile
import unittest
import io
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

from avatar_lab.ledger import Ledger
from avatar_lab.workflow import Workflow
from avatar_lab.provider import build_payload, validate_host, parse_result, ProviderRejected, ProviderUncertain, QwenClient, layout_guide


class LedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def test_reserved_cost_survives_restart_and_blocks_duplicate(self):
        ledger = Ledger(self.root / 'ledger.db')
        ledger.reserve('base')
        restarted = Ledger(self.root / 'ledger.db')
        self.assertEqual(restarted.summary()['committed_fen'], 50)
        with self.assertRaises(ValueError):
            restarted.reserve('base')

    def test_concurrent_callers_cannot_spend_same_budget(self):
        db = self.root / 'ledger.db'
        Ledger(db)
        def reserve(_):
            try:
                return Ledger(db).reserve('base')
            except ValueError:
                return None
        with ThreadPoolExecutor(max_workers=6) as pool:
            result = list(pool.map(reserve, range(6)))
        self.assertEqual(sum(x is not None for x in result), 1)

    def test_budget_cap_applies_across_all_jobs_and_restarts(self):
        ledger = Ledger(self.root / 'ledger.db')
        for n in range(40):
            item = ledger.reserve(f'job{n}')
            ledger.finish(item, 'charged', {'request_id': str(n)})
        with self.assertRaises(ValueError):
            Ledger(self.root / 'ledger.db').reserve('extra')
        self.assertEqual(ledger.summary()['committed_fen'], 2000)

    def test_three_attempt_limit_includes_rejected_requests(self):
        ledger = Ledger(self.root / 'ledger.db')
        for _ in range(3):
            item = ledger.reserve('base')
            ledger.finish(item, 'rejected', {})
        with self.assertRaises(ValueError):
            ledger.reserve('base')

    def test_manual_review_can_close_unknown_without_refunding_its_budget(self):
        ledger = Ledger(self.root / 'ledger.db')
        item = ledger.reserve('idle')
        ledger.finish(item, 'unknown', {})
        ledger.retain(item, '负责人核对控制台无此记录，保守保留费用占用')
        ledger.reserve('idle')
        self.assertEqual(ledger.summary()['committed_fen'], 100)
        self.assertEqual(ledger.summary()['charged_fen'], 0)
        self.assertEqual(ledger.summary()['retained_fen'], 50)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def client(self):
        class FakeExternalAPI:
            def generate(self, payload):
                return {'url': 'https://example.invalid/image.png', 'request_id': 'paid-1'}
            def download(self, url, path):
                Image.new('RGB', (1024, 1536), '#123456').save(path)
        return FakeExternalAPI()

    def test_action_requires_accepted_unchanged_base(self):
        flow = Workflow(self.root, self.client())
        with self.assertRaises(ValueError):
            flow.generate('wave')
        record = flow.generate('base')
        flow.accept(record)
        (self.root / 'jobs' / record / 'source.png').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            flow.generate('wave')
        self.assertEqual(flow.ledger.summary()['committed_fen'], 50)

    def test_timeout_is_not_retried_or_released(self):
        client = self.client()
        def fail(payload):
            raise TimeoutError('secret should never be logged')
        client.generate = fail
        flow = Workflow(self.root, client)
        with self.assertRaises(ValueError):
            flow.generate('base')
        with self.assertRaises(ValueError):
            flow.generate('base')
        self.assertEqual(flow.ledger.summary()['committed_fen'], 50)
        self.assertEqual(len(flow.ledger.records()), 1)
        self.assertNotIn('secret', str(flow.ledger.records()))

    def test_uncertain_provider_status_is_recorded_without_raw_body(self):
        client = self.client()
        def fail(payload):
            raise ProviderUncertain({'request_id': 'provider-123', 'http_status': 503,
                                     'error_code': 'ServiceUnavailable', 'message': 'secret'})
        client.generate = fail
        flow = Workflow(self.root, client)
        with self.assertRaises(ValueError):
            flow.generate('base')
        record = flow.ledger.records()[0]
        self.assertEqual(record['metadata']['request_id'], 'provider-123')
        self.assertEqual(record['metadata']['http_status'], 503)
        self.assertNotIn('secret', str(record))

    def test_download_failure_stays_charged(self):
        client = self.client()
        def fail(url, path):
            raise OSError('download interrupted')
        client.download = fail
        flow = Workflow(self.root, client)
        with self.assertRaises(ValueError):
            flow.generate('base')
        self.assertEqual(flow.ledger.records()[0]['state'], 'charged')
        self.assertEqual(flow.ledger.summary()['committed_fen'], 50)

    def test_abandoned_paid_download_can_retry_without_erasing_cost(self):
        client = self.client()
        download = client.download
        def fail(url, path):
            raise OSError('download lost')
        client.download = fail
        flow = Workflow(self.root, client)
        with self.assertRaises(ValueError):
            flow.generate('base')
        item = flow.ledger.records()[0]['id']
        flow.abandon(item, '已核对计费，原始素材无法取回，放弃该输出')
        client.download = download
        flow.generate('base')
        self.assertEqual(flow.ledger.summary()['committed_fen'], 100)

    def test_rejected_request_releases_budget(self):
        client = self.client()
        def fail(payload):
            raise ProviderRejected('InvalidApiKey')
        client.generate = fail
        flow = Workflow(self.root, client)
        with self.assertRaises(ValueError):
            flow.generate('base')
        self.assertEqual(flow.ledger.summary()['committed_fen'], 0)

    def test_approved_base_cannot_be_regenerated_in_same_sample(self):
        flow = Workflow(self.root, self.client())
        record = flow.generate('base')
        flow.accept(record)
        with self.assertRaises(ValueError):
            flow.generate('base')

    def test_manual_portrait_preserves_reference_and_shares_budget_and_limit(self):
        flow = Workflow(self.root, self.client())
        base = flow.generate('base')
        flow.accept(base)
        accepted = flow.ledger.accepted('base')
        for _ in range(3):
            item = flow.generate('portrait', description='原创成年女性，全身站姿')
            import json
            recipe = json.loads((self.root / 'jobs' / item / 'recipe.json').read_text(encoding='utf-8'))
            self.assertIn('原创成年女性', recipe['prompt'])
            self.assertEqual(recipe['parameters']['size'], '1024*1536')
            self.assertIsNone(recipe['reference_sha256'])
        self.assertEqual(flow.ledger.accepted('base'), accepted)
        self.assertEqual(flow.ledger.summary()['committed_fen'], 200)
        with self.assertRaises(ValueError):
            flow.generate('portrait')

    def test_custom_description_rejected_for_actions_before_spending(self):
        flow = Workflow(self.root, self.client())
        with self.assertRaises(ValueError):
            flow.generate('idle', description='自定义')
        self.assertEqual(flow.ledger.summary()['attempt_count'], 0)


class ProviderTests(unittest.TestCase):
    def test_malformed_response_preserves_safe_http_diagnostics(self):
        class Response:
            status = 502
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return b'<html>secret</html>'
        class Transport:
            def open(self, *args, **kwargs): return Response()
        client = QwenClient('private-key', 'https://dashscope.aliyuncs.com')
        client.http = Transport()
        with self.assertRaises(ProviderUncertain) as error:
            client.generate(build_payload('base'))
        self.assertEqual(error.exception.details['http_status'], 502)
        self.assertNotIn('secret', str(error.exception.details))

    def test_edit_payload_attaches_reference_and_limits_output(self):
        payload = build_payload('wave', b'png')
        content = payload['input']['messages'][0]['content']
        self.assertTrue(content[0]['image'].startswith('data:image/png;base64,'))
        self.assertEqual(payload['parameters']['n'], 1)
        self.assertFalse(payload['parameters']['prompt_extend'])

    def test_layout_guide_is_second_reference_not_generated_frames(self):
        buffer = io.BytesIO()
        Image.new('RGB', (1024, 1536), '#ff00ff').save(buffer, format='PNG')
        guide = layout_guide(buffer.getvalue())
        with Image.open(io.BytesIO(guide)) as image:
            self.assertEqual(image.size, (1536, 1536))
        payload = build_payload('idle', buffer.getvalue(), guide)
        content = payload['input']['messages'][0]['content']
        self.assertEqual(len([c for c in content if 'image' in c]), 2)

    def test_no_reference_for_action_is_rejected(self):
        with self.assertRaises(ValueError):
            build_payload('wave', None)

    def test_api_key_cannot_be_sent_to_unapproved_host(self):
        for host in ['http://dashscope.aliyuncs.com', 'https://evil.cn',
                     'https://dashscope.aliyuncs.com.evil.cn', 'https://x:password@dashscope.aliyuncs.com']:
            with self.assertRaises(ValueError):
                validate_host(host)

    def test_success_with_wrong_usage_is_unknown_not_free(self):
        with self.assertRaises(ValueError):
            parse_result({'request_id':'r', 'usage':{'image_count':2}, 'output':{}})


if __name__ == '__main__':
    unittest.main()
