import tempfile
import unittest
from pathlib import Path
from PIL import Image, ImageDraw
from avatar_lab.media import package, remove_background


class MediaTests(unittest.TestCase):
    def test_actual_generated_pink_background_is_removed(self):
        image = Image.new('RGB', (20, 20), (235, 36, 123))
        ImageDraw.Draw(image).rectangle((6, 5, 13, 14), fill=(48, 71, 91))
        result = remove_background(image)
        self.assertEqual(result.getpixel((1, 1)), (0, 0, 0, 0))
        self.assertEqual(result.getpixel((8, 8)), (48, 71, 91, 255))

    def test_interior_lip_color_is_not_changed_by_edge_cleanup(self):
        image = Image.new('RGB', (40, 40), (235, 36, 123))
        draw = ImageDraw.Draw(image)
        draw.rectangle((5, 5, 34, 34), fill=(220, 185, 153))
        draw.rectangle((16, 18, 23, 21), fill=(170, 65, 105))
        result = remove_background(image)
        self.assertEqual(result.getpixel((19, 19)), (170, 65, 105, 255))

    def test_key_removed_but_white_clothing_survives(self):
        image = Image.new('RGB', (10, 10), (255, 0, 255))
        ImageDraw.Draw(image).rectangle((3, 2, 6, 8), fill='white')
        result = remove_background(image)
        self.assertEqual(result.getpixel((0, 0)), (0, 0, 0, 0))
        self.assertEqual(result.getpixel((4, 4)), (255, 255, 255, 255))

    def test_wrong_dimensions_rejected_without_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'bad.png'
            Image.new('RGB', (100, 100)).save(source)
            with self.assertRaises(ValueError):
                package(source, Path(tmp) / 'out', 'idle')

    def test_six_frame_package_flags_identical_frames_and_blank_cells(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'strip.png'
            Image.new('RGB', (1536, 1536), '#ff00ff').save(source)
            result = package(source, Path(tmp) / 'out', 'idle')
            self.assertFalse(result['geometry_ok'])
            self.assertFalse(result['visual_approved'])
            self.assertIn('identical_frames', result['warnings'])
            self.assertEqual(len(result['frames']), 6)


if __name__ == '__main__':
    unittest.main()
