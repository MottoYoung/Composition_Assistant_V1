"""Common helpers for Gemini prompt test configuration loading."""

from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    import yaml

    YAML_AVAILABLE = True
except ImportError:
    YAML_AVAILABLE = True
    yaml = None


TECHNIQUE_KEY_MAP = {
    "user_prompt_A": "rule_of_thirds",
    "user_prompt_B": "center_composition",
    "user_prompt_C": "leading_lines",
    "user_prompt_D": "foreground_framing",
    "user_prompt_E": "diagonal_composition",
}


@dataclass
class ModelConfig:
    """模型参数配置。"""

    id: str
    temperature: float = 1.0
    top_k: int = 40
    top_p: float = 0.95
    description: str = ""

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "temperature": self.temperature,
            "top_k": self.top_k,
            "top_p": self.top_p,
            "description": self.description,
        }


DEFAULT_MODEL_CONFIGS = [
    ModelConfig(
        id="default",
        temperature=1.0,
        top_k=40,
        top_p=0.95,
        description="默认配置",
    )
]


def _parse_scalar(value: str) -> Any:
    value = value.strip()
    if not value:
        return ""

    if (value.startswith('"') and value.endswith('"')) or (
        value.startswith("'") and value.endswith("'")
    ):
        return value[1:-1]

    if value.lower() == "true":
        return True
    if value.lower() == "false":
        return False

    try:
        if "." in value:
            return float(value)
        return int(value)
    except ValueError:
        return value


def _load_simple_yaml(yaml_path: Path) -> dict[str, Any]:
    """解析当前项目使用到的简化 YAML 子集。"""
    root: dict[str, Any] = {}
    current_list: list[dict[str, Any]] | None = None
    current_item: dict[str, Any] | None = None
    current_nested_key: str | None = None

    for raw_line in yaml_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.rstrip()
        if not line:
            continue

        stripped_line = line.lstrip()
        if stripped_line.startswith("#"):
            continue

        indent = len(line) - len(stripped_line)

        if indent == 0 and stripped_line.endswith(":"):
            section_name = stripped_line[:-1].strip()
            root[section_name] = []
            current_list = root[section_name]
            current_item = None
            current_nested_key = None
            continue

        if indent == 2 and stripped_line.startswith("- "):
            if current_list is None:
                raise ValueError(f"YAML 结构错误，未找到列表段落: {yaml_path}")

            current_item = {}
            current_list.append(current_item)
            current_nested_key = None

            remainder = stripped_line[2:].strip()
            if remainder:
                key, value = remainder.split(":", 1)
                current_item[key.strip()] = _parse_scalar(value)
            continue

        if indent == 4 and current_item is not None:
            key, value = stripped_line.split(":", 1)
            key = key.strip()
            value = value.strip()

            if value:
                current_item[key] = _parse_scalar(value)
                current_nested_key = None
            else:
                current_item[key] = {}
                current_nested_key = key
            continue

        if indent >= 6 and current_item is not None and current_nested_key is not None:
            key, value = stripped_line.split(":", 1)
            current_item[current_nested_key][key.strip()] = _parse_scalar(value)
            continue

        raise ValueError(f"暂不支持的 YAML 结构: {yaml_path} -> {raw_line}")

    return root


def load_yaml_data(yaml_path: Path) -> dict[str, Any] | None:
    """读取 YAML 配置。"""
    if not yaml_path.exists():
        print(f"警告: 配置文件不存在: {yaml_path}")
        return None

    if yaml is not None:
        with yaml_path.open("r", encoding="utf-8") as file:
            data = yaml.safe_load(file)
        return data or {}

    return _load_simple_yaml(yaml_path)


def read_prompt_file(
    base_dir: Path,
    file_name: str,
    *,
    prompt_id: str,
    field_name: str,
) -> str:
    """读取 Markdown 提示词文件。"""
    file_path = base_dir / file_name
    if not file_path.exists():
        raise FileNotFoundError(
            f"提示词文件不存在: {file_path} (prompt={prompt_id}, field={field_name})"
        )

    return file_path.read_text(encoding="utf-8").rstrip("\n")


def resolve_text_field(
    entry: dict[str, Any],
    *,
    inline_key: str,
    file_key: str,
    base_dir: Path,
    prompt_id: str,
) -> str:
    """优先从文件读取文本，否则回退到 YAML 内联字段。"""
    file_name = entry.get(file_key)
    if file_name:
        return read_prompt_file(
            base_dir,
            str(file_name),
            prompt_id=prompt_id,
            field_name=file_key,
        )

    value = entry.get(inline_key, "")
    return str(value) if value is not None else ""


def load_model_configs_from_yaml(yaml_path: Path) -> list[ModelConfig]:
    """加载模型配置，缺失时使用单个默认配置。"""
    data = load_yaml_data(yaml_path)
    if data is None:
        return list(DEFAULT_MODEL_CONFIGS)

    configs: list[ModelConfig] = []
    for entry in data.get("model_configs", []):
        configs.append(
            ModelConfig(
                id=entry["id"],
                temperature=entry.get("temperature", 1.0),
                top_k=entry.get("top_k", 40),
                top_p=entry.get("top_p", 0.95),
                description=entry.get("description", ""),
            )
        )

    return configs or list(DEFAULT_MODEL_CONFIGS)


def build_parallel_user_prompts(
    entry: dict[str, Any],
    *,
    base_dir: Path,
) -> dict[str, str]:
    """兼容新旧 schema，构造并行模式的 technique -> prompt 文本映射。"""
    prompt_id = entry.get("id", "<unknown>")
    user_prompts: dict[str, str] = {}

    prompt_files = entry.get("user_prompt_files", {}) or {}
    if isinstance(prompt_files, dict):
        for technique_id, file_name in prompt_files.items():
            user_prompts[technique_id] = read_prompt_file(
                base_dir,
                str(file_name),
                prompt_id=prompt_id,
                field_name=f"user_prompt_files.{technique_id}",
            )

    inline_prompts = entry.get("user_prompts", {}) or {}
    if isinstance(inline_prompts, dict):
        for technique_id, prompt_text in inline_prompts.items():
            user_prompts.setdefault(technique_id, str(prompt_text))

    for legacy_key, technique_id in TECHNIQUE_KEY_MAP.items():
        if technique_id in user_prompts:
            continue

        legacy_file_key = f"{legacy_key}_file"
        if legacy_file_key in entry or legacy_key in entry:
            user_prompts[technique_id] = resolve_text_field(
                entry,
                inline_key=legacy_key,
                file_key=legacy_file_key,
                base_dir=base_dir,
                prompt_id=prompt_id,
            )

    return user_prompts
