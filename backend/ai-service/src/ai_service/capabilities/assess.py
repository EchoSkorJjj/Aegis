"""Assess a finding: signal vs noise, reachability/exposure, a suggested severity — grounded, cited.

Scaffold: returns a deterministic stub, no LLM call. Step 7 fills this in:
guardrails(input) -> retrieve grounding (RAG) -> get_llm_client().complete(prompt) -> parse + cite.
"""

from ai_service.schemas import Assessment, AssessmentRequest

# from ai_service.llm import get_llm_client  # wired in step 7


def assess(request: AssessmentRequest) -> Assessment:
    return Assessment(
        material=False,
        rationale="not implemented (scaffold)",
        suggested_severity="UNKNOWN",
        citations=[],
    )
