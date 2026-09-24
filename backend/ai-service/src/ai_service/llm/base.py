"""Provider-independent structured generation port. One call is one HTTP attempt."""

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class LlmResult:
    text: str
    provider: str
    model: str
    input_tokens: int
    output_tokens: int


class RetryableProviderError(Exception):
    """Safe metadata only; never retain provider bodies, prompts, or credentials."""

    def __init__(self, retry_after: float | None = None):
        super().__init__("LLM provider temporarily unavailable")
        self.retry_after = retry_after


class InvalidProviderOutput(Exception):
    """The provider response envelope could not be decoded."""


class LlmClient(Protocol):
    provider: str
    model: str

    async def generate_json(
        self, *, system: str, user: str, schema_name: str,
        json_schema: dict, max_output_tokens: int,
    ) -> LlmResult: ...

    async def aclose(self) -> None: ...
