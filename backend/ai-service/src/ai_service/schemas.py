"""The request/response contract — the OpenAPI schema aegis-core's AI-port DTOs mirror.

Kept at the top level because it is the service's public contract, shared by the API layer and the
capabilities. Snake_case on the wire; intentionally minimal for the scaffold, grown with the feature
(BUILD_PLAN step 7).
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


class Draft(ContractModel):
    draft_type: str
    content: str
    citations: list[str] = Field(default_factory=list)
