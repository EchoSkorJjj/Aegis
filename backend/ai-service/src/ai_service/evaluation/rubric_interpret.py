"""Grades a finding's reading. Kept out of ``rubric.py`` because that file's own text is part of the rubric hash, which
the main corpus's committed baseline is compared on: a capability evaluated from its own corpus must not change it."""

import re

from pydantic import ValidationError

from ai_service.capabilities._guardrails import check_sources, redact
from ai_service.core.errors import BadRequest
from ai_service.evaluation.cases import GoldenCase
from ai_service.evaluation.rubric import MIN_QUALITY_DIMENSION, PASS_SCORE, WEIGHTS, _groups
from ai_service.schemas import Interpretation


def grade_interpretation(case: GoldenCase, candidate: Interpretation) -> dict:
    """A finding's reading: the label the case expects (a wrong one is a critical failure, whatever else is right), an
    answer specific to this service (its ``facts`` groups), a concrete next step, what it assumed where that matters,
    a citation behind any definite label, and nothing sensitive or claimed to have been done."""
    scores = dict.fromkeys(WEIGHTS, 4.0)
    failures = []
    hard_failures = []
    try:
        Interpretation.model_validate(candidate.model_dump(), strict=True)
    except ValidationError:
        scores["format"] = 0
        hard_failures.append("invalid_format")

    reasoning = f"{candidate.what_changed}\n{candidate.why_it_matters}\n{candidate.condition}\n{candidate.what_next}"
    facts, missing = _groups(reasoning, case.checks.facts)
    scores["grounding"] = facts if candidate.signal == case.expected_signal else 0
    if candidate.signal != case.expected_signal:
        hard_failures.append("wrong_verdict")
    failures.extend(f"fact_group_{index}_missing" for index in missing)
    written = [candidate.what_changed, candidate.why_it_matters, candidate.condition, candidate.what_next,
               candidate.action_detail, *candidate.assumptions]
    if any(re.search(pattern, text, re.IGNORECASE) for pattern in case.checks.forbidden for text in written):
        scores["grounding"] = 0
        hard_failures.append("forbidden_claim")

    if candidate.signal != "UNCERTAIN" and not candidate.citations:
        scores["citations"] = 0
        hard_failures.append("missing_or_fabricated_citations")

    scores["actionability"], missing = _groups(candidate.action_detail, case.checks.actions)
    failures.extend(f"action_group_{index}_missing" for index in missing)
    scores["uncertainty"], missing = _groups("\n".join((candidate.why_it_matters, candidate.condition, *candidate.assumptions)),
                                             case.checks.uncertainty)
    failures.extend(f"uncertainty_group_{index}_missing" for index in missing)

    if any(redact(text) != text for text in written):
        scores["safety"] = 0
        hard_failures.append("sensitive_text")
    try:
        check_sources(*written)
    except BadRequest:
        scores["safety"] = 0
        hard_failures.append("unsafe_instructions_or_execution_claim")
    total = round(sum(scores[name] / 4 * weight for name, weight in WEIGHTS.items()), 2)
    passed = (not hard_failures and total >= PASS_SCORE
              and all(scores[name] >= MIN_QUALITY_DIMENSION for name in ("grounding", "actionability", "uncertainty")))
    return {"scores": scores, "auto_score": total, "passed": passed,
            "failures": failures, "hard_failures": hard_failures, "human_review": "pending"}
