"""Deterministic data-boundary safeguards; no content or matches are logged.

Detection is deliberately bounded to recognisable formats. It cannot identify every name,
secret or semantic injection. System/data separation, strict schemas, no tools and human
approval remain independent protections, even when a pattern does not match.
"""

import re
import unicodedata

from pydantic import BaseModel

from ai_service.core.errors import BadRequest

REDACTED = "[REDACTED]"
_PATTERNS = [
    # Entire private keys, including an unfinished block, before splitting/truncating input.
    r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\s\S]*?(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\Z)",
    r"(?i)\b(?:https?|postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^\s/@]+:[^\s/@]+@",
    r"(?i)\b(?:bearer|basic)\s+[A-Za-z0-9+/_.=-]+",
    (r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"(?:sk|gsk)-?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|"
    r"(?:AKIA|ASIA)[A-Z0-9]{16})\b"),
    r"\beyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
    (r"(?i)\b(?:[a-z][a-z0-9_]*_)?(?:password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|"
    r"secrets?[_-]?key|secret[_-]?access[_-]?key|private[_-]?key|"
    r"access[_-]?token|refresh[_-]?token|token|authorization)\b[\"']?\s*[:=]\s*"
    r"(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|[^\s,;\"'<>]+)"),
    r"\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+\b",
    r"\b\d{3}-\d{2}-\d{4}\b",
    r"(?i)\b(?:cookie|set-cookie)\s*:\s*[^\r\n]+",
    (r"(?i)\b(?:phone|mobile|tel(?:ephone)?|ssn|national[_ -]?id|"
    r"full[_ -]?name|home[_ -]?address)\s*[:=]\s*[^\r\n;]+"),
    r"(?<![\w])\+\d{1,3}[ -](?:\d[ ()-]*){7,14}\d(?![\w])",
]
_RULES = [re.compile(pattern) for pattern in _PATTERNS]
_INJECTION = re.compile(
    r"ignore\s+(?:(?:all|the|any)\s+)?(?:previous|prior|above|system)\s+"
    r"(?:instructions|rules|prompts)|"
    r"(?:reveal|print|expose|exfiltrate)\s+(?:(?:the|your|all)\s+)?"
    r"(?:system\s+prompt|secrets|credentials|api\s*keys)|"
    r"(?:bypass|skip|disable)\s+(?:(?:the|human|all)\s+)?(?:approval|guardrails|safety)|"
    r"<\s*/?\s*(?:system|developer|assistant)\b|"
    r"\[\s*INST\s*\]|<\|(?:im_start|start_header_id|endoftext)\|>",
    re.IGNORECASE,
)
_EXECUTION_CLAIM = re.compile(
    r"\b(?:i|we|aegis)\s+(?:(?:have|has|already|successfully)\s+)*"
    r"(?:filed\s+(?:the|a)\s+(?:ticket|issue)|"
    r"(?:opened|merged)\s+(?:the|a)\s+(?:pull\s+request|pr)|"
    r"sent\s+(?:the|a)\s+(?:notification|memo))\b", re.IGNORECASE,
)


def redact(text: str | None) -> str | None:
    if text is None:
        return None
    for rule in _RULES:
        text = rule.sub(REDACTED, text)
    return text


def _normalised(text: str) -> str:
    # Remove invisible format controls and normalise full-width letters for screening.
    return "".join(
        char for char in unicodedata.normalize("NFKC", text)
        if unicodedata.category(char) != "Cf"
    )


def check_sources(*sources: str | None) -> None:
    if any(source and _INJECTION.search(_normalised(source)) for source in sources):
        # Aegis-core keeps deterministic facts and falls back without calling the provider.
        raise BadRequest("Source content contains unsupported control instructions")


def protect_output(output: BaseModel) -> None:
    """Run within the engine validation callback, so unsafe output gets its bounded retry.

    Citation IDs are control data: validate those separately without rewriting them.
    Revalidate redacted text to preserve each schema's length bounds.
    """
    data = output.model_dump()
    for field, value in data.items():
        if field == "cited_passage_ids":
            continue
        if isinstance(value, str):
            if _INJECTION.search(_normalised(value)) or _EXECUTION_CLAIM.search(value):
                raise ValueError("Generated text contains unsupported control instructions")
            data[field] = redact(value)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, str) and (
                    _INJECTION.search(_normalised(item)) or _EXECUTION_CLAIM.search(item)
                ):
                    raise ValueError("Generated text contains unsupported control instructions")
            data[field] = [redact(item) if isinstance(item, str) else item for item in value]
    validated = type(output).model_validate(data, strict=True)
    for field in type(output).model_fields:
        setattr(output, field, getattr(validated, field))
