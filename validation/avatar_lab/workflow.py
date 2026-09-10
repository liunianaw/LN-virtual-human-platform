import json
import time
import hashlib
from PIL import Image
from .ledger import Ledger
from .media import package, sha256
from .provider import build_payload, layout_guide, prepare_reference, ProviderRejected, ProviderUncertain, QwenClient
from .prompts import ACTIONS, REPRESENTATIVES, MODEL


class Workflow:
    def __init__(self, root, client=None, manual=False):
        self.root = root
        self.client = client
        self.manual = manual
        self.ledger = Ledger(root / 'ledger.db')

    def accepted_source(self, job):
        accepted = self.ledger.accepted(job)
        if not accepted:
            raise ValueError(f'请先检查并验收 {job}')
        source = self.root / 'jobs' / accepted['id'] / 'source.png'
        if not source.is_file() or sha256(source) != accepted['hash']:
            raise ValueError('已验收素材已改变或缺失，停止生成')
        return source

    def generate(self, job, description=None, reference_path=None):
        if job not in ('base', 'portrait') and job not in ACTIONS:
            raise ValueError('未知动作')
        if description is not None and (job != 'portrait' or not description.strip() or len(description) > 4000):
            raise ValueError('自定义提示词仅用于 portrait，须为1至4000个字符')
        if not self.client:
            raise ValueError('尚未配置模型客户端')
        if reference_path is not None and job not in ACTIONS:
            raise ValueError('--reference 仅用于动作生成，如 generate wave')
        reference = None
        if job not in ('base', 'portrait'):
            reference = (prepare_reference(reference_path) if reference_path is not None
                         else self.accepted_source('base').read_bytes())
            if not self.manual and job not in REPRESENTATIVES:
                for representative in REPRESENTATIVES:
                    self.accepted_source(representative)
        # A paid result with an unfinished download is recovered, never regenerated.
        for row in ([] if self.manual else self.ledger.records()):
            if (row['job'] == job and row['state'] == 'charged'
                    and not row['metadata'].get('source_sha256')
                    and not row['metadata'].get('artifact_abandoned')):
                raise ValueError('存在已收费但未取得素材的结果，请先 recover 或人工核对。')
        guide = layout_guide(reference) if reference else None
        payload = build_payload('base' if job == 'portrait' else job, reference, guide)
        if description is not None:
            payload['input']['messages'][0]['content'][-1]['text'] = (
                description.strip() + '\n生成单个角色，从头顶到鞋底完整全身站姿，双手双脚完整可见，'
                '人物居中，四周留白。纯品红背景RGB(255,0,255)，人物服装不使用品红色。'
                '无文字、水印、边框或网格。'
            )
        item = self.ledger.reserve(job, enforce_limits=not self.manual)
        directory = self.root / 'jobs' / item
        started = time.monotonic()
        try:
            directory.mkdir(parents=True)
            if reference_path is not None:
                (directory / 'reference-input.png').write_bytes(reference)
            if guide:
                (directory / 'layout-guide.png').write_bytes(guide)
            # Store the readable recipe, never a base64 copy of the reference or a Key.
            recipe = {'model': MODEL, 'job': job, 'parameters': payload['parameters'],
                      'prompt': payload['input']['messages'][0]['content'][-1]['text'],
                      'reference_sha256': hashlib.sha256(reference).hexdigest() if reference else None,
                      'reference_source': ('custom_file' if reference_path is not None
                                           else 'accepted_base' if reference else None),
                      'layout_guide_sha256': sha256(directory / 'layout-guide.png') if guide else None,
                      'layout_guide_is_animation': False, 'manual_mode': self.manual}
            (directory / 'recipe.json').write_text(json.dumps(recipe, ensure_ascii=False, indent=2), encoding='utf-8')
            if isinstance(self.client, QwenClient):
                result = self.client.generate(payload, response_path=directory / 'provider-response.local.json')
            else:
                result = self.client.generate(payload)
        except ProviderRejected as error:
            self.ledger.finish(item, 'rejected', {'error_code': str(error), 'elapsed_s': round(time.monotonic()-started, 2)})
            raise ValueError(f'请求被拒绝；预占已释放。记录：{item}') from None
        except ProviderUncertain as error:
            self.ledger.finish(item, 'unknown', error.details | {'elapsed_s': round(time.monotonic()-started, 2)})
            diagnosis = ', '.join(f'{key}={value}' for key, value in error.details.items())
            raise ValueError(f'调用或响应解析未完成：{diagnosis}。记录：{item}') from None
        except Exception:
            self.ledger.finish(item, 'unknown', {'error_code': 'unconfirmed_result', 'elapsed_s': round(time.monotonic()-started, 2)})
            raise ValueError(f'上游结果未知，已保留请求记录：{item}') from None
        self.ledger.finish(item, 'charged', {'request_id': result['request_id'],
                                          'provider_usage': result.get('usage'),
                                          'elapsed_s': round(time.monotonic()-started, 2)})
        try:
            # Signed URL is private recovery data, not included in exported status.
            (directory / 'receipt.local.json').write_text(json.dumps(result), encoding='utf-8')
            self._download(item, result)
        except Exception:
            raise ValueError(f'生成已收费，下载或保存未完成。使用 recover {item}，不要重新生成。') from None
        return item

    def _download(self, item, result):
        directory = self.root / 'jobs' / item
        path = directory / 'source.png'
        self.client.download(result['url'], path)
        with Image.open(path) as image:
            if image.format != 'PNG':
                raise ValueError('输出不是PNG')
            image.verify()
        self.ledger.finish(item, 'charged', {'source_sha256': sha256(path)})

    def recover(self, item):
        row = self.ledger.get(item)
        if not row or row['state'] != 'charged' or row['metadata'].get('source_sha256'):
            raise ValueError('此记录不需要下载恢复')
        receipt = self.root / 'jobs' / item / 'receipt.local.json'
        self._download(item, json.loads(receipt.read_text(encoding='utf-8')))

    def abandon(self, item, note):
        row = self.ledger.get(item)
        if (not row or row['state'] != 'charged' or self.ledger.accepted(row['job'])
                or len(note.strip()) < 6):
            raise ValueError('只能放弃已确认计费但未验收的输出，且需填写原因')
        self.ledger.finish(item, 'charged', {'artifact_abandoned': True, 'abandon_note': note})

    def package(self, item):
        row = self.ledger.get(item)
        if not row or row['state'] != 'charged' or row['job'] not in ACTIONS:
            raise ValueError('请选择已生成的动作记录')
        directory = self.root / 'jobs' / item
        source = directory / 'source.png'
        if not source.is_file() or sha256(source) != row['metadata'].get('source_sha256'):
            raise ValueError('原始素材与生成记录不一致')
        accepted = self.ledger.accepted(row['job'])
        if accepted and accepted['id'] == item:
            raise ValueError('已验收素材不能重新打包覆盖')
        return package(source, directory / 'package', row['job'])

    def accept(self, item, note='人工确认视觉检查通过'):
        row = self.ledger.get(item)
        if row and row['job'] == 'portrait':
            raise ValueError('独立形象试图仅供查看，不替换已验收的动作参考图')
        if (not row or row['state'] != 'charged' or not note.strip()
                or row['metadata'].get('artifact_abandoned')):
            raise ValueError('记录未成功或缺少验收说明')
        directory = self.root / 'jobs' / item
        source = directory / 'source.png'
        if not source.is_file() or sha256(source) != row['metadata'].get('source_sha256'):
            raise ValueError('原图与生成记录不一致')
        if row['job'] == 'base':
            with Image.open(source) as image:
                if image.size != (1024, 1536):
                    raise ValueError('基准图尺寸不是1024×1536')
        else:
            manifest_path = directory / 'package' / 'manifest.json'
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            if not manifest['geometry_ok'] or manifest['source_sha256'] != sha256(source):
                raise ValueError('素材几何检查未通过或原图已改变')
            for name, digest in zip(manifest['frames'], manifest['frame_hashes'], strict=True):
                if sha256(manifest_path.parent / name) != digest:
                    raise ValueError('帧素材已改变')
            manifest['visual_approved'] = True
            manifest['review_note'] = note
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        self.ledger.accept(row['job'], item, sha256(source), note)
