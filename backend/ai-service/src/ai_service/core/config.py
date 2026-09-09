"""Environment-driven configuration."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    server_port: int = 8083
    service_name: str = "ai-service"

    # Shared secret that aegis-core presents (header X-Service-Token). The ai-service is internal and
    # user-agnostic; it trusts its caller via this token, not a user JWT. Change in real deployments.
    service_token: str = "dev-internal-token-change-me"

    # LLM provider (not called in the scaffold — a StubLlmClient stands in). "stub" | "anthropic".
    llm_provider: str = "stub"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5"

    # Observability
    tracing_enabled: bool = False
    zipkin_endpoint: str = "http://localhost:9411/api/v2/spans"


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
