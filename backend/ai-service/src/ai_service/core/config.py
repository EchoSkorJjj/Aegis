"""Environment-driven configuration."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    server_port: int = 8083
    service_name: str = "ai-service"

    # Shared secret that aegis-core presents (header X-Service-Token). The ai-service is internal and
    # user-agnostic; it trusts its caller via this token, not a user JWT. Change in real deployments.
    service_token: str = "dev-internal-token-change-me"

    llm_provider: Literal["stub", "groq", "anthropic"] = "stub"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5"
    groq_api_key: SecretStr = SecretStr("")
    groq_model: str = Field(default="openai/gpt-oss-120b", min_length=1)
    groq_base_url: str = "https://api.groq.com/openai/v1"
    # One wall-clock deadline, including retries and backoff; core waits 30s.
    llm_timeout_seconds: float = Field(default=20, gt=0, le=20)
    llm_max_output_tokens: int = Field(default=1000, gt=0)
    llm_max_input_chars: int = Field(default=16000, gt=0)
    llm_max_requests_per_minute: int = Field(default=25, gt=0)
    llm_max_tokens_per_minute: int = Field(default=7000, gt=0)
    llm_daily_token_budget: int = Field(default=150000, gt=0)

    @field_validator("groq_base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        from urllib.parse import urlsplit

        parsed = urlsplit(value)
        if (parsed.scheme not in {"https", "http"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("GROQ_BASE_URL must be HTTP(S) without credentials, query or fragment")
        return value.rstrip("/")

    # Observability
    tracing_enabled: bool = False
    zipkin_endpoint: str = "http://localhost:9411/api/v2/spans"


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
