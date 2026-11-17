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
from schemas.composition import CompositionResult, CompositionStep, ShotSpec  # noqa: E402
from config import (  # noqa: E402
    TechniqueConfig,
    get_stage1_task_config,
    reset_runtime_stage1_task_configs,
    set_runtime_stage1_task_configs,
)
from services.common import Stage1Result, to_composition_result  # noqa: E402
from services.photographer_eye_tasks import build_direction_tasks  # noqa: E402
from services import stage1_qwen_provider  # noqa: E402
from services import stage2_qwen_provider  # noqa: E402
from services import stage2_openrouter_provider  # noqa: E402


class _Stage0Stub:
    provider_name = "stub"
    model_name = "stub-model"

    async def select_techniques(self, image_bytes, requested_techniques):
        return []


class _TaskStage0Stub:
    provider_name = "stub"
    model_name = "stub-model"

    async def select_stage1_tasks(self, image_bytes, requested_techniques):
        return [
            TechniqueConfig(
                id="photographer_eye_light_shadow_1",
                name="窗边逆光",
                user_prompt="direction prompt",
            )
        ]


class _EmptyTaskStage0Stub:
    provider_name = "stub"
    model_name = "stub-model"

    async def select_stage1_tasks(self, image_bytes, requested_techniques):
        return []


class CompositionPipelineGuardTests(unittest.IsolatedAsyncioTestCase):
    def _composition_result(self, technique: str) -> CompositionResult:
        return CompositionResult(
            technique=technique,
            technique_name=technique,
            aesthetic_desc="测试方案",
            steps=[
                CompositionStep(
                    step_order=1,
                    action_type="Shift",
                    direction="Left",
                    guide_text="左移",
                )
            ],
            shot_spec=ShotSpec(
                viewpoint_required=False,
                target_subject_center=[0.45, 0.5],
                target_subject_size=0.4,
            ),
            image_base64="data:image/png;base64,stub",
        )

    async def test_stage0_empty_shortlist_falls_back_to_requested_techniques(self):
        requested = ["rule_of_thirds", "leading_lines"]

        with patch.object(composition, "ENABLE_STAGE0", True):
            selected = await composition._select_techniques(
                stage0=_Stage0Stub(),
                image_bytes=b"fake-image",
                requested_techniques=requested,
            )

        self.assertEqual(selected, requested)

    async def test_stage0_direction_tasks_are_preserved_for_stage1(self):
        tasks = await composition._select_stage1_tasks(
            stage0=_TaskStage0Stub(),
            image_bytes=b"fake-image",
            requested_techniques=["rule_of_thirds", "leading_lines"],
        )

        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0].id, "photographer_eye_light_shadow_1")
        self.assertEqual(tasks[0].name, "窗边逆光")

    async def test_photographer_eye_empty_stage0_tasks_do_not_fallback_to_legacy_techniques(self):
        with patch.object(composition, "PHOTOGRAPHER_EYE_DIRECTION_MODE", True):
            tasks = await composition._select_stage1_tasks(
                stage0=_EmptyTaskStage0Stub(),
                image_bytes=b"fake-image",
                requested_techniques=["rule_of_thirds", "leading_lines"],
            )

        self.assertEqual(tasks, [])

    async def test_photographer_eye_disabled_stage0_does_not_fallback_to_legacy_techniques(self):
        with patch.object(composition, "PHOTOGRAPHER_EYE_DIRECTION_MODE", True), \
            patch.object(composition, "ENABLE_STAGE0", False):
            tasks = await composition._select_stage1_tasks(
                stage0=None,
                image_bytes=b"fake-image",
                requested_techniques=["rule_of_thirds", "leading_lines"],
            )

        self.assertEqual(tasks, [])

    def test_runtime_stage1_task_config_overrides_static_techniques(self):
        task = TechniqueConfig(
            id="photographer_eye_light_shadow_1",
            name="窗边逆光",
            user_prompt="direction prompt",
        )
        token = set_runtime_stage1_task_configs({task.id: task})
        try:
            resolved = get_stage1_task_config(task.id)
        finally:
            reset_runtime_stage1_task_configs(token)

        self.assertEqual(resolved.name, "窗边逆光")
        self.assertEqual(resolved.user_prompt, "direction prompt")

    def test_photographer_eye_results_are_not_near_deduplicated(self):
        geometry = self._composition_result("photographer_eye_geometry_order_1")
        perspective = self._composition_result("photographer_eye_perspective_shift_2")

        diversified = composition._diversify_compositions([geometry, perspective])

        self.assertEqual([item.technique for item in diversified], [
            "photographer_eye_geometry_order_1",
            "photographer_eye_perspective_shift_2",
        ])

    def test_legacy_near_duplicate_results_are_still_deduplicated(self):
        thirds = self._composition_result("rule_of_thirds")
        center = self._composition_result("center_composition")

        diversified = composition._diversify_compositions([thirds, center])

        self.assertEqual(len(diversified), 1)

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

        result.steps = [
            {"action_type": "shift", "direction": "left"},
            {"action_type": "zoom", "direction": "in"},
            {"action_type": "level", "direction": "cw"},
            {"action_type": "shift", "direction": "up"},
            {"action_type": "shift", "direction": "down"},
        ]
        composition_result = to_composition_result(result)

        self.assertIsNotNone(composition_result)
        self.assertEqual(len(composition_result.steps), 4)
        self.assertEqual(composition_result.steps[-1].direction, "Up")

    def test_shot_spec_contract_excludes_unused_text_fields(self):
        self.assertNotIn("camera_move_summary", ShotSpec.model_fields)
        self.assertNotIn("validation_notes", ShotSpec.model_fields)

    def test_openrouter_stage2_builds_reference_image_payload(self):
        with patch.object(stage2_openrouter_provider, "OPENROUTER_API_KEY", "test-key"):
            provider = stage2_openrouter_provider.OpenRouterStage2Provider()

        request = stage2_openrouter_provider.ImageRequest(
            technique_id="rule_of_thirds",
            image_prompt="camera-only reframing",
            original_image_b64="data:image/jpeg;base64,abc123",
        )

        payload = provider._build_payload(request)

        self.assertEqual(payload["model"], stage2_openrouter_provider.OPENROUTER_STAGE2_MODEL)
        self.assertEqual(payload["n"], 1)
        self.assertEqual(payload["resolution"], "1K")
        self.assertEqual(
            payload["input_references"][0]["image_url"]["url"],
            "data:image/jpeg;base64,abc123",
        )

    def test_openrouter_stage2_extracts_base64_data_url(self):
        payload = {
            "data": [
                {
                    "b64_json": "ZmFrZQ==",
                    "media_type": "image/webp",
                }
            ]
        }

        image_base64 = stage2_openrouter_provider._image_data_url_from_response(payload)

        self.assertEqual(image_base64, "data:image/webp;base64,ZmFrZQ==")

    def test_photographer_eye_direction_parser_builds_stage1_tasks(self):
        parsed = {
            "directions": [
                {
                    "id": "light_shadow",
                    "title": "窗边逆光",
                    "visual_evidence": "左侧窗光把杯子边缘打亮。",
                    "shooting_intent": "压暗背景突出杯壁透光。",
                },
                {
                    "id": "not_allowed",
                    "title": "非法方向",
                },
                {
                    "id": "foreground_frame",
                    "title": "门框远景",
                    "visual_evidence": "门框里能看到远处主体。",
                    "shooting_intent": "平移手机让门框完整圈住主体。",
                },
            ]
        }

        tasks = build_direction_tasks(parsed)

        self.assertEqual([task.id for task in tasks], [
            "photographer_eye_light_shadow_1",
            "photographer_eye_foreground_frame_2",
        ])
        self.assertIn("左侧窗光", tasks[0].user_prompt)
        self.assertIn("门框完整圈住主体", tasks[1].user_prompt)

    def test_prompt_sets_have_required_assets(self):
        for prompt_set in (
            "photographer_eye",
            "default_v1",
            "default_v2",
            "environment_understand",
        ):
            prompt_dir = BACKEND_DIR / "prompts" / prompt_set
            required_files = [
                prompt_dir / "stage0_soft_prefilter.md",
                prompt_dir / "stage1_system_instruction.md",
                prompt_dir / "techniques" / "rule_of_thirds.md",
                prompt_dir / "techniques" / "center_composition.md",
                prompt_dir / "techniques" / "leading_lines.md",
                prompt_dir / "techniques" / "foreground_framing.md",
                prompt_dir / "techniques" / "diagonal_composition.md",
            ]

            for path in required_files:
                self.assertTrue(path.is_file(), f"missing prompt asset: {path}")
                self.assertTrue(path.read_text(encoding="utf-8").strip())


if __name__ == "__main__":
    unittest.main()
