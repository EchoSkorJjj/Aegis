"""Provider-independent input limits and citation checks."""

from typing import Annotated

from pydantic import BaseModel, Field, StringConstraints

from ai_service.core.errors import BadRequest

PassageId = Annotated[str, StringConstraints(min_length=2, max_length=16)]
CitedPassageIds = Annotated[list[PassageId], Field(max_length=64)]


def check_input_size(request: BaseModel, max_chars: int) -> None:
    # Count opaque identifiers and control fields too, even when they are not sent to the LLM.
    size = sum(len(value) for value in request.model_dump().values() if isinstance(value, str))
    if size > max_chars:
        raise BadRequest(f"Input exceeds the configured limit of {max_chars} characters")


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
