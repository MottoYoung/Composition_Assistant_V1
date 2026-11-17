"""Gemini Enterprise Agent Platform 客户端初始化辅助函数。

仅使用 Google Cloud 项目创建的标准 API Key。项目 ID 用于本地配置校验和
日志，不传给 ``genai.Client``，因为 API Key 与 project/location 互斥。
"""

from __future__ import annotations

import os
from typing import Callable

from google import genai
from google.genai import types


def _env(name: str) -> str:
    return os.environ.get(name, "").strip()


def _resolve_api_key(log: Callable[[str], None]) -> str:
    api_key = _env("GOOGLE_API_KEY")
    if api_key:
        return api_key

    legacy_key = _env("GEMINI_API_KEY")
    if legacy_key:
        log(
            "⚠️ GEMINI_API_KEY 已弃用；请改用 GOOGLE_API_KEY。"
            "本次仍通过 Enterprise Agent Platform v1 通道请求，不会回退到 AI Studio。"
        )
        return legacy_key

    raise ValueError(
        "请设置 GOOGLE_API_KEY（在目标 Google Cloud 项目创建的标准 API Key）。"
    )


def create_enterprise_client(
    *,
    timeout_ms: int | None = None,
    log: Callable[[str], None] = print,
) -> genai.Client:
    """创建 Enterprise Agent Platform v1 客户端，不发起网络请求。"""
    project_id = _env("GOOGLE_CLOUD_PROJECT")
    if not project_id:
        raise ValueError(
            "请设置 GOOGLE_CLOUD_PROJECT，用于校验当前 API Key 的目标计费项目。"
        )

    api_key = _resolve_api_key(log)
    options: dict[str, int | str] = {"api_version": "v1"}
    if timeout_ms is not None:
        options["timeout"] = timeout_ms

    log(f"📡 使用 Gemini Enterprise Agent Platform [project={project_id}]")
    return genai.Client(
        enterprise=True,
        api_key=api_key,
        http_options=types.HttpOptions(**options),
    )
