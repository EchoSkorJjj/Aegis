"""Evaluation calibration: regressions must fail even when the production schema accepts them."""

import asyncio
import copy
import json

import pytest
from pydantic import ValidationError

from ai_service.capabilities.draft import _ticket_text
from ai_service.core.errors import BadRequest, RateLimited, ServiceUnavailable
from ai_service.evaluation.__main__ import main
from ai_service.evaluation.cases import Corpus, load_corpus
from ai_service.evaluation.rubric import WEIGHTS, _contains, grade, grade_assessment, grade_look_for
from ai_service.evaluation.runner import (
    GoldenClient,
    compare,
    evaluate,
    job_summary,
    offline_settings,
    write_report,
)
from ai_service.llm.stub import StubLlmClient
from ai_service.schemas import Assessment, Draft, LookFor, TicketSections


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
    assert len(corpus.cases) == 33
    drafts = [case for case in corpus.cases if case.capability == "draft"]
    assert {case.draft_type for case in drafts} == {"risk_ticket", "upgrade", "memo"}
    # E11-5: the AI on whether the vulnerable function is called — each answer, one resting on the search, one whose
    # condition code can't show (with what to check) — and code that tries to instruct it.
    assessments = [case for case in corpus.cases if case.capability == "assess"]
    assert {case.expected_verdict for case in assessments if case.expected == "draft"} == {
        "CALLED", "NOT_CALLED", "CANT_TELL"}
    assert any(case.expected == "blocked" for case in assessments)
    assert any(case.searches for case in assessments) and any(case.fix_summary for case in assessments)
    assert any(case.checks.actions for case in assessments)
    # A trigger that is the shape of bound data, absent and present: searched by the type an application declares.
    assert {case.expected_verdict for case in assessments if "data-shape" in case.tags} == {"CALLED", "NOT_CALLED"}
    # Pass 1: what to search for, from the advisory and its fix; a secret-ish name dropped; an instruction refused.
    look_fors = [case for case in corpus.cases if case.capability == "look_for"]
    assert any(case.unexpected_terms for case in look_fors)
    # The fix commit's names are mostly the package's own, which no service writes: never searched.
    assert any("fix-commit" in case.tags and case.unexpected_terms for case in look_fors)
    assert any(case.fix_summary for case in look_fors)
    assert any(case.expected == "blocked" for case in look_fors)
    for kind in ("risk_ticket", "upgrade", "memo"):
        assert any(case.draft_type == kind and case.context is None for case in corpus.cases)
        assert any(case.draft_type == kind and "privacy" in case.tags for case in corpus.cases)
        assert any(case.draft_type == kind and case.expected == "blocked" for case in corpus.cases)
    assert sum(WEIGHTS.values()) == 100


def test_all_golden_references_pass_actual_drafting_pipeline(golden_report):
    assert golden_report["summary"]["passed"] == 33
    assert golden_report["summary"]["quality_cases"] == 28
    assert golden_report["summary"]["mean_auto_score"] == 100
    assert golden_report["summary"]["guardrail_cases"] == 5
    assert golden_report["summary"]["provider_calls"] == 28
    assert {kind: (group["cases"], group["passed"]) for kind, group in golden_report["summary"]["by_capability"].items()} \
        == {"draft": (20, 20), "assess": (8, 8), "look_for": (5, 5)}
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


def _case(report, case_id):
    return next(case for case in report["cases"] if case["id"] == case_id)


def test_a_borderline_case_missing_its_threshold_is_listed_for_review_not_failed(golden_report):
    # As in CI with nothing changed: one run of three passing, each failing run 7.5 points down.
    changed = copy.deepcopy(golden_report)
    case = _case(changed, "memo-cyber-threat")
    case.update(passed=False, run_pass_rate=0.3333, score=95.0)
    comparison = compare(changed, golden_report)
    assert comparison["passed"]
    assert comparison["changed"] == [{"id": "memo-cyber-threat", "baseline_run_pass_rate": 1.0,
                                      "run_pass_rate": 0.3333, "passed": False}]


@pytest.mark.parametrize("mean,regressed", [(95.0, False), (94.99, True)])
def test_a_type_whose_mean_score_falls_more_than_five_points_regresses(golden_report, mean, regressed):
    changed = copy.deepcopy(golden_report)
    changed["summary"]["by_draft_type"]["memo"]["mean_score"] = mean
    comparison = compare(changed, golden_report)
    assert comparison["passed"] is not regressed
    assert (comparison["regressions"] == [{"kind": "type_mean_score", "id": "memo",
                                           "baseline": 100.0, "now": mean}]) is regressed


@pytest.mark.parametrize("score,regressed", [(85.0, False), (84.99, True)])
def test_a_case_whose_mean_score_falls_more_than_fifteen_points_regresses(golden_report, score, regressed):
    changed = copy.deepcopy(golden_report)
    _case(changed, "upgrade-maven")["score"] = score
    assert (compare(changed, golden_report)["regressions"] == [
        {"kind": "case_mean_score", "id": "upgrade-maven", "baseline": 100.0, "now": score}]) is regressed


@pytest.mark.parametrize("broken,regressed", [(2, False), (3, True)])
def test_more_runs_breaking_a_constraint_than_the_baseline_plus_two_regress(golden_report, broken, regressed):
    changed = copy.deepcopy(golden_report)
    drafts = [case for case in changed["cases"] if case["expected"] == "draft"]
    for case in drafts[:broken]:
        case["runs"][0]["hard_failures"] = ["missing_or_fabricated_citations"]
    assert (compare(changed, golden_report)["regressions"] == [
        {"kind": "constraint_runs", "id": "all", "baseline": 0, "now": broken}]) is regressed


def test_a_case_the_guardrails_must_block_regresses_unless_blocked_on_every_run(golden_report):
    changed = copy.deepcopy(golden_report)
    _case(changed, "memo-injection-blocked")["passed"] = False
    assert compare(changed, golden_report)["regressions"] == [
        {"kind": "not_blocked", "id": "memo-injection-blocked", "baseline": True, "now": False}]


def test_the_job_summary_counts_runs_the_provider_never_answered(corpus):
    # So a provider outage reads as one, not as a fall in quality.
    class Unavailable(GoldenClient):
        async def generate_json(self, **kwargs):
            raise ServiceUnavailable("provider is unavailable")

    report = asyncio.run(evaluate(one(corpus, "upgrade-maven"), Unavailable(), offline_settings(), runs=2))
    assert "Runs without an answer: unavailable 2." in job_summary(report)


def test_the_job_summary_names_each_regression_and_each_changed_verdict(golden_report):
    changed = copy.deepcopy(golden_report)
    changed["summary"]["by_draft_type"]["memo"]["mean_score"] = 80.0
    _case(changed, "memo-cyber-threat").update(passed=False, run_pass_rate=0.3333)
    changed["comparison"] = compare(changed, golden_report)
    text = job_summary(changed)
    assert "memo: mean score 100.0 → 80.0" in text
    assert "memo-cyber-threat: 100% → 33% of runs passing" in text


class Answers(GoldenClient):
    """The case's reference answer, except on the calls listed in ``wrong``, which get that answer instead."""

    def __init__(self, wrong: dict[int, dict]):
        self.wrong, self.call = wrong, 0

    async def generate_json(self, **kwargs):
        self.call += 1
        if self.call in self.wrong:
            reference, self.reference = self.reference, self.wrong[self.call]
            try:
                return await super().generate_json(**kwargs)
            finally:
                self.reference = reference
        return await super().generate_json(**kwargs)


def one(corpus, case_id):
    return corpus.model_copy(update={"cases": [next(case for case in corpus.cases if case.id == case_id)]})


@pytest.mark.parametrize("wrong_calls,passed", [({3}, True), ({2, 3}, False)])
def test_a_case_passes_when_it_meets_the_pass_rate_on_two_of_three_runs(corpus, wrong_calls, passed):
    case = next(case for case in corpus.cases if case.id == "upgrade-maven")
    vague = {"content": "Review the dependency.", "cited_passage_ids": case.reference["cited_passage_ids"]}
    report = asyncio.run(evaluate(one(corpus, "upgrade-maven"), Answers(dict.fromkeys(wrong_calls, vague)),
                                  offline_settings(), runs=3))
    result = report["cases"][0]
    assert result["run_pass_rate"] == round((3 - len(wrong_calls)) / 3, 4)
    assert result["passed"] is passed
    assert result["constraint_failures"] == []


def test_one_run_breaking_a_constraint_fails_the_case_however_the_others_score(corpus):
    # Call 2 cites a passage it was never sent (and so does its validation retry, call 3): an invalid output.
    invented = {"content": "Draft.", "cited_passage_ids": ["unknown"]}
    report = asyncio.run(evaluate(one(corpus, "upgrade-maven"), Answers({2: invented, 3: invented}),
                                  offline_settings(), runs=3))
    result = report["cases"][0]
    assert result["run_pass_rate"] == round(2 / 3, 4)
    assert result["constraint_failures"] == ["invalid_output"]
    assert result["passed"] is False


def test_a_full_minute_is_waited_out_rather_than_counted_as_a_failed_run(corpus):
    class LimitedOnce(GoldenClient):
        limited = False

        async def generate_json(self, **kwargs):
            if not self.limited:
                self.limited = True
                raise RateLimited("LLM token rate limit reached; try again later", retry_after=7)
            return await super().generate_json(**kwargs)

    waits = []

    async def wait(seconds):
        waits.append(seconds)

    report = asyncio.run(evaluate(one(corpus, "upgrade-maven"), LimitedOnce(), offline_settings(), wait=wait))
    assert waits == [7]
    assert report["cases"][0]["passed"] is True


def test_each_draft_type_reports_its_pass_rate_and_mean_score_in_the_job_summary(golden_report, tmp_path,
                                                                                    monkeypatch):
    assert golden_report["summary"]["by_draft_type"]["memo"] == {
        "cases": 6, "passed": 6, "run_pass_rate": 1.0, "mean_score": 100.0}
    job_summary = tmp_path / "step-summary.md"
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(job_summary))
    assert main(["--case", "memo-cyber-threat", "--output", str(tmp_path / "run")]) == 0
    assert "| memo | 1 | 1 | 100% | 100.0 |" in job_summary.read_text(encoding="utf-8")


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


def assessment(case, **changes):
    payload = {"verdict": case.reference["verdict"], "rationale": case.reference["rationale"],
               "checks": case.reference["checks"],
               "cited_excerpts": [passage for passage in case.reference["cited_passage_ids"]
                                  if passage.startswith("E")], **changes}
    return Assessment(**payload)


def test_an_assessment_is_scored_on_the_checks_it_suggests_too(corpus):
    case = next(case for case in corpus.cases if case.id == "assess-condition-outside-the-code")
    assert grade_assessment(case, assessment(case))["passed"] is True
    vague = grade_assessment(case, assessment(case, checks=["Review the code."]))
    assert vague["scores"]["actionability"] < 4 and vague["passed"] is False
    leaky = grade_assessment(case, assessment(case, checks=["Rotate token=ghp_" + "a1" * 12]))
    assert "sensitive_text" in leaky["hard_failures"]


def test_a_settled_answer_never_asks_a_reviewer_to_redo_the_search_or_look_for_internals(corpus):
    case = next(case for case in corpus.cases if case.id == "assess-data-shape-trigger-absent")
    assert grade_assessment(case, assessment(case))["passed"] is True
    for noise in ("Search the entire codebase for references to java.nio.file.Path.",
                  "Verify no imports of jackson-databind's NioPathDeserializer."):
        graded = grade_assessment(case, assessment(case, checks=[noise]))
        assert "forbidden_claim" in graded["hard_failures"], noise


def test_a_look_for_answer_is_scored_on_the_terms_it_keeps(corpus):
    case = next(case for case in corpus.cases if case.id == "look-for-fix-symbols")
    expected = LookFor(terms=case.expected_terms)
    assert grade_look_for(case, expected)["passed"] is True
    assert grade_look_for(case, expected)["auto_score"] == 100
    missing = grade_look_for(case, LookFor(terms=[]))
    assert missing["scores"]["grounding"] == 0 and missing["passed"] is False
    secretive = next(case for case in corpus.cases if case.id == "look-for-secret-dropped")
    unsafe = grade_look_for(secretive, LookFor(terms=secretive.expected_terms + secretive.unexpected_terms))
    assert "unsafe_or_ungrounded_terms" in unsafe["hard_failures"] and unsafe["passed"] is False


def test_corpus_rejects_a_look_for_case_without_the_terms_it_expects(corpus):
    data = corpus.model_dump()
    look_for = next(index for index, case in enumerate(data["cases"]) if case["id"] == "look-for-fix-symbols")
    data["cases"][look_for]["expected_terms"] = []
    with pytest.raises(ValidationError, match="expected terms"):
        Corpus.model_validate(data)


def test_an_assessment_is_scored_on_its_verdict_and_the_lines_it_cites(corpus):
    case = next(case for case in corpus.cases if case.id == "assess-not-called")
    assert grade_assessment(case, assessment(case))["passed"] is True
    assert grade_assessment(case, assessment(case))["auto_score"] == 100

    wrong = grade_assessment(case, assessment(case, verdict="CALLED"))
    assert "wrong_verdict" in wrong["hard_failures"] and wrong["passed"] is False
    unsent = grade_assessment(case, assessment(case, cited_excerpts=["E9"]))
    assert "missing_or_fabricated_citations" in unsent["hard_failures"] and unsent["passed"] is False
    uncited = grade_assessment(case, assessment(case, cited_excerpts=[]))
    assert "missing_or_fabricated_citations" in uncited["hard_failures"]
    leaky = grade_assessment(case, assessment(case, rationale="Called from password=synthetic-password."))
    assert "sensitive_text" in leaky["hard_failures"]


def test_corpus_rejects_an_assessment_case_without_its_expected_answer(corpus):
    data = corpus.model_dump()
    assess = next(index for index, case in enumerate(data["cases"]) if case["id"] == "assess-called")
    data["cases"][assess]["expected_verdict"] = None
    with pytest.raises(ValidationError, match="expected verdict"):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][assess]["reference"]["verdict"] = "MAYBE"
    with pytest.raises(ValidationError):
        Corpus.model_validate(data)
    data = corpus.model_dump()
    data["cases"][assess]["excerpts"] = []
    with pytest.raises(ValidationError):
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
    # Against a baseline only a regression fails: cases that already failed there and still do don't.
    assert main(["--provider", "stub", "--output", str(tmp_path / "stub-again"),
                 "--baseline", str(tmp_path / "stub" / "report.json")]) == 0
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
