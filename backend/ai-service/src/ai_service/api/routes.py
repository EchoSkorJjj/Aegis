"""Endpoints: operational health (public) and the two AI capabilities (service-token protected).

Handlers stay thin — they delegate to the `capabilities` package, which is where prompts, RAG, and
guardrails will live (BUILD_PLAN step 7).
"""

from typing import Annotated

from fastapi import APIRouter, Depends, Request

from ai_service.capabilities.assess import assess as run_assess
from ai_service.capabilities.draft import draft as run_draft
from ai_service.core.security import require_service_token
from ai_service.llm.structured import StructuredGenerator
from ai_service.schemas import Assessment, AssessmentRequest, Draft, DraftRequest

router = APIRouter()


def generator(request: Request) -> StructuredGenerator:
    return request.app.state.generator


@router.get("/actuator/health", tags=["ops"], summary="Liveness")
def health() -> dict:
    return {"status": "UP"}


@router.get(
    "/v1/ping",
    tags=["ops"],
    summary="Service-token connectivity check",
    dependencies=[Depends(require_service_token)],
)
def ping() -> dict:
    # Protected by the service token, so only a token holder (aegis-core) can reach it — the
    # app-level half of "only aegis-core talks to ai-service" (network isolation is the other half).
    return {"service": "ai-service", "status": "ok"}


@router.post(
    "/v1/assess",
    response_model=Assessment,
    tags=["ai"],
    summary="Assess a finding",
    dependencies=[Depends(require_service_token)],
)
async def assess(
    request: AssessmentRequest, engine: Annotated[StructuredGenerator, Depends(generator)],
) -> Assessment:
    return await run_assess(request, engine)


@router.post(
    "/v1/draft",
    response_model=Draft,
    tags=["ai"],
    summary="Draft a response",
    dependencies=[Depends(require_service_token)],
)
async def draft(
    request: DraftRequest, engine: Annotated[StructuredGenerator, Depends(generator)],
) -> Draft:
    return await run_draft(request, engine)
