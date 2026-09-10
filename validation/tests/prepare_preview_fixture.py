"""Synthetic rectangles for UI testing only; never Avatar production evidence."""
import sys
import wave
from pathlib import Path
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from avatar_lab.media import package

root = Path(__file__).resolve().parents[1] / 'workspace' / 'ui-test-fixtures'
root.mkdir(parents=True, exist_ok=True)
for action in ('idle', 'speaking', 'wave'):
    image = Image.new('RGB', (1536, 1536), '#ff00ff')
    draw = ImageDraw.Draw(image)
    for i in range(6):
        x, y = (i % 3)*512, (i // 3)*768
        draw.rectangle((x+120+i*3, y+150, x+390, y+650), fill='#294d42')
        draw.text((x+155, y+360), 'UI TEST ONLY', fill='white')
        draw.text((x+180, y+390), f'{action} {i+1}', fill='white')
    source = root / f'{action}.png'
    image.save(source)
    package(source, root / action, action)
with wave.open(str(root / 'silent-test.wav'), 'wb') as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(8000)
    audio.writeframes(b'\0\0' * 8000 * 12)
print('Synthetic UI test packages created; not generated Avatar assets.')
