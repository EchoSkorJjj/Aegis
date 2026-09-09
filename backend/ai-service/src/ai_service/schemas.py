"""The request/response contract — the OpenAPI schema aegis-core's AI-port DTOs mirror.

Kept at the top level because it is the service's public contract, shared by the API layer and the
capabilities. Snake_case on the wire; intentionally minimal for the scaffold, grown with the feature
(BUILD_PLAN step 7).
"""

from pydantic import BaseModel


class AssessmentRequest(BaseModel):
    finding_id: str
    advisory_text: str
    service_context: str | None = None


class Assessment(BaseModel):
    material: bool
    rationale: str
    suggested_severity: str
    citations: list[str] = []


class DraftRequest(BaseModel):
    finding_id: str
    draft_type: str  # risk_ticket | upgrade | memo
    context: str | None = None


class Draft(BaseModel):
    draft_type: str
    content: str
    citations: list[str] = []
