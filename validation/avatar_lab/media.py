import hashlib
import json
import math
import statistics
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat, ImageFilter
from .prompts import ACTIONS


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def remove_background(image):
    """Estimate flat chroma background from perimeter, then unmix edge pixels.

    Inputs must have foreground-free margins and a color absent from the
    character. This deterministic provisional matte requires visual review.
    """
    image = image.convert('RGBA')
    w, h = image.size
    border = ([image.getpixel((x, 0)) for x in range(w)]
              + [image.getpixel((x, h-1)) for x in range(w)]
              + [image.getpixel((0, y)) for y in range(h)]
              + [image.getpixel((w-1, y)) for y in range(h)])
    key = tuple(statistics.median(p[c] for p in border) for c in range(3))
    # Reject ordinary dark/neutral backgrounds instead of deleting dark clothes.
    if max(key) - min(key) < 80:
        raise ValueError('边缘未检测到饱和纯色背景，请检查生成构图')
    channels = [channel.tobytes() for channel in image.split()]
    distances = [math.dist((r,g,b), key) for r,g,b in zip(*channels[:3])]
    background = Image.new('L', image.size)
    background.putdata([255 if distance <= 45 else 0 for distance in distances])
    boundary = background.filter(ImageFilter.MaxFilter(5)).tobytes()
    pixels = []
    for index, (r, g, b, a) in enumerate(zip(*channels)):
        distance = distances[index]
        if not a or distance <= 45:
            pixels.append((0, 0, 0, 0))
        elif boundary[index] and distance < 95:
            alpha = (distance - 45) / 50
            cleaned = tuple(max(0, min(255, round((v-(1-alpha)*k)/alpha)))
                            for v, k in zip((r, g, b), key))
            final_alpha = round(a*alpha)
            pixels.append((*cleaned, final_alpha) if final_alpha else (0, 0, 0, 0))
        else:
            pixels.append((r, g, b, a) if a else (0, 0, 0, 0))
    image.putdata(pixels)
    # Copy clean neighboring foreground color into the thin key-contaminated
    # boundary, preserving alpha. Interior face/clothing pixels are untouched.
    alpha = image.getchannel('A')
    interior = alpha.filter(ImageFilter.MinFilter(5))
    inner = interior.load()
    source = image.copy().load()
    target = image.load()
    offsets = sorted(((dx,dy) for dx in range(-4,5) for dy in range(-4,5)
                      if dx or dy), key=lambda p:p[0]*p[0]+p[1]*p[1])
    for y in range(h):
        for x in range(w):
            r,g,b,a = source[x,y]
            if not a or inner[x,y] == 255 or (a == 255 and distances[y*w+x] >= 140):
                continue
            for dx,dy in offsets:
                nx,ny = x+dx,y+dy
                if 0 <= nx < w and 0 <= ny < h and inner[nx,ny] == 255:
                    target[x,y] = (*source[nx,ny][:3], a)
                    break
    return image


def package(source, target, action):
    if action not in ACTIONS:
        raise ValueError('只能打包标准动作')
    with Image.open(source) as opened:
        if opened.size != (1536, 1536):
            raise ValueError('动作板必须为1536×1536，请检查生成结果，不自动拉伸')
        original = opened.convert('RGBA')
    target = Path(target)
    target.mkdir(parents=True, exist_ok=True)
    frames, boxes, names, warnings = [], [], [], []
    for index in range(6):
        col, row = index % 3, index // 3
        frame = remove_background(original.crop((col*512, row*768, (col+1)*512, (row+1)*768)))
        box = frame.getchannel('A').getbbox()
        boxes.append(box)
        if not box:
            warnings.append(f'empty_frame_{index}')
        elif box[0] < 3 or box[1] < 3 or box[2] > 509 or box[3] > 765:
            warnings.append(f'touches_border_{index}')
        name = f'{action}-{index}.png'
        frame.save(target / name)
        names.append(name)
        frames.append(frame)
    hashes = [hashlib.sha256(f.tobytes()).hexdigest() for f in frames]
    if len(set(hashes)) == 1:
        warnings.append('identical_frames')
    differences = [round(sum(ImageStat.Stat(ImageChops.difference(frames[i-1], frames[i])).mean), 3)
                   for i in range(6)]
    sheet = Image.new('RGB', (1536, 1536), '#e9e4db')
    for i, frame in enumerate(frames):
        sheet.paste(frame, ((i % 3)*512, (i // 3)*768), frame)
    ImageDraw.Draw(sheet).text((8, 8), f'{action} / QA only / not approved', fill='#1b3430')
    sheet.save(target / 'contact-sheet.jpg', quality=90)
    # QA GIF on an opaque background avoids disposal/alpha artifacts in GIF viewers.
    previews = []
    for frame in frames:
        bg = Image.new('RGB', frame.size, '#e9e4db')
        bg.paste(frame, mask=frame.getchannel('A'))
        previews.append(bg.resize((256, 384)))
    previews[0].save(target / 'preview.gif', save_all=True, append_images=previews[1:], duration=160, loop=0)
    result = {'schema_version': 1, 'framing': 'full-body', 'action': action, 'width': 512, 'height': 768,
              'fps': 6, 'loop': action in ('idle', 'speaking', 'listening', 'thinking'),
              'frames': names, 'frame_hashes': [sha256(target / name) for name in names],
              'source_sha256': sha256(source), 'bounding_boxes': boxes,
              'frame_difference': differences, 'warnings': warnings,
              'geometry_ok': not warnings, 'visual_approved': False,
              'review_note': '自动检查不证明人物一致性、手部正确、动作自然或透明边缘合格'}
    (target / 'manifest.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result
