"""Explicit text-only regression: pinned upstream source and prepared local FSTs.

Set LN_VOICE_TEST_SOURCE and LN_VOICE_TEXT_RESOURCE_DIR, then run this file.
ONNX/model/tokenizer objects and optional espeak fallback are stubbed;
wetext normalizers, Chinese G2P and dictionary English G2P are real.
"""
import ast
from dataclasses import dataclass
from functools import partial
import importlib.metadata
import logging
import os
from pathlib import Path
import re
import socket
import shutil
import tempfile
import types
from typing import Callable, Generator, List, Optional, Tuple, Union
import unittest
from unittest.mock import Mock, patch
from ruoyi_voice.build_inputs import read_lock, verify_source, verify_resources
from ruoyi_voice.model_backends import KokoroBackend, load_backend


ROOT = Path(__file__).resolve().parents[1]


def official_class(path, name, scope):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == name)
    exec(compile(ast.Module(body=[cls], type_ignores=[]), str(path), "exec"), scope)
    return scope[name]


class LockedTextCheck(unittest.TestCase):
    def test_cosy_official_frontend_local_normalization_and_missing_resource_readiness(self):
        import wetext.wetext
        import inflect
        self.assertEqual("0.0.4", importlib.metadata.version("wetext"))
        self.assertEqual("1.8.0", importlib.metadata.version("kaldifst"))
        source = Path(os.environ["LN_VOICE_TEST_SOURCE"])
        lock = read_lock(ROOT / "model-runtimes/cosyvoice3/source.lock.json")
        verify_source(source, lock)
        resource_lock = read_lock(ROOT / "model-runtimes/cosyvoice3/text-resources.lock.json")
        resources = verify_resources(os.environ["LN_VOICE_TEXT_RESOURCE_DIR"], resource_lock)
        scope = dict(Callable=Callable, Generator=Generator, os=os, logging=logging, re=re,
                     partial=partial, torch=Mock(), onnxruntime=Mock(), inflect=inflect)
        exec((source / "cosyvoice/utils/frontend_utils.py").read_text(encoding="utf-8"), scope)
        frontend = official_class(source / "cosyvoice/cli/frontend.py", "CosyVoiceFrontEnd", scope)
        with tempfile.TemporaryDirectory() as cache, \
             patch.dict(os.environ, {"LN_VOICE_WETEXT_DIR": str(Path(cache) / "fst"), "MODELSCOPE_CACHE": cache, "HF_HOME": cache}), \
             patch.object(socket.socket, "connect", side_effect=AssertionError("Network forbidden")), \
             patch.object(wetext.wetext, "snapshot_download", side_effect=AssertionError("Implicit download forbidden")) as download:
            # Windows kaldifst cannot open the workspace's Unicode hyphen. Use the same verified bytes.
            shutil.copytree(resources, Path(cache) / "fst")
            verify_resources(Path(cache) / "fst", resource_lock)
            actual = frontend(lambda: Mock(encode=lambda text, **kw: list(text)), None, "fixture.onnx", "fixture.onnx")
            self.assertEqual("wetext", actual.text_frontend)
            for text, expected in [("今天是2026年10月3日，有123个苹果。", "今天是二零二六年十月三日，有一百二十三个苹果。"),
                                   ("I have 123 apples.", "I have one hundred and twenty three apples.")]:
                self.assertEqual(expected, actual.text_normalize(text, split=False))
            download.assert_not_called()
            with patch.dict(os.environ, {"LN_VOICE_WETEXT_DIR": cache}):
                with self.assertRaises(Exception):
                    frontend(lambda: None, None, "fixture.onnx", "fixture.onnx")
            for mode in ("missing", "corrupt"):
                if mode == "corrupt":
                    path = Path(cache) / resource_lock["files"][0]["path"]
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"invalid FST")
                with patch.dict(os.environ, {"LN_VOICE_TEXT_RESOURCE_LOCK": str(ROOT / "model-runtimes/cosyvoice3/text-resources.lock.json"),
                                              "LN_VOICE_TEXT_RESOURCE_DIR": cache}), \
                     patch("ruoyi_voice.model_backends.verify_source", return_value=source), \
                     patch("ruoyi_voice.model_backends.verify_weights", return_value=Path(cache)), \
                     patch("ruoyi_voice.model_backends.importlib.import_module") as native:
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        load_backend("COSYVOICE3", source, cache, lock, dict(providerType="COSYVOICE3", modelRevision=lock["weights"]["revision"]))
                    native.assert_not_called()

    def test_kokoro_actual_legacy_chinese_and_english_g2p_wiring(self):
        from misaki import en
        self.assertEqual("0.9.4", importlib.metadata.version("misaki"))
        source = Path(os.environ["LN_VOICE_TEST_KOKORO_SOURCE"])
        verify_source(source, read_lock(ROOT / "model-runtimes/kokoro/source.lock.json"))
        class Model:
            Output = Mock
            def __init__(self, **kwargs): pass
            def to(self, device): return self
            def eval(self): return self
        scope = dict(KModel=Model, Callable=Callable, Generator=Generator, List=List, Optional=Optional,
                     Tuple=Tuple, Union=Union, dataclass=dataclass, en=en,
                     espeak=types.SimpleNamespace(EspeakFallback=Mock(side_effect=ImportError("Optional native fallback absent in text fixture"))), re=re,
                     torch=Mock(), os=os, logger=Mock())
        scope.update(ALIASES={'zh': 'z', 'en-us': 'a'}, LANG_CODES={'a': 'American English', 'z': 'Mandarin Chinese'})
        pipeline = official_class(source / "kokoro/pipeline.py", "KPipeline", scope)
        with patch.dict("sys.modules", {"kokoro": types.SimpleNamespace(KModel=Model, KPipeline=pipeline),
                                       "loguru": types.SimpleNamespace(logger=Mock())}):
            backend = KokoroBackend(Path("fixture"), {"voices": []})
        chinese, english = backend.pipelines["zh-CN"], backend.pipelines["en-US"]
        original_en = english.g2p
        english.g2p = Mock(wraps=original_en)
        phones, _ = chinese.g2p("你好Hello，OpenAI有123个苹果。")
        self.assertEqual(["Hello", "OpenAI"], [call.args[0] for call in english.g2p.call_args_list])
        self.assertIn(original_en("Hello")[0], phones)
        self.assertIn(original_en("OpenAI")[0], phones)
        self.assertNotIn("Hello", phones)
        self.assertNotIn("123", phones)
        english.g2p.reset_mock()
        self.assertTrue(chinese.g2p("你好，有123个苹果。")[0])
        english.g2p.assert_not_called()
        self.assertEqual(original_en("Hello 123")[0], english.g2p("Hello 123")[0])


if __name__ == "__main__":
    unittest.main()
