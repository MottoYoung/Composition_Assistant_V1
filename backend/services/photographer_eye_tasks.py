"""
Helpers for the photographer_eye prompt set.

Stage 0 in this mode returns shooting directions instead of fixed composition
technique ids. These helpers convert those directions into runtime Stage 1
tasks while preserving the old `technique` / `technique_name` response shape.
"""
import re
from typing import Any

from config import (
    PHOTOGRAPHER_EYE_DIRECTION_CONFIGS,
    STAGE0_MAX_TECHNIQUES,
    TechniqueConfig,
)


def direction_candidates_text() -> str:
    return "\n".join(
        f'- "{direction_id}": {config.name} - {config.user_prompt}'
        for direction_id, config in PHOTOGRAPHER_EYE_DIRECTION_CONFIGS.items()
    )


def _clean_text(value: Any, *, fallback: str = "") -> str:
    text = str(value or "").strip()
    return text if text else fallback


def _normalize_direction_id(value: Any) -> str:
    text = str(value or "").strip().lower()
    text = re.sub(r"[^a-z0-9_]+", "_", text).strip("_")
    return text


def build_direction_task(raw_direction: Any, index: int) -> TechniqueConfig | None:
    if not isinstance(raw_direction, dict):
        return None

    base_id = _normalize_direction_id(raw_direction.get("id"))
    if base_id not in PHOTOGRAPHER_EYE_DIRECTION_CONFIGS:
        return None

    base_config = PHOTOGRAPHER_EYE_DIRECTION_CONFIGS[base_id]
    title = _clean_text(raw_direction.get("title"), fallback=base_config.name)
    visual_evidence = _clean_text(raw_direction.get("visual_evidence"), fallback="原图中存在可见摄影机会。")
    shooting_intent = _clean_text(raw_direction.get("shooting_intent"), fallback="基于该机会给出更有完成度的参考取景。")

    task_id = f"photographer_eye_{base_id}_{index}"
    user_prompt = f"""# 摄影眼拍摄方向
direction_id: {base_id}
direction_title: {title}

# Stage 0 观察
visual_evidence: {visual_evidence}
shooting_intent: {shooting_intent}

# 任务
请只围绕这个拍摄方向分析当前画面，判断它是否真实可达，并输出当前 App 可执行的机位动作、image_prompt 和 shot_spec。不要改换地点、不要移动真实物体、不要新增不存在的前景/光线/主体。"""

    return TechniqueConfig(id=task_id, name=title, user_prompt=user_prompt)


def build_direction_tasks(parsed_json: Any) -> list[TechniqueConfig]:
    if not isinstance(parsed_json, dict):
        return []

    raw_directions = parsed_json.get("directions")
    if not isinstance(raw_directions, list):
        return []

    tasks: list[TechniqueConfig] = []
    seen_base_ids: set[str] = set()
    for raw_direction in raw_directions:
        task = build_direction_task(raw_direction, len(tasks) + 1)
        if task is None:
            continue
        base_id = task.id.rsplit("_", 1)[0]
        if base_id in seen_base_ids:
            continue
        seen_base_ids.add(base_id)
        tasks.append(task)
        if len(tasks) >= min(4, STAGE0_MAX_TECHNIQUES):
            break
    return tasks
