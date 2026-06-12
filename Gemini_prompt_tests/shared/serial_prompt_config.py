"""Shared serial prompt configuration loader."""

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Iterator

from shared.common import (
    ModelConfig,
    load_model_configs_from_yaml as _load_model_configs_from_yaml,
    load_yaml_data,
    resolve_text_field,
)


@dataclass
class PromptConfig:
    """提示词配置。"""

    id: str
    system_instruction: str
    user_prompt: str
    description: str = ""

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "system_instruction": self.system_instruction,
            "user_prompt": self.user_prompt,
            "description": self.description,
        }


@dataclass
class TestCase:
    """单个测试用例 = 提示词 × 模型配置。"""

    prompt: PromptConfig
    model_config: ModelConfig

    @property
    def case_id(self) -> str:
        return f"{self.prompt.id}_{self.model_config.id}"

    def to_dict(self) -> dict:
        return {
            "case_id": self.case_id,
            "prompt": self.prompt.to_dict(),
            "model_config": self.model_config.to_dict(),
        }


def load_prompts_from_yaml(yaml_path: Path) -> list[PromptConfig]:
    """从 YAML 文件加载串行模式提示词配置。"""
    data = load_yaml_data(yaml_path)
    if data is None:
        return []

    base_dir = yaml_path.parent
    prompts: list[PromptConfig] = []
    for entry in data.get("prompts", []):
        prompt_id = entry["id"]
        prompts.append(
            PromptConfig(
                id=prompt_id,
                description=entry.get("description", ""),
                system_instruction=resolve_text_field(
                    entry,
                    inline_key="system_instruction",
                    file_key="system_instruction_file",
                    base_dir=base_dir,
                    prompt_id=prompt_id,
                ),
                user_prompt=resolve_text_field(
                    entry,
                    inline_key="user_prompt",
                    file_key="user_prompt_file",
                    base_dir=base_dir,
                    prompt_id=prompt_id,
                ),
            )
        )

    return prompts


def load_model_configs_from_yaml(yaml_path: Path) -> list[ModelConfig]:
    """从 YAML 文件加载模型配置。"""
    return _load_model_configs_from_yaml(yaml_path)


def load_config(yaml_path: Path) -> tuple[list[PromptConfig], list[ModelConfig]]:
    """从 YAML 文件加载全部配置。"""
    return load_prompts_from_yaml(yaml_path), load_model_configs_from_yaml(yaml_path)


def generate_test_matrix(
    yaml_path: Path,
    prompts: list[PromptConfig] | None = None,
    model_configs: list[ModelConfig] | None = None,
) -> Iterator[TestCase]:
    """生成串行模式测试矩阵。"""
    if prompts is None:
        prompts = load_prompts_from_yaml(yaml_path)
    if model_configs is None:
        model_configs = load_model_configs_from_yaml(yaml_path)

    for prompt in prompts:
        for model_config in model_configs:
            yield TestCase(prompt=prompt, model_config=model_config)


def save_test_matrix(
    output_path: str | Path,
    yaml_path: Path,
    prompts: list[PromptConfig] | None = None,
    model_configs: list[ModelConfig] | None = None,
) -> dict:
    """保存测试矩阵到 JSON 文件。"""
    prompt_list = prompts if prompts is not None else load_prompts_from_yaml(yaml_path)
    model_config_list = (
        model_configs if model_configs is not None else load_model_configs_from_yaml(yaml_path)
    )
    cases = list(generate_test_matrix(yaml_path, prompt_list, model_config_list))

    data = {
        "total_cases": len(cases),
        "prompts_count": len(prompt_list),
        "model_configs_count": len(model_config_list),
        "cases": [case.to_dict() for case in cases],
    }

    output_file = Path(output_path)
    output_file.write_text(
        json.dumps(data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return data
