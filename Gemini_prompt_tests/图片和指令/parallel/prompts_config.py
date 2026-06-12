"""
并行提示词测评配置模块

统一从 config.yaml + prompts/*.md 加载提示词和模型配置。
"""

import sys
from pathlib import Path
from typing import Iterator

ROOT_DIR = Path(__file__).resolve().parents[2]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from shared.common import ModelConfig, YAML_AVAILABLE
from shared.parallel_prompt_config import (
    CompositionRequest,
    ParallelPromptSet,
    generate_all_requests as _generate_all_requests,
    generate_composition_requests as _generate_composition_requests,
    generate_test_matrix as _generate_test_matrix,
    get_prompt_set_by_id as _get_prompt_set_by_id,
    load_config as _load_config,
    load_model_configs_from_yaml as _load_model_configs_from_yaml,
    load_prompts_from_yaml as _load_prompts_from_yaml,
)

CONFIG_FILE = Path(__file__).parent / "config.yaml"


def load_prompts_from_yaml(yaml_path: Path = CONFIG_FILE) -> list[ParallelPromptSet]:
    return _load_prompts_from_yaml(yaml_path)


def load_model_configs_from_yaml(yaml_path: Path = CONFIG_FILE) -> list[ModelConfig]:
    return _load_model_configs_from_yaml(yaml_path)


def load_config(yaml_path: Path = CONFIG_FILE) -> tuple[list[ParallelPromptSet], list[ModelConfig]]:
    return _load_config(yaml_path)


def get_prompt_set_by_id(prompt_id: str | None = None) -> ParallelPromptSet:
    return _get_prompt_set_by_id(CONFIG_FILE, prompt_id)


def generate_composition_requests(
    prompt_set: ParallelPromptSet | None = None,
    prompt_id: str | None = None,
    model_config: ModelConfig | None = None,
) -> list[CompositionRequest]:
    return _generate_composition_requests(
        CONFIG_FILE,
        prompt_set=prompt_set,
        prompt_id=prompt_id,
        model_config=model_config,
    )


def generate_all_requests(
    model_config: ModelConfig | None = None,
) -> dict[str, list[CompositionRequest]]:
    return _generate_all_requests(CONFIG_FILE, model_config=model_config)


def generate_test_matrix(
    prompt_sets: list[ParallelPromptSet] | None = None,
    model_configs: list[ModelConfig] | None = None,
) -> Iterator[tuple[str, str, CompositionRequest]]:
    return _generate_test_matrix(CONFIG_FILE, prompt_sets=prompt_sets, model_configs=model_configs)


PROMPT_VERSIONS = load_prompts_from_yaml()
MODEL_CONFIGS = load_model_configs_from_yaml()


if __name__ == "__main__":
    print("=" * 60)
    print("并行提示词配置预览")
    print("=" * 60)

    prompt_sets = load_prompts_from_yaml()
    model_configs = load_model_configs_from_yaml()

    print(f"\n📂 配置文件: {CONFIG_FILE}")
    print(f"   YAML 可用: {YAML_AVAILABLE}")

    print(f"\n📋 提示词集合 ({len(prompt_sets)}):")
    for prompt_set in prompt_sets:
        print(f"  - {prompt_set.id}: {prompt_set.description}")
        print(f"    包含 {len(prompt_set.user_prompts)} 种构图技术:")
        for technique_id in prompt_set.user_prompts.keys():
            print(f"      • {technique_id}")

    print(f"\n⚙️ 模型配置 ({len(model_configs)}):")
    for config in model_configs:
        print(f"  - {config.id}: temp={config.temperature}, topK={config.top_k}, topP={config.top_p}")

    print(f"\n🎯 生成请求示例 (第一个提示词版本):")
    if prompt_sets:
        for request in generate_composition_requests():
            print(f"  - {request.request_id}")

    all_requests = list(generate_test_matrix())
    print(f"\n📦 完整测试矩阵: {len(all_requests)} 组合")
