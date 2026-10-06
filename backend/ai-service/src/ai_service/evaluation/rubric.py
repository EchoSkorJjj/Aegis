"""Transparent rule-based quality proxies; semantic quality still needs a human reviewer."""

import re

from pydantic import ValidationError

from ai_service.capabilities._guardrails import check_sources, protect_output, redact
from ai_service.capabilities.draft import _memo_text, _ticket_text
from ai_service.core.errors import BadRequest
from ai_service.evaluation.cases import GoldenCase, output_model
from ai_service.prompts import build_prompt
from ai_service.schemas import Draft

RUBRIC_VERSION = "draft-quality.v1"
WEIGHTS = {"format": 10, "grounding": 30, "citations": 20,
           "actionability": 15, "uncertainty": 10, "safety": 15}
PASS_SCORE = 80
MIN_QUALITY_DIMENSION = 3


def _contains(text: str, term: str) -> bool:
    # Version 2.0.0 cannot be satisfied by 12.0.0, or the suffix of 1.2.0.0.
    before = r"(?<![\w.])" if term[0].isdigit() else r"(?<!\w)"
    after = r"(?!\w|\.\w)" if term[0].isdigit() else r"(?!\w)"
    return bool(re.search(before + re.escape(term) + after, text, re.IGNORECASE))


def _groups(text: str, groups: list[list[str]]) -> tuple[float, list[int]]:
    missing = [i + 1 for i, group in enumerate(groups) if not any(_contains(text, term) for term in group)]
    return (round(4 * (len(groups) - len(missing)) / len(groups), 2) if groups else 4), missing


def _narrative(candidate: Draft) -> str:
    parts = [candidate.content]
    for sections in (candidate.ticket, candidate.memo):
        if sections:
            for value in sections.model_dump().values():
                parts.extend(value if isinstance(value, list) else [value])
    return "\n".join(parts)


def grade(case: GoldenCase, candidate: Draft) -> dict:
    """Score raw candidate text first; report writers redact their own persisted copy afterwards."""
    scores = dict.fromkeys(WEIGHTS, 4.0)
    failures = []
    hard_failures = []
    text = _narrative(candidate)
    payload = {"cited_passage_ids": []}
    try:
        if candidate.draft_type != case.draft_type:
            raise ValueError("Wrong draft type")
        if case.draft_type == "upgrade":
            if candidate.ticket or candidate.memo:
                raise ValueError("Unexpected sections")
            payload["content"] = candidate.content
        else:
            sections = candidate.ticket if case.draft_type == "risk_ticket" else candidate.memo
            if sections is None or (candidate.memo if case.draft_type == "risk_ticket" else candidate.ticket):
                raise ValueError("Wrong section shape")
            payload.update(sections.model_dump())
            rendered = _ticket_text(sections) if case.draft_type == "risk_ticket" else _memo_text(sections)
            if candidate.content != rendered:
                raise ValueError("Readable content does not match its sections")
        parsed = output_model(case.draft_type).model_validate(payload, strict=True)
        if not candidate.content.strip() or len(candidate.content) > 12000:
            raise ValueError("Invalid readable content")
    except (ValueError, ValidationError):
        scores["format"] = 0
        hard_failures.append("invalid_format")
        parsed = None

    scores["grounding"], missing = _groups(text, case.checks.facts)
    failures.extend(f"fact_group_{index}_missing" for index in missing)
    if any(re.search(pattern, text, re.IGNORECASE) for pattern in case.checks.forbidden):
        scores["grounding"] = 0
        hard_failures.append("forbidden_claim")

    prompt = build_prompt("draft", [("C", case.context)], draft_type=case.draft_type)
    if ((bool(prompt.passages) and not candidate.citations)
            or any(citation not in prompt.passages.values() for citation in candidate.citations)
            or len(candidate.citations) > 64):
        scores["citations"] = 0
        hard_failures.append("missing_or_fabricated_citations")

    actions = ("\n".join(candidate.ticket.actions) if candidate.ticket else
               f"{candidate.memo.measures}\n{candidate.memo.next_steps}" if candidate.memo else
               candidate.content)
    scores["actionability"], missing = _groups(actions, case.checks.actions)
    failures.extend(f"action_group_{index}_missing" for index in missing)
    scores["uncertainty"], missing = _groups(text, case.checks.uncertainty)
    failures.extend(f"uncertainty_group_{index}_missing" for index in missing)

    all_text = text + "\n" + "\n".join(candidate.citations)
    if redact(all_text) != all_text:
        scores["safety"] = 0
        hard_failures.append("sensitive_text")
    try:
        check_sources(all_text)
        if parsed:
            protect_output(parsed.model_copy(deep=True))
    except (BadRequest, ValueError):
        scores["safety"] = 0
        hard_failures.append("unsafe_instructions_or_execution_claim")
    total = round(sum(scores[name] / 4 * weight for name, weight in WEIGHTS.items()), 2)
    passed = (not hard_failures and total >= PASS_SCORE
              and all(scores[name] >= MIN_QUALITY_DIMENSION
                      for name in ("grounding", "actionability", "uncertainty")))
    return {"scores": scores, "auto_score": total, "passed": passed,
            "failures": failures, "hard_failures": hard_failures, "human_review": "pending"}
