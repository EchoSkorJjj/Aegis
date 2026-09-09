"""Selects the LLM provider from configuration. Scaffold: always the stub."""

from ai_service.core.config import settings
from ai_service.llm.base import LlmClient
from ai_service.llm.stub import StubLlmClient


def get_llm_client() -> LlmClient:
    # TODO(step 7): if settings.llm_provider == "anthropic": return AnthropicLlmClient(...)
    _ = settings.llm_provider
    return StubLlmClient()
