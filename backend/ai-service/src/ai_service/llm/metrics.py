"""Bounded labels only: no request ids, prompts, citation text, or credentials."""

from prometheus_client import Counter, Histogram

REQUESTS = Counter(
    "aegis_llm_requests_total", "Logical structured-generation requests",
    ["provider", "model", "capability", "template", "outcome"],
)
ATTEMPTS = Counter(
    "aegis_llm_attempts_total", "Provider attempts admitted by local cost controls",
    ["provider", "model", "capability"],
)
TOKENS = Counter(
    "aegis_llm_tokens_total", "Reported provider token usage, including invalid outputs",
    ["provider", "model", "direction"],
)
LATENCY = Histogram(
    "aegis_llm_duration_seconds", "Logical generation latency including retries",
    ["provider", "model", "capability", "template"],
    buckets=(0.1, 0.5, 1, 2, 5, 10, 15, 20),
)
