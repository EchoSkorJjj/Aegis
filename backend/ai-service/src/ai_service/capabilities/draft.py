"""Draft a response (risk ticket, version-upgrade suggestion, or notification memo), each citing its
sources.

Scaffold: returns a deterministic stub, no LLM call. Step 7 fills this in:
guardrails(input) -> retrieve grounding (RAG) -> get_llm_client().complete(prompt) -> parse + cite.
"""

from ai_service.schemas import Draft, DraftRequest

# from ai_service.llm import get_llm_client  # wired in step 7


def draft(request: DraftRequest) -> Draft:
    return Draft(draft_type=request.draft_type, content="not implemented (scaffold)", citations=[])
