"""Provider-independent input limits and citation checks."""

import re
from typing import Annotated

from pydantic import BaseModel, Field, StringConstraints

from ai_service.core.errors import BadRequest

PassageId = Annotated[str, StringConstraints(min_length=2, max_length=16)]
CitedPassageIds = Annotated[list[PassageId], Field(max_length=64)]


def check_input_size(request: BaseModel, max_chars: int) -> None:
    # Count opaque identifiers and control fields too, even when they are not sent to the LLM, and every
    # string nested in a list or object (an assessment's code excerpts).
    if _size(request.model_dump()) > max_chars:
        raise BadRequest(f"Input exceeds the configured limit of {max_chars} characters")


def _size(value: object) -> int:
    if isinstance(value, str):
        return len(value)
    if isinstance(value, dict):
        return sum(_size(item) for item in value.values())
    if isinstance(value, list):
        return sum(_size(item) for item in value)
    return 0


def validate_citations(
    cited_ids: list[str], passages: dict[str, str], *, required: bool
) -> None:
    # Never include an untrusted ID or passage in the error (or a repair prompt).
    if any(passage_id not in passages for passage_id in cited_ids):
        raise ValueError("Citations must refer only to supplied passage IDs")
    if required and not cited_ids:
        raise ValueError("At least one supporting citation is required")


def resolve_citations(cited_ids: list[str], passages: dict[str, str]) -> list[str]:
    # The model can select IDs only; citation text comes from the redacted grounding input.
    # Splitting at PASSAGE_CHARS bounds each citation without silently losing source characters.
    return [passages[passage_id] for passage_id in dict.fromkeys(cited_ids)]


def written_as(name: str) -> re.Pattern:
    """Where a source writes ``name``: whole (not inside a longer name), or — for a name of several words
    (``cacheComponents``, ``app_router``) — as those words in order, in prose ("Cache Component"), in any case
    and either number."""
    whole = rf"(?<![A-Za-z0-9_$]){re.escape(name)}(?![A-Za-z0-9_$])"
    words = [word for word in re.split(r"[_.:/@$-]+|(?<=[a-z0-9])(?=[A-Z])", name) if word]
    if len(words) < 2:
        return re.compile(whole)
    spoken = r"[\s_.-]*".join(re.escape(word.removesuffix("s")) + "s?" for word in words)
    return re.compile(rf"{whole}|(?<![A-Za-z0-9]){spoken}(?![A-Za-z0-9])", re.IGNORECASE)
