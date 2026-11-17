"""
OpenRouter Stage 2 provider.

Uses the dedicated Images API for image generation/editing models.
"""
import asyncio
import json
import urllib.error
import urllib.request
from typing import List, Optional

from config import (
    OPENROUTER_API_KEY,
    OPENROUTER_IMAGE_API_URL,
    OPENROUTER_PROXY_URL,
    OPENROUTER_STAGE2_ASPECT_RATIO,
    OPENROUTER_STAGE2_MAX_CONCURRENCY,
    OPENROUTER_STAGE2_MODEL,
    OPENROUTER_STAGE2_RESOLUTION,
    OPENROUTER_STAGE2_TIMEOUT_SECONDS,
    USE_OPENROUTER_PROXY,
)
from schemas.image import ImageRequest, ImageResult
from services.common import Stage2Timing, log_stage2_finish, now_perf
from services.prompt_adapters import adapt_prompt_for_stage2


def _read_error_body(error: urllib.error.HTTPError) -> str:
    try:
        return error.read().decode("utf-8", errors="replace")
    except Exception:
        return ""


def _image_data_url_from_response(payload: dict) -> Optional[str]:
    images = payload.get("data")
    if not isinstance(images, list) or not images:
        return None

    first = images[0]
    if not isinstance(first, dict):
        return None

    b64 = first.get("b64_json")
    if not isinstance(b64, str) or not b64:
        return None
    if b64.startswith("data:"):
        return b64

    media_type = first.get("media_type") or "image/png"
    return f"data:{media_type};base64,{b64}"


def _openrouter_proxy_enabled() -> bool:
    return USE_OPENROUTER_PROXY and bool(OPENROUTER_PROXY_URL)


def _describe_openrouter_proxy() -> str:
    if not _openrouter_proxy_enabled():
        return "<disabled>"
    return OPENROUTER_PROXY_URL


def _build_openrouter_opener() -> urllib.request.OpenerDirector:
    if not _openrouter_proxy_enabled():
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))

    return urllib.request.build_opener(
        urllib.request.ProxyHandler(
            {
                "http": OPENROUTER_PROXY_URL,
                "https": OPENROUTER_PROXY_URL,
            }
        )
    )


def probe_openrouter_proxy_connectivity(timeout_seconds: float = 6.0) -> tuple[bool, str]:
    if not _openrouter_proxy_enabled():
        return False, "OpenRouter 代理未启用"

    request = urllib.request.Request(
        OPENROUTER_IMAGE_API_URL,
        method="HEAD",
        headers={"User-Agent": "PhotoFramer Composition Assistant"},
    )
    try:
        with _build_openrouter_opener().open(request, timeout=timeout_seconds) as response:
            return (
                True,
                f"代理连通成功 [{OPENROUTER_PROXY_URL}] -> {OPENROUTER_IMAGE_API_URL} "
                f"[http={response.status}]",
            )
    except urllib.error.HTTPError as error:
        if error.code in {400, 401, 403, 404, 405}:
            return (
                True,
                f"代理连通成功 [{OPENROUTER_PROXY_URL}] -> {OPENROUTER_IMAGE_API_URL} "
                f"[http={error.code}]",
            )
        return (
            False,
            f"代理连通失败 [{OPENROUTER_PROXY_URL}] -> {OPENROUTER_IMAGE_API_URL} "
            f"[http={error.code}]",
        )
    except Exception as error:
        return (
            False,
            f"代理连通失败 [{OPENROUTER_PROXY_URL}] -> {OPENROUTER_IMAGE_API_URL} "
            f"error={type(error).__name__}: {error}",
        )


class OpenRouterStage2Provider:
    provider_name = "openrouter"
    model_name = OPENROUTER_STAGE2_MODEL

    def __init__(self):
        if not OPENROUTER_API_KEY:
            raise ValueError("请设置 OPENROUTER_API_KEY 环境变量")
        self._semaphore = asyncio.Semaphore(max(1, OPENROUTER_STAGE2_MAX_CONCURRENCY))
        self._opener = _build_openrouter_opener()
        print(
            "✅ OpenRouterStage2Provider 初始化成功 "
            f"[{self.model_name}] [local_proxy={_describe_openrouter_proxy()}]"
        )

    def _build_payload(self, request: ImageRequest) -> dict:
        prompt = adapt_prompt_for_stage2(
            provider_name=self.provider_name,
            model_name=self.model_name,
            prompt=request.image_prompt,
            has_source_image=bool(request.original_image_b64),
        )
        payload = {
            "model": self.model_name,
            "prompt": prompt,
            "n": 1,
            "resolution": OPENROUTER_STAGE2_RESOLUTION,
        }
        if OPENROUTER_STAGE2_ASPECT_RATIO:
            payload["aspect_ratio"] = OPENROUTER_STAGE2_ASPECT_RATIO
        if request.original_image_b64:
            payload["input_references"] = [
                {
                    "type": "image_url",
                    "image_url": {"url": request.original_image_b64},
                }
            ]
        return payload

    def _call_sync(self, request: ImageRequest) -> dict:
        body = json.dumps(self._build_payload(request)).encode("utf-8")
        http_request = urllib.request.Request(
            OPENROUTER_IMAGE_API_URL,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {OPENROUTER_API_KEY}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://github.com/photo-framer/composition-assistant",
                "X-Title": "PhotoFramer Composition Assistant",
            },
        )
        try:
            with self._opener.open(
                http_request,
                timeout=OPENROUTER_STAGE2_TIMEOUT_SECONDS,
            ) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            body_text = _read_error_body(error)
            raise RuntimeError(
                f"OpenRouter HTTP {error.code}: {body_text or error.reason}"
            ) from error

        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise RuntimeError("OpenRouter 返回格式不是 JSON object")
        return payload

    async def generate_single_with_timing(
        self,
        request: ImageRequest,
    ) -> tuple[ImageResult, Stage2Timing]:
        timing = Stage2Timing(technique_id=request.technique_id, start_ts=now_perf())
        result = ImageResult(technique_id=request.technique_id, success=False)
        try:
            print(
                f"  🚀 [openrouter:{request.technique_id}] stage2 request start "
                f"model={self.model_name}",
                flush=True,
            )
            async with self._semaphore:
                payload = await asyncio.to_thread(self._call_sync, request)
            result.image_base64 = _image_data_url_from_response(payload)
            result.success = result.image_base64 is not None
            if not result.success:
                result.error_message = "OpenRouter 未返回图片"
        except Exception as error:
            result.error_message = str(error)

        timing.finish_ts = now_perf()
        result.response_time_ms = timing.total_ms or 0.0
        log_stage2_finish(
            self.provider_name,
            request.technique_id,
            timing,
            success=result.success,
            error_message=result.error_message,
        )
        return result, timing

    async def generate_parallel(
        self,
        requests: List[ImageRequest],
    ) -> tuple[List[ImageResult], float]:
        start_ts = now_perf()
        payloads = await asyncio.gather(
            *[self.generate_single_with_timing(req) for req in requests],
            return_exceptions=True,
        )
        results: List[ImageResult] = []
        for payload in payloads:
            if isinstance(payload, Exception):
                continue
            result, _ = payload
            results.append(result)
        total_ms = (now_perf() - start_ts) * 1000
        print(
            f"🏁 [openrouter-stage2] total={total_ms:.0f}ms requests={len(requests)}",
            flush=True,
        )
        return results, total_ms
