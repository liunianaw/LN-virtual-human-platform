"""Deterministic, CPU-only processing for one generated avatar action board.

The input is a 1536 x 1536 board with six 512 x 768 action cells in 3 x 2
order.  This module intentionally has no provider, queue, database, or
credential dependency; the worker can call it after a generated image has
been retrieved by its own future adapter.
"""

from __future__ import annotations

import hashlib
import json
import math
import statistics
from pathlib import Path
from typing import Any

from PIL import Image, ImageFilter


ACTIONS = (
    "idle",
    "speaking",
    "listening",
    "thinking",
    "nod",
    "shake_head",
    "wave",
    "happy",
)
LOOPING_ACTIONS = frozenset(("idle", "speaking", "listening", "thinking"))
FRAME_WIDTH = 512
FRAME_HEIGHT = 768
FRAME_COUNT = 6
ATLAS_COLUMNS = 3
ATLAS_ROWS = 2
ATLAS_WIDTH = FRAME_WIDTH * ATLAS_COLUMNS
ATLAS_HEIGHT = FRAME_HEIGHT * ATLAS_ROWS


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def remove_chroma_background(image: Image.Image) -> Image.Image:
    """Remove a saturated perimeter key while preserving foreground alpha.

    This is the validation tool's deterministic matte logic, adapted as a
    reusable primitive.  Neutral/dark keys are rejected to avoid erasing dark
    clothing; visual quality is deliberately left for the later review step.
    """

    image = image.convert("RGBA")
    width, height = image.size
    border = (
        [image.getpixel((x, 0)) for x in range(width)]
        + [image.getpixel((x, height - 1)) for x in range(width)]
        + [image.getpixel((0, y)) for y in range(height)]
        + [image.getpixel((width - 1, y)) for y in range(height)]
    )
    key = tuple(statistics.median(pixel[channel] for pixel in border) for channel in range(3))
    if max(key) - min(key) < 80:
        raise ValueError("action board perimeter must use a saturated chroma background")

    channels = [channel.tobytes() for channel in image.split()]
    distances = [math.dist((red, green, blue), key) for red, green, blue in zip(*channels[:3], strict=True)]
    background = Image.new("L", image.size)
    background.putdata([255 if distance <= 45 else 0 for distance in distances])
    boundary = background.filter(ImageFilter.MaxFilter(5)).tobytes()
    pixels: list[tuple[int, int, int, int]] = []
    for index, (red, green, blue, alpha) in enumerate(zip(*channels, strict=True)):
        distance = distances[index]
        if not alpha or distance <= 45:
            pixels.append((0, 0, 0, 0))
        elif boundary[index] and distance < 95:
            edge_alpha = (distance - 45) / 50
            cleaned = tuple(
                max(0, min(255, round((value - (1 - edge_alpha) * key_value) / edge_alpha)))
                for value, key_value in zip((red, green, blue), key, strict=True)
            )
            final_alpha = round(alpha * edge_alpha)
            pixels.append((*cleaned, final_alpha) if final_alpha else (0, 0, 0, 0))
        else:
            pixels.append((red, green, blue, alpha) if alpha else (0, 0, 0, 0))
    image.putdata(pixels)

    alpha = image.getchannel("A")
    interior = alpha.filter(ImageFilter.MinFilter(5))
    inner = interior.load()
    source = image.copy().load()
    target = image.load()
    offsets = sorted(
        ((delta_x, delta_y) for delta_x in range(-4, 5) for delta_y in range(-4, 5) if delta_x or delta_y),
        key=lambda point: point[0] * point[0] + point[1] * point[1],
    )
    for y in range(height):
        for x in range(width):
            red, green, blue, alpha_value = source[x, y]
            if not alpha_value or inner[x, y] == 255 or (alpha_value == 255 and distances[y * width + x] >= 140):
                continue
            for delta_x, delta_y in offsets:
                neighbor_x, neighbor_y = x + delta_x, y + delta_y
                if 0 <= neighbor_x < width and 0 <= neighbor_y < height and inner[neighbor_x, neighbor_y] == 255:
                    target[x, y] = (*source[neighbor_x, neighbor_y][:3], alpha_value)
                    break
    return image


def process_action_board(source: str | Path, target: str | Path, action: str) -> dict[str, Any]:
    """Create transparent frames, a 3 x 2 atlas, and an integrity manifest."""

    if action not in ACTIONS:
        raise ValueError(f"action must be one of: {', '.join(ACTIONS)}")
    source_path = Path(source)
    with Image.open(source_path) as opened:
        if opened.size != (ATLAS_WIDTH, ATLAS_HEIGHT):
            raise ValueError("action board must be exactly 1536 x 1536; it is never resized")
        board = opened.convert("RGBA")

    frames: list[Image.Image] = []
    warnings: list[str] = []
    for index in range(FRAME_COUNT):
        column, row = index % ATLAS_COLUMNS, index // ATLAS_COLUMNS
        frame = remove_chroma_background(
            board.crop(
                (
                    column * FRAME_WIDTH,
                    row * FRAME_HEIGHT,
                    (column + 1) * FRAME_WIDTH,
                    (row + 1) * FRAME_HEIGHT,
                )
            )
        )
        # Chroma-key antialiasing can leave isolated, low-alpha edge pixels.
        # Geometry checks should only treat visibly opaque pixels as content.
        bounding_box = frame.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0).getbbox()
        if not bounding_box:
            warnings.append(f"empty_frame_{index:02d}")
        elif (
            bounding_box[0] < 3
            or bounding_box[1] < 3
            or bounding_box[2] > FRAME_WIDTH - 3
            or bounding_box[3] > FRAME_HEIGHT - 3
        ):
            warnings.append(f"touches_border_{index:02d}")
        frames.append(frame)

    if len({frame.tobytes() for frame in frames}) == 1:
        warnings.append("identical_frames")

    target_path = Path(target)
    target_path.mkdir(parents=True, exist_ok=True)
    atlas = Image.new("RGBA", (ATLAS_WIDTH, ATLAS_HEIGHT), (0, 0, 0, 0))
    frame_records: list[dict[str, Any]] = []
    for index, frame in enumerate(frames):
        column, row = index % ATLAS_COLUMNS, index // ATLAS_COLUMNS
        filename = f"frame-{index:02d}.png"
        frame_path = target_path / filename
        frame.save(frame_path, format="PNG")
        atlas.alpha_composite(frame, (column * FRAME_WIDTH, row * FRAME_HEIGHT))
        frame_records.append(
            {
                "file": filename,
                "sha256": _sha256(frame_path),
                "x": column * FRAME_WIDTH,
                "y": row * FRAME_HEIGHT,
                "width": FRAME_WIDTH,
                "height": FRAME_HEIGHT,
            }
        )

    atlas_path = target_path / "atlas.png"
    atlas.save(atlas_path, format="PNG")
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "framing": "FULL_BODY",
        "action": action,
        "frameSize": {"width": FRAME_WIDTH, "height": FRAME_HEIGHT},
        "frameCount": FRAME_COUNT,
        "fps": 6,
        "loop": action in LOOPING_ACTIONS,
        "sourceSha256": _sha256(source_path),
        "frames": frame_records,
        "atlas": {
            "file": "atlas.png",
            "sha256": _sha256(atlas_path),
            "width": ATLAS_WIDTH,
            "height": ATLAS_HEIGHT,
            "layout": {"columns": ATLAS_COLUMNS, "rows": ATLAS_ROWS},
        },
        "warnings": warnings,
        "geometryOk": not warnings,
    }
    (target_path / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return validate_action_package(target_path)


def validate_action_package(directory: str | Path) -> dict[str, Any]:
    """Validate the fixed action contract and on-disk PNG hashes.

    A geometrically suspect package is still structurally valid so that a
    later human-review gate can show the warnings; tampered or malformed files
    raise ``ValueError`` immediately.
    """

    directory_path = Path(directory)
    manifest_path = directory_path / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("manifest.json must be readable JSON") from error
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be an object")
    if manifest.get("schemaVersion") != 1 or manifest.get("framing") != "FULL_BODY":
        raise ValueError("unsupported action manifest schema")

    action = manifest.get("action")
    if action not in ACTIONS:
        raise ValueError("manifest action is not one of the eight standard actions")
    if manifest.get("frameSize") != {"width": FRAME_WIDTH, "height": FRAME_HEIGHT}:
        raise ValueError("manifest frameSize must be 512 x 768")
    if manifest.get("frameCount") != FRAME_COUNT or manifest.get("fps") != 6:
        raise ValueError("manifest requires six frames at 6 fps")
    if manifest.get("loop") is not (action in LOOPING_ACTIONS):
        raise ValueError("manifest loop setting does not match the action contract")

    frames = manifest.get("frames")
    if not isinstance(frames, list) or len(frames) != FRAME_COUNT:
        raise ValueError("manifest must contain exactly six ordered frame records")
    for index, frame in enumerate(frames):
        expected = {
            "file": f"frame-{index:02d}.png",
            "x": (index % ATLAS_COLUMNS) * FRAME_WIDTH,
            "y": (index // ATLAS_COLUMNS) * FRAME_HEIGHT,
            "width": FRAME_WIDTH,
            "height": FRAME_HEIGHT,
        }
        if not isinstance(frame, dict) or any(frame.get(field) != value for field, value in expected.items()):
            raise ValueError(f"frame {index} does not match the fixed atlas layout")
        frame_path = directory_path / expected["file"]
        _validate_png(frame_path, FRAME_WIDTH, FRAME_HEIGHT, f"frame {index}")
        if frame.get("sha256") != _sha256(frame_path):
            raise ValueError(f"frame {index} hash mismatch")

    atlas = manifest.get("atlas")
    expected_atlas = {
        "file": "atlas.png",
        "width": ATLAS_WIDTH,
        "height": ATLAS_HEIGHT,
        "layout": {"columns": ATLAS_COLUMNS, "rows": ATLAS_ROWS},
    }
    if not isinstance(atlas, dict) or any(atlas.get(field) != value for field, value in expected_atlas.items()):
        raise ValueError("atlas does not match the fixed 3 x 2 layout")
    atlas_path = directory_path / "atlas.png"
    _validate_png(atlas_path, ATLAS_WIDTH, ATLAS_HEIGHT, "atlas")
    if atlas.get("sha256") != _sha256(atlas_path):
        raise ValueError("atlas hash mismatch")

    warnings = manifest.get("warnings")
    if not isinstance(warnings, list) or not all(isinstance(warning, str) for warning in warnings):
        raise ValueError("manifest warnings must be a string list")
    if manifest.get("geometryOk") is not (not warnings):
        raise ValueError("geometryOk must reflect the processing warnings")
    if not isinstance(manifest.get("sourceSha256"), str) or len(manifest["sourceSha256"]) != 64:
        raise ValueError("manifest sourceSha256 must be a SHA-256 digest")
    return manifest


def _validate_png(path: Path, width: int, height: int, label: str) -> None:
    try:
        with Image.open(path) as image:
            if image.format != "PNG" or image.mode != "RGBA" or image.size != (width, height):
                raise ValueError(f"{label} must be a transparent RGBA PNG of the declared size")
            if image.getchannel("A").getextrema()[0] != 0:
                raise ValueError(f"{label} must contain transparent pixels")
    except OSError as error:
        raise ValueError(f"{label} PNG cannot be read") from error
