"""Groq Chat Completions adapter. Retries and budgets belong to the shared generator."""

import math
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime

import httpx

from ai_service.core.config import Settings
from ai_service.core.errors import ServiceUnavailable
from ai_service.llm.base import InvalidProviderOutput, LlmResult, RetryableProviderError


def retry_after_seconds(value: str | None) -> float | None:
    if not value:
        return None
    try:
        seconds = float(value)
    except ValueError:
        try:
            seconds = (parsedate_to_datetime(value) - datetime.now(UTC)).total_seconds()
        except (ValueError, TypeError, OverflowError):
            return None
    return max(0, seconds) if math.isfinite(seconds) else None


class GroqLlmClient:
    provider = "groq"

    def __init__(self, settings: Settings, *, http: httpx.AsyncClient | None = None):
        if not settings.groq_api_key.get_secret_value().strip():
            raise ValueError("LLM_PROVIDER=groq requires GROQ_API_KEY")
        self.model = settings.groq_model
        self._settings = settings
        self._http = http or httpx.AsyncClient(
            timeout=settings.llm_timeout_seconds, follow_redirects=False, trust_env=False,
        )

    async def generate_json(
        self, *, system: str, user: str, schema_name: str,
        json_schema: dict, max_output_tokens: int,
    ) -> LlmResult:
        try:
            response = await self._http.post(
                f"{self._settings.groq_base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self._settings.groq_api_key.get_secret_value()}"},
                json={
                    "model": self.model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "response_format": {"type": "json_schema", "json_schema": {
                        "name": schema_name, "strict": True, "schema": json_schema,
                    }},
                    "max_completion_tokens": max_output_tokens,
                    "temperature": 0.1,
                    "reasoning_effort": "low",
                    "include_reasoning": False,
                    "stream": False,
                },
            )
        except httpx.HTTPError:
            raise ServiceUnavailable("LLM provider connection failed or timed out") from None
        if response.status_code == 429 or 500 <= response.status_code <= 599:
            raise RetryableProviderError(
                retry_after_seconds(response.headers.get("retry-after")),
                rate_limited=response.status_code == 429,
            )
        if not response.is_success:
            # Includes redirects: never relay an Authorization header to another location.
            raise ServiceUnavailable("LLM provider rejected the request; check provider configuration")
        try:
            data = response.json()
            usage = data["usage"]
            input_tokens, output_tokens = usage["prompt_tokens"], usage["completion_tokens"]
            if any(type(n) is not int or n < 0 for n in (input_tokens, output_tokens)):
                raise ValueError
            choice = data["choices"][0]
            content = choice["message"].get("content")
            # Refusal/truncation still consumes usage; validate empty text in the shared layer.
            if choice.get("finish_reason") != "stop" or not isinstance(content, str):
                content = ""
            return LlmResult(content, self.provider, self.model, input_tokens, output_tokens)
        except (ValueError, KeyError, TypeError, IndexError, AttributeError):
            raise InvalidProviderOutput("LLM provider returned an invalid response") from None

    async def aclose(self) -> None:
        await self._http.aclose()
