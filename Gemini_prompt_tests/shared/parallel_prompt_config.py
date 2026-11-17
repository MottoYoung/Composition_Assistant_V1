"""Shared parallel prompt configuration loader."""

from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

from shared.common import (
    ModelConfig,
    build_parallel_user_prompts,
    load_model_configs_from_yaml as _load_model_configs_from_yaml,
    load_yaml_data,
    resolve_text_field,
)


@dataclass
class ParallelPromptSet:
    """并行提示词集合 = 统一 System Prompt + 多个 User Prompt。"""

    id: str
    description: str
    system_instruction: str
    user_prompts: dict[str, str]

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "description": self.description,
            "system_instruction": self.system_instruction,
            "user_prompts": self.user_prompts,
        }


@dataclass
class CompositionRequest:
    """单个构图方案请求。"""

    technique_id: str
    user_prompt: str
    system_instruction: str
    model_config: ModelConfig

    @property
    def request_id(self) -> str:
        return f"{self.technique_id}_{self.model_config.id}"

    def to_dict(self) -> dict:
        return {
            "request_id": self.request_id,
            "technique_id": self.technique_id,
            "user_prompt": self.user_prompt,
            "model_config": self.model_config.to_dict(),
        }


def load_prompts_from_yaml(yaml_path: Path) -> list[ParallelPromptSet]:
    """从 YAML 文件加载并行模式提示词配置。"""
    data = load_yaml_data(yaml_path)
    if data is None:
        return []

    base_dir = yaml_path.parent
    prompt_sets: list[ParallelPromptSet] = []
    for entry in data.get("prompts", []):
        prompt_id = entry["id"]
        user_prompts = build_parallel_user_prompts(entry, base_dir=base_dir)
        if not user_prompts:
            continue

        prompt_sets.append(
            ParallelPromptSet(
                id=prompt_id,
                description=entry.get("description", ""),
                system_instruction=resolve_text_field(
                    entry,
                    inline_key="system_instruction",
                    file_key="system_instruction_file",
                    base_dir=base_dir,
                    prompt_id=prompt_id,
                ),
                user_prompts=user_prompts,
            )
        )

    return prompt_sets


def load_model_configs_from_yaml(yaml_path: Path) -> list[ModelConfig]:
    """从 YAML 文件加载模型配置。"""
    return _load_model_configs_from_yaml(yaml_path)


def get_prompt_set_by_id(
    yaml_path: Path,
    prompt_id: str | None = None,
) -> ParallelPromptSet:
    """根据 ID 获取并行提示词集合。"""
    prompt_sets = load_prompts_from_yaml(yaml_path)
    if not prompt_sets:
        raise ValueError("未找到提示词配置")

    if prompt_id is None:
        return prompt_sets[0]

    for prompt_set in prompt_sets:
        if prompt_set.id == prompt_id:
            return prompt_set

    available_ids = [prompt_set.id for prompt_set in prompt_sets]
    raise ValueError(f"未找到提示词集合: {prompt_id}，可用: {available_ids}")


def generate_composition_requests(
    yaml_path: Path,
    prompt_set: ParallelPromptSet | None = None,
    prompt_id: str | None = None,
    model_config: ModelConfig | None = None,
) -> list[CompositionRequest]:
    """生成指定提示词集合下的所有构图请求。"""
    if prompt_set is None:
        prompt_set = get_prompt_set_by_id(yaml_path, prompt_id)

    if model_config is None:
        model_config = load_model_configs_from_yaml(yaml_path)[0]

    return [
        CompositionRequest(
            technique_id=technique_id,
            user_prompt=user_prompt,
            system_instruction=prompt_set.system_instruction,
            model_config=model_config,
        )
        for technique_id, user_prompt in prompt_set.user_prompts.items()
    ]


def generate_all_requests(
    yaml_path: Path,
    model_config: ModelConfig | None = None,
) -> dict[str, list[CompositionRequest]]:
    """为所有并行提示词集合生成构图请求。"""
    if model_config is None:
        model_config = load_model_configs_from_yaml(yaml_path)[0]

    return {
        prompt_set.id: [
            CompositionRequest(
                technique_id=technique_id,
                user_prompt=user_prompt,
                system_instruction=prompt_set.system_instruction,
                model_config=model_config,
            )
            for technique_id, user_prompt in prompt_set.user_prompts.items()
        ]
        for prompt_set in load_prompts_from_yaml(yaml_path)
    }


def generate_test_matrix(
    yaml_path: Path,
    prompt_sets: list[ParallelPromptSet] | None = None,
    model_configs: list[ModelConfig] | None = None,
) -> Iterator[tuple[str, str, CompositionRequest]]:
    """生成并行模式完整测试矩阵。"""
    if prompt_sets is None:
        prompt_sets = load_prompts_from_yaml(yaml_path)
    if model_configs is None:
        model_configs = load_model_configs_from_yaml(yaml_path)

    for prompt_set in prompt_sets:
        for model_config in model_configs:
            for technique_id, user_prompt in prompt_set.user_prompts.items():
                yield (
                    prompt_set.id,
                    technique_id,
                    CompositionRequest(
                        technique_id=technique_id,
                        user_prompt=user_prompt,
                        system_instruction=prompt_set.system_instruction,
                        model_config=model_config,
                    ),
                )


def load_config(yaml_path: Path) -> tuple[list[ParallelPromptSet], list[ModelConfig]]:
    """从 YAML 文件加载全部配置。"""
    return load_prompts_from_yaml(yaml_path), load_model_configs_from_yaml(yaml_path)
