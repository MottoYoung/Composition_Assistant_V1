import asyncio
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

    class _DummyConversation:
        @staticmethod
        def call(*args, **kwargs):
            raise RuntimeError("stub")

    dashscope_module.MultiModalConversation = _DummyConversation
    dashscope_module.base_http_api_url = ""
    sys.modules["dashscope"] = dashscope_module


if "google.genai" not in sys.modules:
    google_module = sys.modules.setdefault("google", ModuleType("google"))
    genai_module = ModuleType("google.genai")

    class _DummyClient:
        def __init__(self, *args, **kwargs):
            self._api_client = SimpleNamespace(
                _http_options=SimpleNamespace(headers={})
            )

    class _DummyConfig:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs

    class _DummyPart:
        @staticmethod
        def from_bytes(*args, **kwargs):
            return {"args": args, "kwargs": kwargs}

    genai_module.Client = _DummyClient
    genai_module.types = SimpleNamespace(
        HttpOptions=_DummyConfig,
        GenerateContentConfig=_DummyConfig,
        ImageConfig=_DummyConfig,
        ThinkingConfig=_DummyConfig,
        Part=_DummyPart,
    )
    google_module.genai = genai_module
    sys.modules["google.genai"] = genai_module

from routers import composition  # noqa: E402
from schemas.composition import ShotSpec  # noqa: E402
from services.common import Stage1Result, to_composition_result  # noqa: E402
from services import stage1_qwen_provider  # noqa: E402
from services import stage2_qwen_provider  # noqa: E402


class _Stage0Stub:
    provider_name = "stub"
    model_name = "stub-model"

    async def select_techniques(self, image_bytes, requested_techniques):
        return []


class CompositionPipelineGuardTests(unittest.IsolatedAsyncioTestCase):
    async def test_stage0_empty_shortlist_falls_back_to_requested_techniques(self):
        requested = ["rule_of_thirds", "leading_lines"]

        with patch.object(composition, "ENABLE_STAGE0", True):
            selected = await composition._select_techniques(
                stage0=_Stage0Stub(),
                image_bytes=b"fake-image",
                requested_techniques=requested,
            )

        self.assertEqual(selected, requested)

    async def test_qwen_stage2_download_failure_does_not_report_success(self):
        with patch.object(stage2_qwen_provider, "DASHSCOPE_API_KEY", "test-key"):
            provider = stage2_qwen_provider.QwenStage2Provider()

        request = stage2_qwen_provider.ImageRequest(
            technique_id="rule_of_thirds",
            image_prompt="camera-only reframing",
            original_image_b64=None,
        )

        with patch.object(
            provider,
            "_call_sync",
            return_value=(True, ["https://example.com/fake.png"], ""),
        ), patch.object(stage2_qwen_provider, "_url_to_base64", return_value=None):
            result, _ = await provider.generate_single_with_timing(request)

        self.assertFalse(result.success)
        self.assertIsNone(result.image_base64)
        self.assertTrue(result.error_message)

    def test_qwen_stage1_stream_skips_empty_choice_chunks(self):
        provider = object.__new__(stage1_qwen_provider.QwenStage1Provider)
        provider.model_name = "test-model"
        provider.client = SimpleNamespace(
            chat=SimpleNamespace(
                completions=SimpleNamespace(
                    create=lambda **kwargs: iter(
                        [
                            SimpleNamespace(choices=[]),
                            SimpleNamespace(
                                choices=[
                                    SimpleNamespace(
                                        delta=SimpleNamespace(content='{"is_applicable":')
                                    )
                                ]
                            ),
                            SimpleNamespace(
                                choices=[
                                    SimpleNamespace(
                                        delta=SimpleNamespace(content="false}")
                                    )
                                ]
                            ),
                        ]
                    )
                )
            )
        )

        with patch.object(provider, "_build_messages", return_value=[]):
            content = provider._stream_call_sync(
                "data:image/jpeg;base64,stub",
                "rule_of_thirds",
            )

        self.assertEqual(content, '{"is_applicable":false}')

    def test_applicable_stage1_result_requires_valid_steps(self):
        result = Stage1Result(
            technique_id="rule_of_thirds",
            technique_name="三分构图",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            response_time_ms=1000,
            success=True,
            is_applicable=True,
            image_prompt="camera-only reframing",
            steps=[],
        )

        self.assertIsNone(to_composition_result(result))

        result.steps = [{"action_type": "Pan", "direction": "Sideways"}]
        self.assertIsNone(to_composition_result(result))

        result.steps = [{"action_type": "shift", "direction": "left"}]
        composition_result = to_composition_result(result)

        self.assertIsNotNone(composition_result)
        self.assertEqual(composition_result.steps[0].action_type, "Shift")
        self.assertEqual(composition_result.steps[0].direction, "Left")

    def test_shot_spec_contract_excludes_unused_text_fields(self):
        self.assertNotIn("camera_move_summary", ShotSpec.model_fields)
        self.assertNotIn("validation_notes", ShotSpec.model_fields)


if __name__ == "__main__":
    unittest.main()
