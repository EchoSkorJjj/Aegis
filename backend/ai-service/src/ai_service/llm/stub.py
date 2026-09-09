"""Deterministic stand-in used until the real provider lands (BUILD_PLAN step 7). Never hits a
network, so tests run with no API key. A real provider (e.g. an ``anthropic.py`` in this package)
implements the same ``LlmClient`` protocol."""


class StubLlmClient:
    def complete(self, prompt: str) -> str:
        return "[stub] LLM not implemented yet"
