"""
三阶段后端配置。

目标：
1. Stage 0（软预筛）、Stage 1（分析）与 Stage 2（生图）可独立切换 provider / model
2. 默认使用 Gemini 负责分析，OpenRouter 负责 Stage 2 生图
3. 同时支持 Gemini、Qwen、OpenRouter 作为可组合 provider 接入
"""

import os
from contextvars import ContextVar
from dataclasses import dataclass
from pathlib import Path


def _api_key(name: str) -> str:
    return os.environ.get(name, "").strip()


# ==================== Provider（qwen/gemini/openrouter）选择 ==================== #
STAGE1_PROVIDER = "gemini"
STAGE2_PROVIDER = "gemini"


# STAGE1_PROVIDER = "qwen"
# STAGE2_PROVIDER = "qwen"
# STAGE2_PROVIDER = "openrouter"

# Stage 0 是预筛阶段，默认跟随 Stage 1，也可以单独指定为 "gemini" 或 "qwen"。
STAGE0_PROVIDER = STAGE1_PROVIDER


# ==================== API Keys ==================== #
DASHSCOPE_API_KEY = _api_key("DASHSCOPE_API_KEY")
GEMINI_API_KEY = _api_key("GEMINI_API_KEY")
GEMINI_DOMESTIC_API_KEY = _api_key("GEMINI_DOMESTIC_API_KEY")
OPENROUTER_API_KEY = _api_key("OPENROUTER_API_KEY")


# ==================== 网络 / 代理配置 ==================== #
# Qwen 国内访问配置
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
IMAGE_DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/api/v1"

# 本地代理默认走 127.0.0.1:11087；如果你的代理是 socks，请改成 socks5://127.0.0.1:11087
USE_GEMINI_PROXY = True
GEMINI_PROXY_URL = "http://127.0.0.1:11087"

# OpenRouter 默认复用本地代理；如果你的代理是 socks，请先确认 urllib 运行环境支持该协议。
USE_OPENROUTER_PROXY = True
OPENROUTER_PROXY_URL = os.environ.get("OPENROUTER_PROXY_URL", GEMINI_PROXY_URL).strip()
# Gemini 国内中转配置：
# - 关闭时：继续使用官方 Gemini API（GEMINI_API_KEY）
# - 开启时：使用兼容 Gemini REST 路径的国内中转
# - BASE URL 填服务根地址或 API 根地址，不要填完整 generateContent 路径

USE_GEMINI_DOMESTIC_API = False
GEMINI_DOMESTIC_BASE_URL = "https://yinli.one"
GEMINI_DOMESTIC_API_VERSION = "v1beta"


# ==================== 模型配置 ==================== #
## Qwen
QWEN_STAGE1_MODEL = "qwen3.7-plus"
QWEN_STAGE2_MODEL = "qwen-image-2.0"
QWEN_STAGE0_MODEL = QWEN_STAGE1_MODEL

## Gemini

GEMINI_STAGE1_MODEL = "gemini-3.5-flash"
GEMINI_STAGE2_MODEL = "gemini-2.5-flash-image"
GEMINI_STAGE0_MODEL = "gemini-3.5-flash"

## OpenRouter-gemini

OPENROUTER_STAGE2_MODEL = "google/gemini-3.1-flash-lite-image"


## 获取模型名称
def get_stage0_model_name() -> str:
    return GEMINI_STAGE0_MODEL if STAGE0_PROVIDER == "gemini" else QWEN_STAGE0_MODEL


def get_stage1_model_name() -> str:
    return GEMINI_STAGE1_MODEL if STAGE1_PROVIDER == "gemini" else QWEN_STAGE1_MODEL


def get_stage2_model_name() -> str:
    if STAGE2_PROVIDER == "gemini":
        return GEMINI_STAGE2_MODEL
    if STAGE2_PROVIDER == "openrouter":
        return OPENROUTER_STAGE2_MODEL
    return QWEN_STAGE2_MODEL


# ==================== 路径配置 ==================== #
BASE_DIR = Path(__file__).resolve().parent
TEMP_DIR = BASE_DIR / "temp"
PROMPTS_DIR = BASE_DIR / "prompts"
#PROMPT_SET = "default"
#PROMPT_SET = "self_written"
PROMPT_SET = "photographer_eye"
PHOTOGRAPHER_EYE_DIRECTION_MODE = PROMPT_SET == "photographer_eye"

TEMP_DIR.mkdir(exist_ok=True)


def load_prompt_file(filename: str) -> str:
    return (PROMPTS_DIR / filename).read_text(encoding="utf-8").strip()


# ==================== Prompt / 构图技术配置 ==================== #
@dataclass
class TechniqueConfig:
    id: str
    name: str
    user_prompt: str


STAGE1_SYSTEM_PROMPT_PATH = f"{PROMPT_SET}/stage1_system_instruction.md"
STAGE0_SOFT_PREFILTER_PROMPT_PATH = f"{PROMPT_SET}/stage0_soft_prefilter.md"
TECHNIQUE_PROMPT_DIR = f"{PROMPT_SET}/techniques"

SYSTEM_INSTRUCTION = load_prompt_file(STAGE1_SYSTEM_PROMPT_PATH)
STAGE0_SOFT_PREFILTER_PROMPT_TEMPLATE = load_prompt_file(STAGE0_SOFT_PREFILTER_PROMPT_PATH)

_TECHNIQUE_DEFINITIONS = (
    ("rule_of_thirds", "三分构图"),
    ("center_composition", "中心构图"),
    ("leading_lines", "引导线构图"),
    ("foreground_framing", "前景/框架构图"),
    ("diagonal_composition", "对角线构图"),
)

TECHNIQUE_CONFIGS: dict[str, TechniqueConfig] = {
    technique_id: TechniqueConfig(
        id=technique_id,
        name=technique_name,
        user_prompt=load_prompt_file(f"{TECHNIQUE_PROMPT_DIR}/{technique_id}.md"),
    )
    for technique_id, technique_name in _TECHNIQUE_DEFINITIONS
}

_PHOTOGRAPHER_EYE_DIRECTION_DEFINITIONS = (
    ("light_shadow", "光影机会", "逆光、侧光、窗光、投影、光斑、剪影、灯点或明暗分区。"),
    ("reflection", "反射机会", "水面、玻璃、金属、雨后地面、橱窗或车窗中的真实反射。"),
    ("foreground_frame", "前景框景", "门洞、窗框、栏杆、树枝、屋檐、车窗、桌边或任何真实前景开口。"),
    ("spatial_depth", "空间层次", "前景、中景、远景、街道纵深、道路、走廊、岸线或空间尺度关系。"),
    ("subject_isolation", "主体提取", "人、动物、花、灯、杯子、远处建筑等小而明确的主体。"),
    ("geometry_order", "几何秩序", "重复线条、对称、网格、窗格、楼梯节奏、建筑轴线或稳定图形。"),
    ("perspective_shift", "视角重构", "低机位、高机位、绕拍、靠近或后退后会明显变好的视角。"),
    ("texture_detail", "材质局部", "玻璃、金属、水面、墙面、叶脉、食物质感、毛发、布料或器物表面。"),
    ("moment_narrative", "瞬间叙事", "人物、动物、车辆、手势、姿态、关系或等待后更成立的瞬间。"),
)

PHOTOGRAPHER_EYE_DIRECTION_CONFIGS: dict[str, TechniqueConfig] = {
    direction_id: TechniqueConfig(
        id=direction_id,
        name=direction_name,
        user_prompt=direction_desc,
    )
    for direction_id, direction_name, direction_desc in _PHOTOGRAPHER_EYE_DIRECTION_DEFINITIONS
}

_RUNTIME_STAGE1_TASK_CONFIGS: ContextVar[dict[str, TechniqueConfig]] = ContextVar(
    "runtime_stage1_task_configs",
    default={},
)


def set_runtime_stage1_task_configs(task_configs: dict[str, TechniqueConfig]):
    return _RUNTIME_STAGE1_TASK_CONFIGS.set(task_configs)


def reset_runtime_stage1_task_configs(token) -> None:
    _RUNTIME_STAGE1_TASK_CONFIGS.reset(token)


def get_stage1_task_config(task_id: str) -> TechniqueConfig:
    runtime_configs = _RUNTIME_STAGE1_TASK_CONFIGS.get()
    if task_id in runtime_configs:
        return runtime_configs[task_id]
    return TECHNIQUE_CONFIGS[task_id]


# ==================== Stage 0 配置 ==================== #
ENABLE_STAGE0 = True
STAGE0_MAX_TECHNIQUES = 5
STAGE0_TEMPERATURE = 0.1
STAGE0_TOP_P = 0.9


# ==================== Stage 1 通用配置 ==================== #
MODEL_TEMPERATURE = 0.35
MODEL_TOP_P = 0.80
MODEL_TOP_K = 40
MODEL_MAX_TOKENS = 1024

ENABLE_THINKING = False
MODEL_THINKING_BUDGET = 0

# Stage 1 思考控制：
# - Gemini 2.5 Flash / Flash Lite：显式设为 thinking_budget=0，避免默认 dynamic thinking 带来额外时延
# - Gemini 3 Flash：显式设为 thinking_level=minimal，避免默认 high dynamic thinking
# - Gemini 2.5 Pro：无法完全关闭，只能压到最低预算
# - Qwen：默认显式关闭 enable_thinking
QWEN_STAGE1_ENABLE_THINKING = False
QWEN_STAGE1_THINKING_BUDGET = 0
QWEN_STAGE0_ENABLE_THINKING = False
QWEN_STAGE0_THINKING_BUDGET = 0

GEMINI_STAGE1_FORCE_MINIMAL_THINKING = True
GEMINI_STAGE1_THINKING_LEVEL = "minimal"
GEMINI_STAGE1_THINKING_BUDGET = 0

# Gemini Stage 1 专用参数（会按模型支持情况有选择地下发）
GEMINI_STAGE1_TEMPERATURE = MODEL_TEMPERATURE
GEMINI_STAGE1_TOP_P = MODEL_TOP_P
GEMINI_STAGE1_TOP_K = MODEL_TOP_K
GEMINI_STAGE1_MAX_OUTPUT_TOKENS = MODEL_MAX_TOKENS
GEMINI_STAGE1_RESPONSE_MIME_TYPE = "application/json"
GEMINI_STAGE1_FORCE_DISABLE_MAX_OUTPUT_TOKENS = True

STAGE1_MAX_CONCURRENCY = 5
STAGE0_TIMEOUT_SECONDS = 12.0
STAGE1_TIMEOUT_SECONDS = 20.0
STAGE2_TIMEOUT_SECONDS = 40.0


# ==================== Stage 2 配置 ==================== #
IMAGE_MAX_RATE = 2.0

# Gemini Stage 2 专用参数（同样按模型支持情况有选择地下发）
GEMINI_STAGE2_TEMPERATURE = 1.0
GEMINI_STAGE2_TOP_P = MODEL_TOP_P
GEMINI_STAGE2_TOP_K = MODEL_TOP_K
GEMINI_STAGE2_MAX_OUTPUT_TOKENS = 1024
# 注意：
# - 2.5-flash-image 默认固定 1024px，不下发 image_size
# - 3.x image preview / 2.0 image-generation 才会按模型能力下发 image_size
GEMINI_STAGE2_IMAGE_SIZE = "1K"
GEMINI_STAGE2_INCLUDE_SOURCE_IMAGE = True
GEMINI_STAGE2_FORCE_DISABLE_MAX_OUTPUT_TOKENS = True
GEMINI_STAGE2_MAX_CONCURRENCY = 2
GEMINI_STAGE2_MAX_RETRIES = 2
GEMINI_STAGE2_RETRY_BASE_DELAY_MS = 450

# OpenRouter Stage 2 专用参数。
# google/gemini-3.1-flash-lite-image 当前只支持 1K、n=1、非流式输出。
OPENROUTER_IMAGE_API_URL = "https://openrouter.ai/api/v1/images"
OPENROUTER_STAGE2_RESOLUTION = os.environ.get("OPENROUTER_STAGE2_RESOLUTION", "1K").strip() or "1K"
OPENROUTER_STAGE2_ASPECT_RATIO = os.environ.get("OPENROUTER_STAGE2_ASPECT_RATIO", "").strip()
OPENROUTER_STAGE2_MAX_CONCURRENCY = 2
OPENROUTER_STAGE2_TIMEOUT_SECONDS = 20.0
