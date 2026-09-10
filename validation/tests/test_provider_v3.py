import json
import tempfile
import unittest
from pathlib import Path
from avatar_lab.provider import QwenClient, ProviderUncertain, build_payload, parse_result
from avatar_lab.workflow import Workflow
from PIL import Image


def response_data(usage):
    # Offline fixture based on Alibaba's Qwen Image 3.0 API reference.
    return {'request_id': 'example-request', 'usage': usage,
            'output': {'choices': [{'message': {'content': [
                {'image': 'https://example.oss-cn-beijing.aliyuncs.com/output.png?signature=private'}
            ]}}]}}


class ProviderV3Tests(unittest.TestCase):
    def test_v3_workflow_saves_response_and_downloads_without_retry(self):
        data = response_data({'input_image_count': 0, 'output_image_count': 1})
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return json.dumps(data).encode()
        class Transport:
            calls = 0
            def open(self, *args, **kwargs):
                self.calls += 1
                return Response()
        client = QwenClient('private-key', 'https://dashscope.aliyuncs.com')
        client.http = Transport()
        client.download = lambda url, path: Image.new('RGB', (1024, 1536)).save(path)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            flow = Workflow(root, client, manual=True)
            item = flow.generate('base')
            self.assertEqual(client.http.calls, 1)
            self.assertEqual(flow.ledger.get(item)['state'], 'charged')
            self.assertEqual(flow.ledger.get(item)['metadata']['provider_usage'], data['usage'])
            self.assertTrue((root / 'jobs' / item / 'source.png').is_file())
            self.assertTrue((root / 'jobs' / item / 'provider-response.local.json').is_file())

    def test_v3_success_uses_output_count_not_input_count(self):
        data = response_data({'input_image_count': 2, 'output_image_count': 1})
        result = parse_result(data)
        self.assertEqual(result['request_id'], 'example-request')
        self.assertEqual(result['usage'], data['usage'])

    def test_v2_success_remains_supported(self):
        self.assertEqual(parse_result(response_data({'image_count': 1}))['request_id'], 'example-request')

    def test_missing_or_multiple_output_is_not_success(self):
        for usage in ({}, {'output_image_count': 0}, {'output_image_count': 2}):
            with self.subTest(usage=usage), self.assertRaises(ValueError):
                parse_result(response_data(usage))
        data = response_data({'output_image_count': 1})
        data['output']['choices'][0]['message']['content'] *= 2
        with self.assertRaises(ValueError):
            parse_result(data)

    def test_response_is_saved_before_parse_failure_and_diagnostics_are_safe(self):
        data = response_data({'output_image_count': 2})
        data['message'] = 'private-key'
        body = json.dumps(data).encode()
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return body
        class Transport:
            def open(self, *args, **kwargs): return Response()
        client = QwenClient('private-key', 'https://dashscope.aliyuncs.com')
        client.http = Transport()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'provider-response.local.json'
            with self.assertRaises(ProviderUncertain) as error:
                client.generate(build_payload('base'), response_path=path)
            self.assertEqual(error.exception.details['parse_reason'], 'unexpected_output_count')
            self.assertEqual(error.exception.details['output_image_count'], 2)
            self.assertEqual(error.exception.details['http_status'], 200)
            self.assertNotIn('signature', str(error.exception.details))
            self.assertNotIn('private-key', path.read_text())
            self.assertEqual(json.loads(path.read_text())['request_id'], 'example-request')
