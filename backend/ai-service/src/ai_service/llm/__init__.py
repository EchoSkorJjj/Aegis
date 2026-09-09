"""LLM provider abstraction. Callers use ``get_llm_client()`` and depend only on the ``LlmClient``
protocol, so the provider (stub now; Anthropic later) can change without touching the capabilities."""

from ai_service.llm.base import LlmClient
from ai_service.llm.factory import get_llm_client

__all__ = ["LlmClient", "get_llm_client"]
