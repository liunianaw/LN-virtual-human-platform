import base64
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from avatar_lab.workflow import Workflow
from avatar_lab.cli import main


class ReferenceTests(unittest.TestCase):
    def test_custom_jpeg_generates_action_without_accepted_base(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reference = root / '我的角色.jpg'
            Image.new('RGB', (600, 600), 'blue').save(reference)
            before = reference.read_bytes()
            class Client:
                def generate(self, payload):
                    self.payload = payload
                    return {'request_id': 'offline', 'url': 'https://example.invalid/image'}
                def download(self, url, path):
                    Image.new('RGB', (1536, 1536)).save(path)
            client = Client()
            flow = Workflow(root / 'workspace', client, manual=True)
            item = flow.generate('wave', reference_path=reference)
            content = client.payload['input']['messages'][0]['content']
            with Image.open(io.BytesIO(base64.b64decode(content[0]['image'].split(',')[1]))) as image:
                self.assertEqual(image.size, (1024, 1536))
                # Square source keeps its proportions, surrounded by padding.
                self.assertEqual(image.getpixel((512, 768)), (0, 0, 254))
                self.assertEqual(image.getpixel((512, 0)), (255, 0, 255))
            self.assertEqual(reference.read_bytes(), before)
            directory = root / 'workspace' / 'jobs' / item
            self.assertTrue((directory / 'reference-input.png').is_file())
            self.assertEqual(json.loads((directory / 'recipe.json').read_text(encoding='utf-8'))['reference_source'], 'custom_file')
            self.assertIsNone(flow.ledger.accepted('base'))

    def test_invalid_reference_does_not_submit_or_reserve(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bad = root / 'invalid.png'
            bad.write_text('not an image')
            flow = Workflow(root / 'workspace', object(), manual=True)
            for reference in (bad, root / 'missing.png'):
                with self.assertRaises(ValueError):
                    flow.generate('wave', reference_path=reference)
            with self.assertRaises(ValueError):
                flow.generate('base', reference_path=bad)
            self.assertEqual(flow.ledger.summary()['attempt_count'], 0)

    def test_cli_passes_custom_path(self):
        with patch('avatar_lab.cli.load_client'), patch('avatar_lab.cli.Workflow') as workflow:
            self.assertEqual(main(['generate', 'wave', '--reference', '我的角色.jpg']), 0)
            self.assertEqual(workflow.return_value.generate.call_args.kwargs['reference_path'], Path('我的角色.jpg'))
