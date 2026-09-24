"""Draft text for human review, using server-resolved citations and the common LLM engine."""

from typing import TYPE_CHECKING

from pydantic import BaseModel, ConfigDict, Field

from ai_service.capabilities._grounding import (
    CitedPassageIds,
    check_input_size,
    resolve_citations,
    validate_citations,
)
from ai_service.prompts import build_prompt
from ai_service.schemas import Draft, DraftRequest

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator


class DraftOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    content: str = Field(min_length=1, max_length=12000)
    cited_passage_ids: CitedPassageIds


async def draft(request: DraftRequest, engine: "StructuredGenerator") -> Draft:
    check_input_size(request, engine.settings.llm_max_input_chars)
    prompt = build_prompt("draft", [("C", request.context)], draft_type=request.draft_type)

    def validate(output: DraftOutput) -> None:
        validate_citations(output.cited_passage_ids, prompt.passages, required=bool(prompt.passages))

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="draft",
        output_model=DraftOutput,
        validate=validate,
    )
    return Draft(
        draft_type=request.draft_type,
        content=output.content,
        citations=resolve_citations(output.cited_passage_ids, prompt.passages),
    )
