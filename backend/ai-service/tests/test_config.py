"""Provider selection and startup configuration fail before accepting traffic."""

import asyncio

import pytest
from pydantic import ValidationError

from ai_service.core.config import Settings
from ai_service.llm.factory import get_llm_client
from ai_service.llm.groq import GroqLlmClient
from ai_service.llm.stub import StubLlmClient
from ai_service.main import create_app


def test_default_factory_is_offline_stub(monkeypatch):
    monkeypatch.delenv("LLM_PROVIDER", raising=False)
    assert isinstance(get_llm_client(Settings(_env_file=None)), StubLlmClient)


@pytest.mark.parametrize("key", ["", "   "])
def test_missing_groq_key_fails_at_app_construction(key):
    config = Settings(_env_file=None, llm_provider="groq", groq_api_key=key)
    with pytest.raises(ValueError, match="requires GROQ_API_KEY"):
        create_app(config)


def test_groq_factory_and_secret_redaction():
    config = Settings(_env_file=None, llm_provider="groq", groq_api_key="offline-test-credential")
    assert "offline-test-credential" not in repr(config)
    assert "offline-test-credential" not in config.model_dump_json()
    client = get_llm_client(config)
    assert isinstance(client, GroqLlmClient)
    asyncio.run(client.aclose())


def test_anthropic_is_explicitly_unimplemented():
    with pytest.raises(ValueError, match="Anthropic provider is not implemented"):
        create_app(Settings(_env_file=None, llm_provider="anthropic"))


@pytest.mark.parametrize("overrides", [
    {"llm_provider": "unknown"}, {"llm_timeout_seconds": 21}, {"llm_timeout_seconds": 0},
    {"llm_max_output_tokens": 0}, {"llm_max_input_chars": 0},
    {"llm_max_requests_per_minute": 0}, {"llm_max_tokens_per_minute": -1},
    {"llm_daily_token_budget": 0},
    {"groq_base_url": "https://user:secret@example.com"},
    {"groq_base_url": "https://example.com?key=secret"},
    {"groq_base_url": "file:///tmp/provider"},
])
def test_invalid_configuration_is_rejected(overrides):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **overrides)


def test_environment_overrides_provider_and_limits(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "groq")
    monkeypatch.setenv("GROQ_API_KEY", "offline-test-credential")
    monkeypatch.setenv("GROQ_MODEL", "test-model")
    monkeypatch.setenv("LLM_MAX_REQUESTS_PER_MINUTE", "3")
    monkeypatch.setenv("LLM_MAX_TOKENS_PER_MINUTE", "500")
    monkeypatch.setenv("LLM_DAILY_TOKEN_BUDGET", "2000")
    config = Settings(_env_file=None)
    assert config.llm_provider == "groq"
    assert config.groq_model == "test-model"
    assert config.llm_max_requests_per_minute == 3
    assert config.llm_max_tokens_per_minute == 500
    assert config.llm_daily_token_budget == 2000
