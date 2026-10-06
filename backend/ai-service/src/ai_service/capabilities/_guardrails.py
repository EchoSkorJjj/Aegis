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
# Each rule matches the shape of a credential or contact detail, not the words around one:
# advisories name packages as lodash@4.17.21 and describe "Basic authentication" or "Bearer
# tokens", and that text must reach the model intact. Each rule redacts the value only, never
# the rest of the line.
_SECRET_KEY = (r"(?:[a-z][a-z0-9_]*_)?(?:password|passwd|pwd|secret|client[_-]?secret|"
               r"api[_-]?key|secrets?[_-]?key|secret[_-]?access[_-]?key|private[_-]?key|"
               r"access[_-]?token|refresh[_-]?token|token|authorization)")
_PATTERNS = [
    # Entire private keys, including an unfinished block, before splitting/truncating input.
    r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\s\S]*?(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\Z)",
    r"(?i)\b(?:https?|postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^\s/@]+:[^\s/@]+@",
    # Base64 credentials: a lowercase English word ("Basic authentication") has no digit, symbol or
    # capital after its first letter.
    (r"\b(?i:basic)\s+(?=[A-Za-z0-9+/]{8,}={0,2}(?![A-Za-z0-9+/=]))"
     r"[A-Za-z0-9+/][A-Za-z0-9+/]*?[0-9A-Z+/][A-Za-z0-9+/]*={0,2}"),
    (r"\b(?i:bearer)\s+(?=[A-Za-z0-9._~+/-]{16,}=*(?![A-Za-z0-9._~+/=-]))"
     r"(?=[A-Za-z0-9._~+/-]*[0-9._~+/-])[A-Za-z0-9._~+/-]+=*"),
    # Provider keys are random, so they contain a digit; "skip_…" or "sk-payments-…" doesn't.
    (r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
     r"(?:sk|gsk)[-_](?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|"
     r"(?:AKIA|ASIA)[A-Z0-9]{16})\b"),
    r"\beyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
    # An assignment always holds a value; after a colon, prose ("CSRF token: the server…") must
    # not match, so the value has to be quoted, contain a digit or symbol, or be long.
    rf"(?i)\b{_SECRET_KEY}\b[\"']?\s*=\s*(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|[^\s,;\"'<>]+)",
    (rf"(?i)\b{_SECRET_KEY}\b[\"']?\s*:\s*(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|"
     r"(?=[^\s,;\"'<>]*[0-9_+/=@#$%^&*!?~-])[^\s,;\"'<>]+|[^\s,;\"'<>]{16,})"),
    # An email's domain ends in a letter TLD and never starts like a version (lodash@4.17.21).
    (r"\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?![0-9]+\.[0-9]+)"
     r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b"),
    r"\b\d{3}-\d{2}-\d{4}\b",
    r"(?i)\b(?:cookie|set-cookie)\s*:\s*[^\s=;,]+=[^\r\n]*",
    r"(?i)\b(?:phone|mobile|tel(?:ephone)?)\s*[:=]\s*\+?[0-9(][0-9 ().-]{5,}[0-9]",
    r"(?i)\b(?:ssn|national[_ -]?id)\s*[:=]\s*[A-Za-z0-9][A-Za-z0-9-]*(?: [0-9][A-Za-z0-9-]*)*",
    r"(?i)\bfull[_ -]?name\s*[:=]\s*[^\r\n;,.]+",
    r"(?i)\bhome[_ -]?address\s*[:=]\s*[^\r\n;]+",
    r"(?<![\w])\+\d{1,3}[ -](?:\d[ ()-]*){7,14}\d(?![\w])",
]
_RULES = [re.compile(pattern) for pattern in _PATTERNS]
# An instruction to the model is imperative: it starts a sentence or line, or it addresses the model
# ("your"). Advisories describe what an attacker can do ("allows an attacker to expose credentials",
# "lets members bypass the approval rules"), and that wording must still reach the drafts.
_STARTS = r"(?:\A|(?<=[.!?;:\n>]))\s*"
_INJECTION = re.compile(
    r"ignore\s+(?:(?:all|the|any)\s+)?(?:previous|prior|above|system)\s+"
    r"(?:instructions|rules|prompts)"
    r"|(?:reveal|print|expose|exfiltrate|repeat|output)\s+(?:(?:the|your)\s+)?system\s+prompt"
    rf"|{_STARTS}(?:reveal|print|expose|exfiltrate)\s+(?:(?:the|all)\s+)?"
    r"(?:secrets|credentials|api\s*keys)\b"
    r"|(?:reveal|print|expose|exfiltrate)\s+your\s+(?:secrets|credentials|api\s*keys)"
    rf"|{_STARTS}(?:bypass|skip|disable)\s+(?:(?:the|all)\s+)?"
    r"(?:human\s+(?:approval|review)|approval|guardrails|safety)\b"
    r"|(?:bypass|skip|disable|ignore)\s+your\s+(?:approval|guardrails|safety|rules|instructions)"
    r"|<\s*/?\s*(?:system|developer|assistant)\b"
    r"|\[\s*INST\s*\]|<\|(?:im_start|start_header_id|endoftext)\|>",
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
