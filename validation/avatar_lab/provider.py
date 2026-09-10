import base64
import io
import json
import re
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from PIL import Image, ImageOps
from .prompts import MODEL, ACTIONS, prompt


class ProviderRejected(Exception):
    pass


class ResponseFormatError(ValueError):
    """A fixed diagnostic code, never upstream text or credentials."""


class ProviderUncertain(Exception):
    def __init__(self, details):
        self.details = {}
        for key in ('request_id', 'error_code', 'error_type', 'parse_reason'):
            value = details.get(key)
            if isinstance(value, str) and re.fullmatch(r'[a-zA-Z0-9_.:-]{1,100}', value):
                self.details[key] = value
        for key in ('http_status', 'image_count', 'input_image_count', 'output_image_count'):
            if type(details.get(key)) is int:
                self.details[key] = details[key]
        super().__init__('上游结果不明确，请按安全诊断字段核对')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_host(host):
    parts = urlsplit(host)
    valid = parts.hostname == 'dashscope.aliyuncs.com' or bool(re.fullmatch(
        r'[a-zA-Z0-9-]+\.cn-beijing\.maas\.aliyuncs\.com', parts.hostname or ''))
    if (parts.scheme != 'https' or not valid or parts.username or parts.password
            or parts.port not in (None, 443) or parts.path not in ('', '/')
            or parts.query or parts.fragment):
        raise ValueError('API Host必须是百炼北京官方HTTPS域名，不含路径或登录信息。')
    return host.rstrip('/')


def prepare_reference(path):
    """Prepare a PNG reference without cropping or changing the source file."""
    try:
        if path.stat().st_size > 10 * 1024 * 1024:
            raise ValueError('参考图片须小于等于10MB')
        with Image.open(path) as opened:
            if opened.format not in ('PNG', 'JPEG', 'WEBP'):
                raise ValueError('参考图片支持PNG、JPG/JPEG和WebP')
            oriented = ImageOps.exif_transpose(opened).convert('RGBA')
            fitted = ImageOps.contain(oriented, (820, 1230), Image.Resampling.LANCZOS)
            canvas = Image.new('RGB', (1024, 1536), '#ff00ff')
            canvas.paste(fitted, ((1024-fitted.width)//2, (1536-fitted.height)//2), fitted)
            buffer = io.BytesIO()
            canvas.save(buffer, format='PNG')
            return buffer.getvalue()
    except (OSError, Image.DecompressionBombError) as error:
        raise ValueError('无法读取参考图片，请检查路径、格式和图片完整性') from None


def layout_guide(reference):
    """A repeated static layout reference, never an animation output."""
    with Image.open(io.BytesIO(reference)) as opened:
        if opened.size != (1024, 1536):
            raise ValueError('排版参考需要标准全身基准图')
        cell = opened.convert('RGB').resize((512, 768), Image.Resampling.LANCZOS)
    guide = Image.new('RGB', (1536, 1536))
    for index in range(6):
        guide.paste(cell, ((index % 3)*512, (index // 3)*768))
    buffer = io.BytesIO()
    guide.save(buffer, format='PNG')
    return buffer.getvalue()


def build_payload(job, reference=None, guide=None):
    if job != 'base' and job not in ACTIONS:
        raise ValueError('未知动作')
    if job != 'base' and not reference:
        raise ValueError('动作生成必须附带基准图')
    content = []
    if reference:
        if len(reference) > 10 * 1024 * 1024:
            raise ValueError('参考图超过10MB')
        content.append({'image': 'data:image/png;base64,' + base64.b64encode(reference).decode('ascii')})
    text = prompt(job)
    if guide:
        content.append({'image': 'data:image/png;base64,' + base64.b64encode(guide).decode('ascii')})
        text = ('第一张图只提供角色身份，第二张图只提供六个角色的排布、大小与脚底位置。'
                '按第二张图的两行三列布局重新绘制六个连续动作帧，禁止改成单行或改变人物数量。'
                '参考图中的原场景和矩形照片背景不属于排版约束，必须全部移除；'
                '每格只保留角色，整张图共用一块连续、均匀的纯品红背景，不得出现六张带原背景的照片拼贴。' + text)
    content.append({'text': text})
    return {'model': MODEL, 'input': {'messages': [{'role': 'user', 'content': content}]},
            'parameters': {'n': 1, 'size': '1024*1536' if job == 'base' else '1536*1536',
                           'prompt_extend': False, 'watermark': False, 'seed': 20260908}}


def parse_result(data):
    # Only unambiguous pre-generation failures release the reserved budget.
    if data.get('code') in {'InvalidApiKey', 'InvalidParameter', 'InvalidParameter.DataInspectionFailed',
                             'AccessDenied', 'ModelNotFound'}:
        raise ProviderRejected(data['code'])
    if data.get('code'):
        raise ResponseFormatError('upstream_error')
    usage = data.get('usage') or {}
    # Qwen Image 3.0 separates input and output usage; 2.0 uses image_count.
    count = usage.get('output_image_count', usage.get('image_count'))
    if type(count) is not int or count != 1:
        raise ResponseFormatError('unexpected_output_count')
    images = [c['image'] for choice in data.get('output', {}).get('choices', [])
              for c in choice.get('message', {}).get('content', []) if 'image' in c]
    if len(images) != 1 or not isinstance(images[0], str) or not images[0]:
        raise ResponseFormatError('missing_unique_image_url')
    if not isinstance(data.get('request_id'), str) or not data['request_id']:
        raise ResponseFormatError('missing_request_id')
    return {'url': images[0], 'request_id': data['request_id'], 'usage': usage}


class QwenClient:
    def __init__(self, key, host):
        if not key or not isinstance(key, str) or any(c.isspace() for c in key):
            raise ValueError('请先在本地配置有效API Key')
        self.key = key
        self.host = validate_host(host)
        self.http = urllib.request.build_opener(NoRedirect())

    def generate(self, payload, response_path=None):
        req = urllib.request.Request(
            self.host + '/api/v1/services/aigc/multimodal-generation/generation',
            data=json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + self.key})
        details = {}
        try:
            try:
                with self.http.open(req, timeout=300) as response:
                    details['http_status'] = response.status
                    body = response.read(2 * 1024 * 1024)
            except urllib.error.HTTPError as error:
                details['http_status'] = error.code
                body = error.read(2 * 1024 * 1024)
            # Preserve the response before parsing, including private recovery URLs.
            # Never write request headers/Key; this file lives in ignored workspace.
            if response_path is not None:
                temporary = response_path.with_suffix('.tmp')
                temporary.write_bytes(body.replace(self.key.encode(), b'[REDACTED]'))
                temporary.replace(response_path)
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError('unexpected_response_type')
            details['request_id'] = data.get('request_id')
            details['error_code'] = data.get('code')
            usage = data.get('usage')
            if isinstance(usage, dict):
                for field in ('image_count', 'input_image_count', 'output_image_count'):
                    details[field] = usage.get(field)
            if not 200 <= details['http_status'] < 300 and not data.get('code'):
                raise ResponseFormatError('unexpected_http_status')
            return parse_result(data)
        except ProviderRejected:
            raise
        except Exception as error:
            details['error_type'] = type(error).__name__
            if isinstance(error, ResponseFormatError):
                details['parse_reason'] = str(error)
            raise ProviderUncertain(details) from None

    def download(self, url, path):
        parsed = urlsplit(url)
        if (parsed.scheme != 'https' or not (parsed.hostname or '').endswith('.aliyuncs.com')
                or parsed.username or parsed.password or parsed.port not in (None, 443)):
            raise ValueError('输出图片地址不是允许的阿里云HTTPS地址')
        with self.http.open(url, timeout=120) as response:
            data = response.read(25 * 1024 * 1024 + 1)
        if len(data) > 25 * 1024 * 1024:
            raise ValueError('图片超过25MB限制')
        temporary = path.with_suffix('.tmp')
        temporary.write_bytes(data)
        temporary.replace(path)
