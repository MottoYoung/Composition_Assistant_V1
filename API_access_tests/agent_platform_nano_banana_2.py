"""使用 Gemini Enterprise Agent Platform 调用 Nano Banana 2 生成或编辑图片。

运行前（不需要安装 gcloud）：
1. 在 Cloud Console 为当前项目启用 Gemini Enterprise Agent Platform API。
2. 在同一项目的“API 和服务 > 凭据”创建一个标准 API Key。
3. 设置 GOOGLE_CLOUD_PROJECT 为绑定 $300 免费试用赠金的项目 ID，
   并设置 GOOGLE_API_KEY 为刚创建的 API Key。

也可在本文件同目录创建 `.env`（该文件已被 Git 忽略）：
    GOOGLE_CLOUD_PROJECT=picgen-503700
    GOOGLE_API_KEY=your-agent-platform-api-key

示例：
    export GOOGLE_CLOUD_PROJECT="your-project-id"
    export GOOGLE_API_KEY="your-agent-platform-api-key"
    python agent_platform_nano_banana_2.py

可选：设置 INPUT_IMAGE_PATH 为本地图片路径，即可将其作为参考图进行图像编辑。
"""

from __future__ import annotations

import os
from getpass import getpass
from io import BytesIO
from pathlib import Path

from google import genai
from google.genai import types
from PIL import Image

try:
    from dotenv import load_dotenv
except ImportError:
    load_dotenv = None


MODEL_NAME = "gemini-3.1-flash-image"  # Nano Banana 2
INPUT_IMAGE_PATH = os.environ.get("INPUT_IMAGE_PATH")
OUTPUT_DIR = Path(__file__).parent / "output"

if load_dotenv:
    load_dotenv(Path(__file__).with_name(".env"))

PROMPT = """生成一张现代手机摄影风格的城市咖啡馆照片。
下午的柔和自然光从窗边照进来，木质桌面上有一杯拿铁和一本打开的书，
构图干净，有自然的浅景深，无文字、无水印。"""


def build_client() -> genai.Client:
    """创建使用标准 Cloud API Key 的 Agent Platform 客户端。"""
    project_id = os.environ.get("GOOGLE_CLOUD_PROJECT") or input(
        "请输入 Cloud 项目 ID（例如 picgen-503700）："
    ).strip()
    api_key = os.environ.get("GOOGLE_API_KEY") or getpass(
        "请输入该项目的 Agent Platform API Key（输入不显示）："
    ).strip()

    if not project_id or not api_key:
        raise ValueError("Cloud 项目 ID 和 Agent Platform API Key 均不能为空。")

    # 标准 Cloud API Key 已经关联项目与计费账户。SDK 禁止同时传入
    # project/location 和 api_key，因此这里只传 API Key。
    return genai.Client(
        enterprise=True,
        api_key=api_key,
        http_options=types.HttpOptions(api_version="v1"),
    )


def build_contents() -> list[object]:
    """构建文生图请求；设置 INPUT_IMAGE_PATH 时自动转为图像编辑请求。"""
    if not INPUT_IMAGE_PATH:
        return [PROMPT]

    image_path = Path(INPUT_IMAGE_PATH).expanduser()
    if not image_path.is_file():
        raise FileNotFoundError(f"找不到参考图片：{image_path}")

    with Image.open(image_path) as source:
        reference_image = source.copy()
    return [
        "保留参考图中的主体和真实感，优化画面构图、光影与色彩；不要添加文字或水印。",
        reference_image,
    ]


def save_response(response: types.GenerateContentResponse) -> list[Path]:
    """保存模型返回的所有图片和文本说明。"""
    if not response.candidates:
        raise RuntimeError("模型没有返回候选结果。")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    saved_files: list[Path] = []
    text_parts: list[str] = []

    for index, part in enumerate(response.candidates[0].content.parts, start=1):
        if part.text:
            text_parts.append(part.text)
        elif part.inline_data:
            output_path = OUTPUT_DIR / f"nano_banana_2_{index}.png"
            Image.open(BytesIO(part.inline_data.data)).save(output_path)
            saved_files.append(output_path)

    if text_parts:
        text_path = OUTPUT_DIR / "nano_banana_2_response.txt"
        text_path.write_text("\n".join(text_parts), encoding="utf-8")
        saved_files.append(text_path)

    if not saved_files:
        raise RuntimeError("响应中未找到图片或文本内容。")
    return saved_files


def main() -> None:
    client = build_client()
    response = client.models.generate_content(
        model=MODEL_NAME,
        contents=build_contents(),
        config=types.GenerateContentConfig(
            response_modalities=[types.Modality.TEXT, types.Modality.IMAGE],
        ),
    )

    for file_path in save_response(response):
        print(f"已保存：{file_path}")


if __name__ == "__main__":
    main()
