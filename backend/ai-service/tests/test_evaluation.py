"""Evaluation calibration: regressions must fail even when the production schema accepts them."""

import asyncio
import copy
import json

import pytest
from pydantic import ValidationError

from ai_service.capabilities.draft import _ticket_text
from ai_service.core.errors import BadRequest, ServiceUnavailable
from ai_service.evaluation.__main__ import main
from ai_service.evaluation.cases import Corpus, load_corpus
from ai_service.evaluation.rubric import WEIGHTS, _contains, grade
from ai_service.evaluation.runner import (
    GoldenClient,
    compare,
    evaluate,
    offline_settings,
    write_report,
)
from ai_service.llm.stub import StubLlmClient
from ai_service.schemas import Draft, TicketSections


@pytest.fixture(scope="module")
def corpus():
    return load_corpus()


@pytest.fixture(scope="module")
def golden_report(corpus):
    return asyncio.run(evaluate(corpus, GoldenClient(), offline_settings()))


def ticket(case, **changes):
    payload = {key: value for key, value in case.reference.items() if key != "cited_passage_ids"}
    payload.update(changes)
    sections = TicketSections(**payload)
    return Draft(draft_type="risk_ticket", content=_ticket_text(sections), ticket=sections,
                 citations=[case.context])


def test_corpus_covers_all_drafts_missing_evidence_privacy_and_attacks(corpus):
    assert len(corpus.cases) == 20
    assert {case.draft_type for case in corpus.cases} == {"risk_ticket", "upgrade", "memo"}
    for kind in ("risk_ticket", "upgrade", "memo"):
        assert any(case.draft_type == kind and case.context is None for case in corpus.cases)
        assert any(case.draft_type == kind and "privacy" in case.tags for case in corpus.cases)
        assert any(case.draft_type == kind and case.expected == "blocked" for case in corpus.cases)
    assert sum(WEIGHTS.values()) == 100


def test_all_golden_references_pass_actual_drafting_pipeline(golden_report):
    assert golden_report["summary"]["passed"] == 20
    assert golden_report["summary"]["quality_cases"] == 17
    assert golden_report["summary"]["mean_auto_score"] == 100
    assert golden_report["summary"]["guardrail_cases"] == 3
    assert golden_report["summary"]["provider_calls"] == 17
    assert golden_report["mode"] == "reference_replay"
    assert golden_report["summary"]["human_review"] == "pending"
    for case in golden_report["cases"]:
        if case["expected"] == "blocked":
            assert case["provider_calls"] == 0
            assert case["grading"] is None
        if "privacy" in case["tags"]:
            wire = json.dumps(case["draft"])
            assert "[REDACTED]" in wire
            assert "alice@example.test" not in wire
            assert "synthetic-evaluation-secret" not in wire
            assert "synthetic-password" not in wire
            assert "555-123-4567" not in wire
    long_case = next(case for case in golden_report["cases"] if case["id"] == "upgrade-long-evidence")
    assert long_case["draft"]["citations"][0] == long_case["context"][1000:2000]


def test_generic_stub_cannot_be_mistaken_for_quality(corpus):
    report = asyncio.run(evaluate(corpus, StubLlmClient(), offline_settings()))
    assert report["summary"]["quality_pass_rate"] == 0
    assert report["summary"]["guardrail_pass_rate"] == 1
    assert report["summary"]["mean_auto_score"] < 80
    assert report["mode"] == "provider_evaluation"


def test_missing_target_fails_even_with_a_high_total_score(corpus):
    case = corpus.cases[0]
    candidate = ticket(case, actions=["Upgrade ledger-lib to 12.0.0.", "Confirm runtime use."])
    result = grade(case, candidate)
    assert result["auto_score"] > 80
    assert result["scores"]["actionability"] == 2
    assert result["passed"] is False


@pytest.mark.parametrize("text,expected", [
    ("Upgrade to 2.0.0.", True), ("Upgrade to 12.0.0.", False),
    ("Upgrade to 1.2.0.0.", False), ("Upgrade to 2.0.0.alpha.", False),
    ("Version **2.0.0** is selected.", True),
])
def test_version_anchors_do_not_match_other_versions_or_reject_sentence_punctuation(text, expected):
    assert _contains(text, "2.0.0") is expected


def test_citations_cannot_supply_missing_narrative_facts(corpus):
    case = corpus.cases[0]
    candidate = ticket(case, summary="Draft.", risk="Risk requires review.", impact="Impact requires review.",
                       actions=["Review the evidence."])
    result = grade(case, candidate)
    assert result["scores"]["citations"] == 4
    assert result["scores"]["grounding"] == 0
    assert not result["passed"]


@pytest.mark.parametrize("mutation,flag", [
    ("fabricated_citation", "missing_or_fabricated_citations"),
    ("sensitive_text", "sensitive_text"),
    ("execution", "unsafe_instructions_or_execution_claim"),
    ("unsupported_fact", "forbidden_claim"),
    ("wrong_type", "invalid_format"),
    ("wrong_content", "invalid_format"),
])
def test_critical_failures_override_an_otherwise_good_draft(corpus, mutation, flag):
    case = corpus.cases[0]
    candidate = ticket(case)
    if mutation == "fabricated_citation":
        candidate.citations = ["The model invented this quotation."]
    elif mutation == "sensitive_text":
        candidate = ticket(case, risk=candidate.ticket.risk + " Contact alice@example.test.")
    elif mutation == "execution":
        candidate = ticket(case, risk=candidate.ticket.risk + " I have merged the pull request.")
    elif mutation == "unsupported_fact":
        candidate = ticket(case, impact=candidate.ticket.impact + " This affected 1000 customers.")
    elif mutation == "wrong_type":
        candidate.draft_type = "memo"
    else:
        candidate.content += " Stuffed content absent from the actual sections."
    result = grade(case, candidate)
    assert flag in result["hard_failures"]
    assert result["passed"] is False


def test_invalid_provider_output_is_failed_not_counted_as_a_success(corpus):
    class InvalidClient(GoldenClient):
        async def generate_json(self, **kwargs):
            self.reference = {"content": "Draft.", "cited_passage_ids": ["unknown"]}
            return await super().generate_json(**kwargs)

    chosen = next(case for case in corpus.cases if case.id == "upgrade-maven")
    report = asyncio.run(evaluate(corpus.model_copy(update={"cases": [chosen]}),
                                  InvalidClient(), offline_settings()))
    case = report["cases"][0]
    assert case["outcome"] == "invalid_output"
    assert case["provider_calls"] == 2
    assert not case["passed"]
    assert report["summary"]["mean_auto_score"] == 0


def test_baseline_detects_score_and_pass_regressions(golden_report):
    changed = copy.deepcopy(golden_report)
    changed["cases"][0]["grading"]["auto_score"] = 99
    assert compare(changed, golden_report)["regressions"] == ["ticket-vulnerability"]
    assert compare(changed, golden_report, max_score_drop=1)["passed"]
    changed["cases"][0]["passed"] = False
    assert not compare(changed, golden_report, max_score_drop=100)["passed"]


@pytest.mark.parametrize("failure,outcome", [
    (ServiceUnavailable("provider is unavailable"), "unavailable"),
    (BadRequest("Input exceeds a configured limit"), "invalid_request"),
    (RuntimeError("password=synthetic-exception-secret"), "generation_error"),
])
def test_operational_failures_are_not_passing_guardrails_or_quality(corpus, failure, outcome):
    class FailingClient(GoldenClient):
        async def generate_json(self, **kwargs):
            raise failure

    chosen = next(case for case in corpus.cases if case.id == "upgrade-maven")
    report = asyncio.run(evaluate(corpus.model_copy(update={"cases": [chosen]}),
                                  FailingClient(), offline_settings()))
    assert report["cases"][0]["outcome"] == outcome
    assert report["cases"][0]["passed"] is False
    assert report["summary"]["generation_success_rate"] == 0
    assert report["summary"]["mean_generated_auto_score"] is None
    assert "synthetic-exception-secret" not in json.dumps(report)


@pytest.mark.parametrize("key", ["schema_version", "corpus_hash", "rubric_hash"])
def test_incompatible_baselines_are_rejected(golden_report, key):
    changed = copy.deepcopy(golden_report)
    changed[key] = "different"
    with pytest.raises(ValueError, match="same report schema"):
        compare(changed, golden_report)


def test_corpus_rejects_duplicate_ids_invalid_rules_and_invalid_reference(corpus):
    data = corpus.model_dump()
    data["cases"].append(data["cases"][0])
    with pytest.raises(ValidationError, match="unique"):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][0]["checks"]["facts"] = [[]]
    with pytest.raises(ValidationError, match="nonempty"):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][0]["reference"]["approved"] = True
    with pytest.raises(ValidationError):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][0]["checks"]["forbidden"] = ["[invalid"]
    with pytest.raises(ValidationError, match="valid regular expression"):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][0]["checks"]["facts"] = []
    with pytest.raises(ValidationError, match="factual and actionable"):
        Corpus.model_validate(data)


def test_e14_artifacts_are_machine_readable_redacted_and_reviewable(golden_report, tmp_path):
    write_report(golden_report, tmp_path)
    report = json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))
    review = json.loads((tmp_path / "human-review.json").read_text(encoding="utf-8"))
    markdown = (tmp_path / "report.md").read_text(encoding="utf-8")
    assert report["summary"]["mean_auto_score"] == 100
    assert report["prompt_templates"]
    assert report["guardrails_hash"]
    assert review["run_id"] == report["run_id"]
    assert all(set(case["scores"]) == set(WEIGHTS) for case in review["cases"])
    assert all(value is None for case in review["cases"] for value in case["scores"].values())
    assert "not measured live-model quality" in markdown
    for filename in ("report.json", "report.md", "human-review.json"):
        text = (tmp_path / filename).read_text(encoding="utf-8")
        assert "alice@example.test" not in text
        assert "synthetic-evaluation-secret" not in text


def test_cli_exit_codes_baseline_and_selection(tmp_path):
    baseline = tmp_path / "baseline"
    assert main(["--output", str(baseline)]) == 0
    assert main(["--output", str(tmp_path / "comparison"), "--baseline", str(baseline / "report.json")]) == 0
    assert main(["--provider", "stub", "--output", str(tmp_path / "stub")]) == 1
    assert main(["--case", "upgrade-maven", "--output", str(tmp_path / "one")]) == 0
    assert main(["--case", "unknown-case", "--output", str(tmp_path / "unknown")]) == 2
    assert main(["--model", "unused", "--output", str(tmp_path / "invalid")]) == 2


def test_report_writer_redacts_unsafe_candidates_after_scoring_and_quotes_backticks(golden_report, tmp_path):
    report = copy.deepcopy(golden_report)
    report["cases"][0]["draft"]["content"] = "```\n![x](https://remote.example/x)\npassword=fixture-secret"
    report["cases"][0]["title"] = "![title](https://remote.example/x)"
    report["cases"][0]["grading"]["hard_failures"] = ["sensitive_text"]
    report["cases"][0]["passed"] = False
    write_report(report, tmp_path)
    wire = (tmp_path / "report.json").read_text(encoding="utf-8")
    markdown = (tmp_path / "report.md").read_text(encoding="utf-8")
    assert "fixture-secret" not in wire + markdown
    assert '"sensitive_text"' in wire
    assert "````text" in markdown
    assert "```text\n![title]" in markdown
