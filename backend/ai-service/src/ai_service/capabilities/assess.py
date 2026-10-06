"""Assess source evidence through a provider-independent, validated structured call."""

from typing import TYPE_CHECKING, Literal

from pydantic import BaseModel, ConfigDict, Field

from ai_service.capabilities._grounding import (
    CitedPassageIds,
    check_input_size,
    resolve_citations,
    validate_citations,
)
from ai_service.capabilities._guardrails import check_sources, protect_output
from ai_service.prompts import build_prompt
from ai_service.schemas import Assessment, AssessmentRequest

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator


class AssessmentOutput(BaseModel):
    """Internal model output; citation IDs never become part of the wire contract."""

    model_config = ConfigDict(extra="forbid", strict=True)

    material: bool
    rationale: str = Field(min_length=1, max_length=4000)
    suggested_severity: Literal["CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE"]
    cited_passage_ids: CitedPassageIds


async def assess(request: AssessmentRequest, engine: "StructuredGenerator") -> Assessment:
    check_input_size(request, engine.settings.llm_max_input_chars)
    check_sources(request.advisory_text, request.service_context)
    prompt = build_prompt("assess", [("A", request.advisory_text), ("S", request.service_context)])

    def validate(output: AssessmentOutput) -> None:
        validate_citations(output.cited_passage_ids, prompt.passages, required=output.material)
        protect_output(output)

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="assess",
        output_model=AssessmentOutput,
        validate=validate,
    )
    return Assessment(
        material=output.material,
        rationale=output.rationale,
        suggested_severity=output.suggested_severity,
        citations=resolve_citations(output.cited_passage_ids, prompt.passages),
    )
