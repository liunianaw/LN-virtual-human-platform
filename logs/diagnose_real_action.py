import io
import json
import os
import sys
from pathlib import Path
from PIL import Image
from inspect_worker_runtime import process_environment

root = Path(__file__).resolve().parents[1]
os.environ.update(process_environment(int(sys.argv[1])))
sys.path.insert(0, str(root / 'ruoyi-media/src'))
from ruoyi_media.worker.cos_writer import TencentCosObjectWriter
from ruoyi_media.worker.platform_http import _action_prompt, _generation_references
from ruoyi_media.providers.qwen_image import QwenImageProvider, ImageGenerationRequest, ProviderUncertain
from ruoyi_media.media import process_action_board

folder = root / 'logs/real-action-fixed'
folder.mkdir(exist_ok=True)
writer = TencentCosObjectWriter.from_environment()
source = writer._client.get_object(Bucket=writer._bucket,
    Key='avatar-sources/1/102078449035771907-b2da33eb9f844f1d81c11757e841677e.png')['Body'].get_raw_stream().read()
with Image.open(io.BytesIO(source)) as image:
    normalized=io.BytesIO()
    image.convert('RGBA').save(normalized,format='PNG')
reference, guide = _generation_references(normalized.getvalue())
request=ImageGenerationRequest(model='qwen-image-3.0-pro',prompt=_action_prompt('idle'),reference_png=reference,
    layout_guide_png=guide,parameters={'n':1,'size':'1536*1536','watermark':False,'prompt_extend':False,'seed':20260908})
provider=QwenImageProvider.from_environment()
try:
    result=provider.generate(request)
except ProviderUncertain as error:
    print(json.dumps({'phase':'provider','details':error.details}),flush=True)
    raise SystemExit(1)
(folder/'source.png').write_bytes(result.image_png)
(folder/'result.json').write_text(json.dumps({'requestId':result.request_id,'usage':result.usage}),encoding='utf-8')
print(json.dumps({'phase':'source_saved','requestId':result.request_id,'bytes':len(result.image_png),'usage':result.usage}),flush=True)
try:
    manifest=process_action_board(folder/'source.png',folder/'package','idle')
    print(json.dumps({'phase':'processed','warnings':manifest['warnings']}),flush=True)
except Exception as error:
    print(json.dumps({'phase':'processing_failed','type':type(error).__name__,'reason':str(error)}),flush=True)
