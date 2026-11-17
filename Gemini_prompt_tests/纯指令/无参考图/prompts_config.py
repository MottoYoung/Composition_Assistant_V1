"""
提示词测评配置模块

统一从 config.yaml + prompts/*.md 加载提示词和模型配置。
"""

import sys
from pathlib import Path
from typing import Iterator

ROOT_DIR = Path(__file__).resolve().parents[2]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from shared.common import ModelConfig, YAML_AVAILABLE
from shared.serial_prompt_config import (
    PromptConfig,
    TestCase,
    generate_test_matrix as _generate_test_matrix,
    load_config as _load_config,
    load_model_configs_from_yaml as _load_model_configs_from_yaml,
    load_prompts_from_yaml as _load_prompts_from_yaml,
    save_test_matrix as _save_test_matrix,
)

CONFIG_FILE = Path(__file__).parent / "config.yaml"


def load_prompts_from_yaml(yaml_path: Path = CONFIG_FILE) -> list[PromptConfig]:
    return _load_prompts_from_yaml(yaml_path)


def load_model_configs_from_yaml(yaml_path: Path = CONFIG_FILE) -> list[ModelConfig]:
    return _load_model_configs_from_yaml(yaml_path)


def load_config(yaml_path: Path = CONFIG_FILE) -> tuple[list[PromptConfig], list[ModelConfig]]:
    return _load_config(yaml_path)


def generate_test_matrix(
    prompts: list[PromptConfig] | None = None,
    model_configs: list[ModelConfig] | None = None,
    from_yaml: bool = True,
) -> Iterator[TestCase]:
    if prompts is None:
        prompts = load_prompts_from_yaml() if from_yaml else PROMPT_VERSIONS
    if model_configs is None:
        model_configs = load_model_configs_from_yaml() if from_yaml else MODEL_CONFIGS
    return _generate_test_matrix(CONFIG_FILE, prompts=prompts, model_configs=model_configs)


def save_test_matrix(
    output_path: str | Path,
    prompts: list[PromptConfig] | None = None,
    model_configs: list[ModelConfig] | None = None,
) -> dict:
    return _save_test_matrix(
        output_path,
        CONFIG_FILE,
        prompts=prompts,
        model_configs=model_configs,
    )


PROMPT_VERSIONS = load_prompts_from_yaml()
MODEL_CONFIGS = load_model_configs_from_yaml()


if __name__ == "__main__":
    print("=" * 60)
    print("测试矩阵预览")
    print("=" * 60)

    prompts = load_prompts_from_yaml()
    configs = load_model_configs_from_yaml()

    print(f"\n📂 配置文件: {CONFIG_FILE}")
    print(f"   YAML 可用: {YAML_AVAILABLE}")

    for case in generate_test_matrix():
        print(f"- {case.case_id}: {case.prompt.description} + {case.model_config.description}")

    print(f"\n总计: {len(prompts)} 提示词 × {len(configs)} 模型配置 = {len(prompts) * len(configs)} 组合")
