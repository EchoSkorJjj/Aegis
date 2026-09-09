"""The provider port. Structural (Protocol), so implementations need not inherit it."""

from typing import Protocol


class LlmClient(Protocol):
    def complete(self, prompt: str) -> str: ...
