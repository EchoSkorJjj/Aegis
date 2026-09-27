"""Draft text for human review, using server-resolved citations and the common LLM engine.

An upgrade gets a free-text rationale. A risk ticket and a DORA notification memo each get their own
prompt and a strict schema of named sections, because aegis-core assembles those documents itself:
every fact, priority and deadline around the sections is deterministic, never the model's.
"""

from typing import TYPE_CHECKING, Annotated

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from ai_service.capabilities._grounding import (
    CitedPassageIds,
    check_input_size,
    resolve_citations,
    validate_citations,
)
from ai_service.prompts import build_prompt
from ai_service.schemas import Draft, DraftRequest, MemoSections, TicketSections

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator


Line = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
Sentence = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=300)]
Section = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
LongSection = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1200)]


class DraftOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    content: str = Field(min_length=1, max_length=12000)
    cited_passage_ids: CitedPassageIds


class TicketOutput(BaseModel):
    """What the model must return for a risk ticket (strict)."""

    model_config = ConfigDict(extra="forbid", strict=True)

    summary: Sentence
    risk: LongSection
    impact: LongSection
    actions: Annotated[list[Line], Field(min_length=1, max_length=6)]
    cited_passage_ids: CitedPassageIds


class MemoOutput(BaseModel):
    """What the model must return for a DORA notification memo (strict)."""

    model_config = ConfigDict(extra="forbid", strict=True)

    summary: Section
    impact: Section
    measures: Section
    next_steps: Section
    cited_passage_ids: CitedPassageIds


async def draft(request: DraftRequest, engine: "StructuredGenerator") -> Draft:
    check_input_size(request, engine.settings.llm_max_input_chars)
    prompt = build_prompt("draft", [("C", request.context)], draft_type=request.draft_type)
    output_model = {"risk_ticket": TicketOutput, "memo": MemoOutput}.get(request.draft_type, DraftOutput)

    def validate(output: BaseModel) -> None:
        validate_citations(output.cited_passage_ids, prompt.passages, required=bool(prompt.passages))

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="draft",
        output_model=output_model,
        validate=validate,
    )
    citations = resolve_citations(output.cited_passage_ids, prompt.passages)
    if isinstance(output, TicketOutput):
        ticket = TicketSections(summary=output.summary, risk=output.risk, impact=output.impact,
                                actions=list(output.actions))
        return Draft(draft_type=request.draft_type, content=_ticket_text(ticket), ticket=ticket,
                     citations=citations)
    if isinstance(output, MemoOutput):
        memo = MemoSections(summary=output.summary, impact=output.impact, measures=output.measures,
                            next_steps=output.next_steps)
        return Draft(draft_type=request.draft_type, content=_memo_text(memo), memo=memo, citations=citations)
    return Draft(draft_type=request.draft_type, content=output.content, citations=citations)


def _ticket_text(ticket: TicketSections) -> str:
    actions = "\n".join(f"- {action}" for action in ticket.actions)
    return (f"{ticket.summary}\n\nRisk\n{ticket.risk}\n\nImpact\n{ticket.impact}\n\n"
            f"Recommended actions\n{actions}")


def _memo_text(memo: MemoSections) -> str:
    return (f"Summary\n{memo.summary}\n\nImpact\n{memo.impact}\n\nMeasures\n{memo.measures}\n\n"
            f"Next steps\n{memo.next_steps}")
