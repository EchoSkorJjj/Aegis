"""Select a provider explicitly; invalid configuration fails during startup."""

from ai_service.core.config import Settings, settings
from ai_service.llm.base import LlmClient
from ai_service.llm.groq import GroqLlmClient
from ai_service.llm.stub import StubLlmClient


def get_llm_client(config: Settings | None = None) -> LlmClient:
    config = config or settings
    if config.llm_provider == "stub":
        return StubLlmClient()
    if config.llm_provider == "groq":
        return GroqLlmClient(config)
    raise ValueError("Anthropic provider is not implemented yet; use stub or groq")
