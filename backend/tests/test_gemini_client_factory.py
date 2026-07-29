"""Gemini Enterprise Agent Platform 客户端离线初始化测试。"""

import importlib.util
import sys
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import patch


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))


if "dashscope" not in sys.modules:
    dashscope_module = ModuleType("dashscope")
    dashscope_module.MultiModalConversation = SimpleNamespace(call=lambda *args, **kwargs: None)
    dashscope_module.base_http_api_url = ""
    sys.modules["dashscope"] = dashscope_module


if "google.genai" not in sys.modules:
    google_module = sys.modules.setdefault("google", ModuleType("google"))
    genai_module = ModuleType("google.genai")

    class _DummyClient:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs

    class _DummyHttpOptions:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    class _DummyConfig:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs
            self.__dict__.update(kwargs)

    class _DummyPart:
        @staticmethod
        def from_bytes(*args, **kwargs):
            return {"args": args, "kwargs": kwargs}

    genai_module.Client = _DummyClient
    genai_module.types = SimpleNamespace(
        HttpOptions=_DummyHttpOptions,
        GenerateContentConfig=_DummyConfig,
        ImageConfig=_DummyConfig,
        ThinkingConfig=_DummyConfig,
        Part=_DummyPart,
    )
    google_module.genai = genai_module
    sys.modules["google.genai"] = genai_module


FACTORY_PATH = BACKEND_DIR / "services" / "gemini_client_factory.py"
FACTORY_SPEC = importlib.util.spec_from_file_location(
    "gemini_client_factory_under_test",
    FACTORY_PATH,
)
if FACTORY_SPEC is None or FACTORY_SPEC.loader is None:
    raise RuntimeError(f"无法加载测试目标：{FACTORY_PATH}")
gemini_client_factory = importlib.util.module_from_spec(FACTORY_SPEC)
FACTORY_SPEC.loader.exec_module(gemini_client_factory)


class GeminiClientFactoryTests(unittest.TestCase):
    def test_official_client_uses_enterprise_v1_without_project_or_location(self):
        with (
            patch.object(gemini_client_factory, "USE_GEMINI_DOMESTIC_API", False),
            patch.object(gemini_client_factory, "GOOGLE_CLOUD_PROJECT", "picgen-503700"),
            patch.object(gemini_client_factory, "GOOGLE_API_KEY", "cloud-api-key"),
            patch.object(gemini_client_factory, "GEMINI_API_KEY", ""),
            patch.object(gemini_client_factory.genai, "Client", return_value=object()) as client,
        ):
            gemini_client_factory.create_gemini_client(timeout_seconds=2.5)

        kwargs = client.call_args.kwargs
        self.assertEqual(kwargs["api_key"], "cloud-api-key")
        self.assertTrue(kwargs["enterprise"])
        self.assertNotIn("project", kwargs)
        self.assertNotIn("location", kwargs)
        self.assertEqual(kwargs["http_options"].api_version, "v1")
        self.assertEqual(kwargs["http_options"].timeout, 2500)

    def test_legacy_key_keeps_enterprise_channel_and_prints_migration_notice(self):
        with (
            patch.object(gemini_client_factory, "USE_GEMINI_DOMESTIC_API", False),
            patch.object(gemini_client_factory, "GOOGLE_CLOUD_PROJECT", "picgen-503700"),
            patch.object(gemini_client_factory, "GOOGLE_API_KEY", ""),
            patch.object(gemini_client_factory, "GEMINI_API_KEY", "legacy-key"),
            patch.object(gemini_client_factory.genai, "Client", return_value=object()) as client,
            patch("builtins.print") as output,
        ):
            gemini_client_factory.create_gemini_client()

        self.assertTrue(client.call_args.kwargs["enterprise"])
        self.assertEqual(client.call_args.kwargs["api_key"], "legacy-key")
        self.assertTrue(
            any("GEMINI_API_KEY 已弃用" in str(call) for call in output.call_args_list)
        )
