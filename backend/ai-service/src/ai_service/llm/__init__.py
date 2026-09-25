"""LLM provider abstraction. Callers use ``get_llm_client()`` and depend only on the ``LlmClient``
protocol, so stub/Groq (and future providers) can change without touching capabilities."""

from ai_service.llm.base import LlmClient
from ai_service.llm.factory import get_llm_client

__all__ = ["LlmClient", "get_llm_client"]
