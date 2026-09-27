"""The request/response contract — the OpenAPI schema aegis-core's AI-port DTOs mirror.

Kept at the top level because it is the service's public contract, shared by the API layer and the
capabilities. Snake_case on the wire.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AssessmentRequest(ContractModel):
    finding_id: str
    advisory_text: str
    service_context: str | None = None


class Assessment(ContractModel):
    material: bool
    rationale: str
    suggested_severity: str
    citations: list[str] = Field(default_factory=list)


class DraftRequest(ContractModel):
    finding_id: str
    draft_type: Literal["risk_ticket", "upgrade", "memo"]
    context: str | None = None


class TicketSections(ContractModel):
    """A risk ticket's narrative fields. Its priority, due date and facts are aegis-core's, not the model's."""

    summary: str
    risk: str
    impact: str
    actions: list[str]


class MemoSections(ContractModel):
    """A DORA notification memo's narrative sections; aegis-core's template supplies every fact around them."""

    summary: str
    impact: str
    measures: str
    next_steps: str


class Draft(ContractModel):
    draft_type: str
    # The whole draft as readable text, whatever its type; the typed sections below are for the types
    # whose document aegis-core assembles itself.
    content: str
    ticket: TicketSections | None = None
    memo: MemoSections | None = None
    citations: list[str] = Field(default_factory=list)
