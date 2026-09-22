"""Focused contract checks for the local avatar action processor."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

from ruoyi_media.media.processor import process_action_board, validate_action_package


class AssetProcessorTests(unittest.TestCase):
    @staticmethod
    def _write_action_board(path: Path) -> None:
        board = Image.new("RGB", (1536, 1536), "#ff00ff")
        draw = ImageDraw.Draw(board)
        for index in range(6):
            column, row = index % 3, index // 3
            x, y = column * 512, row * 768
            draw.rectangle((x + 120 + index, y + 80, x + 390, y + 700), fill=(45 + index, 80, 110))
        board.save(path)

    def test_processes_six_transparent_frames_into_a_valid_action_atlas(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            source = temporary_path / "idle-board.png"
            output = temporary_path / "idle"
            self._write_action_board(source)

            manifest = process_action_board(source, output, "idle")

            self.assertEqual(manifest["action"], "idle")
            self.assertTrue(manifest["loop"])
            self.assertEqual(manifest["frameSize"], {"width": 512, "height": 768})
            self.assertEqual(len(manifest["frames"]), 6)
            self.assertEqual(manifest["atlas"]["layout"], {"columns": 3, "rows": 2})
            self.assertEqual(
                manifest["atlas"]["sha256"],
                hashlib.sha256((output / "atlas.png").read_bytes()).hexdigest(),
            )
            self.assertEqual(Image.open(output / "frame-00.png").getpixel((1, 1))[3], 0)
            self.assertEqual(validate_action_package(output), manifest)
            self.assertEqual(json.loads((output / "manifest.json").read_text(encoding="utf-8")), manifest)

    def test_rejects_an_atlas_whose_bytes_do_not_match_the_manifest_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            source = temporary_path / "wave-board.png"
            output = temporary_path / "wave"
            self._write_action_board(source)
            process_action_board(source, output, "wave")
            with (output / "atlas.png").open("ab") as atlas:
                atlas.write(b"tampered")

            with self.assertRaisesRegex(ValueError, "atlas hash mismatch"):
                validate_action_package(output)

    def test_geometry_ignores_low_alpha_edge_noise_but_rejects_opaque_content(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            source = temporary_path / "idle-board.png"
            output = temporary_path / "idle"
            self._write_action_board(source)
            with Image.open(source) as opened:
                board = opened.convert("RGB")
            board.putpixel((0, 0), (200, 0, 255))
            board.putpixel((512, 0), (45, 80, 110))
            board.save(source)

            manifest = process_action_board(source, output, "idle")

            self.assertEqual(manifest["warnings"], ["touches_border_01"])
            self.assertFalse(manifest["geometryOk"])


if __name__ == "__main__":
    unittest.main()
