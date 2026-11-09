"""Run the actual guarded drafting pipeline and export reproducible E14 inputs."""

import asyncio
import json
import platform
import re
import time
from collections import Counter
from datetime import UTC, datetime
from importlib.resources import files
from pathlib import Path
from uuid import uuid4

from ai_service.capabilities._guardrails import redact
from ai_service.capabilities.assess import assess
from ai_service.capabilities.draft import draft
from ai_service.capabilities.interpret import interpret
from ai_service.capabilities.look_for import look_for
from ai_service.core.config import Settings
from ai_service.core.errors import BadGateway, BadRequest, RateLimited, ServiceUnavailable
from ai_service.evaluation.cases import Corpus, fingerprint
from ai_service.evaluation.rubric import (
    CONSTRAINTS,
    MIN_QUALITY_DIMENSION,
    PASS_SCORE,
    RUBRIC_VERSION,
    RUN_PASS_RATE,
    WEIGHTS,
    grade,
    grade_assessment,
    grade_look_for,
)
from ai_service.evaluation.rubric_interpret import grade_interpretation
from ai_service.llm.base import LlmResult
from ai_service.llm.structured import StructuredGenerator
from ai_service.prompts import build_prompt
from ai_service.schemas import DraftRequest

REPORT_VERSION = "draft-evaluation.v2"
# A run waits out a full minute of the token budget at most this often before it counts as unavailable.
MAX_WAITS = 5
DRAFT_TYPES = ("risk_ticket", "upgrade", "memo")
CAPABILITIES = ("draft", "assess", "look_for", "interpret")


class GoldenClient:
    """Reference replay validates the pipeline/scorer, not a real model's quality."""
    provider = "golden"
    model = "synthetic-reference.v1"
    reference: dict | None = None

    async def generate_json(self, **kwargs):
        if self.reference is None:
            raise ValueError("No golden reference for this case")
        return LlmResult(json.dumps(self.reference), self.provider, self.model, 0, 0)

    async def aclose(self):
        pass


class TrackingClient:
    def __init__(self, client):
        self.client = client
        self.provider, self.model = client.provider, client.model
        self.calls = self.input_tokens = self.output_tokens = 0

    async def generate_json(self, **kwargs):
        self.calls += 1
        result = await self.client.generate_json(**kwargs)
        self.input_tokens += result.input_tokens
        self.output_tokens += result.output_tokens
        return result


def offline_settings() -> Settings:
    # Offline runs never select a configured paid provider. Larger admission limits are for
    # synthetic calls with zero actual token usage only; live runs retain their configured limits.
    return Settings(_env_file=None, llm_provider="stub", llm_max_requests_per_minute=1000,
                    llm_max_tokens_per_minute=1000000, llm_daily_token_budget=10000000,
                    llm_timeout_seconds=20, llm_max_output_tokens=1500, llm_max_input_chars=16000)


def _protected(value):
    if isinstance(value, str):
        return redact(value)
    if isinstance(value, list):
        return [_protected(item) for item in value]
    if isinstance(value, dict):
        return {key: _protected(item) for key, item in value.items()}
    return value


async def _run(case, client, tracked, engine, wait) -> dict:
    """One run of one case: what the pipeline produced, how it was graded and what it cost. A full minute of the token
    budget is waited out and the case run again, so pacing is never counted as a failure."""
    if isinstance(client, GoldenClient):
        client.reference = case.reference
    before = (tracked.calls, tracked.input_tokens, tracked.output_tokens)
    started = time.perf_counter()
    candidate, grading, waits = None, None, 0
    while True:
        try:
            if case.capability == "assess":
                candidate = await assess(case.assessment_request(), engine)
                grading = grade_assessment(case, candidate)
            elif case.capability == "look_for":
                candidate = await look_for(case.look_for_request(), engine)
                grading = grade_look_for(case, candidate)
            elif case.capability == "interpret":
                candidate = await interpret(case.interpretation_request(), engine)
                grading = grade_interpretation(case, candidate)
            else:
                candidate = await draft(DraftRequest(finding_id=case.id, draft_type=case.draft_type,
                                                   context=case.context), engine)
                grading = grade(case, candidate)
            outcome = "draft"
        except RateLimited as error:
            if waits < MAX_WAITS:
                waits += 1
                await wait(min(error.retry_after, 60))
                continue
            outcome = "unavailable"
        except BadRequest as error:
            outcome = ("blocked" if error.message == "Source content contains unsupported control instructions"
                       else "invalid_request")
        except BadGateway:
            outcome = "invalid_output"
        except ServiceUnavailable:
            outcome = "unavailable"
        except Exception:  # noqa: BLE001 -- provider exceptions may contain credentials; retain a safe failed case.
            # Fail the case without exporting exception content, prompts or credentials.
            outcome = "generation_error"
        break
    calls = tracked.calls - before[0]
    passed = (outcome == "blocked" and calls == 0 if case.expected == "blocked"
              else outcome == "draft" and grading["passed"])
    return {"outcome": outcome, "passed": passed, "grading": grading, "candidate": candidate,
            "constraints": sorted(({outcome} | set(grading["hard_failures"] if grading else ())) & CONSTRAINTS),
            "latency_ms": round((time.perf_counter() - started) * 1000, 2), "provider_calls": calls,
            "input_tokens": tracked.input_tokens - before[1], "output_tokens": tracked.output_tokens - before[2]}


def _group(cases: list[dict]) -> dict:
    """How a group of cases did: each case's verdict, every run's, and the mean score of the cases expected to draft."""
    runs = [run for case in cases for run in case["runs"]]
    scored = [case["score"] for case in cases if case["expected"] == "draft"]
    return {"cases": len(cases), "passed": sum(case["passed"] for case in cases),
            "run_pass_rate": round(sum(run["passed"] for run in runs) / len(runs), 4) if runs else None,
            "mean_score": round(sum(scored) / len(scored), 2) if scored else None}


async def evaluate(corpus: Corpus, client, config: Settings, *, delay_seconds: float = 0, runs: int = 1,
                   wait=asyncio.sleep) -> dict:
    if runs < 1:
        raise ValueError("Each case runs at least once")
    tracked = TrackingClient(client)
    engine = StructuredGenerator(config, tracked)
    results, templates = [], {}
    for index, case in enumerate(corpus.cases):
        prompt = (build_prompt(case.capability, [("A", case.advisory)]) if case.capability != "draft"
                  else build_prompt("draft", [("C", case.context)], draft_type=case.draft_type))
        resources = files("ai_service.prompts")
        template = prompt.template_id.rsplit(".", 1)[0]
        templates[prompt.template_id] = fingerprint([
            resources.joinpath(f"{template}.system.md").read_text(encoding="utf-8").replace("\r\n", "\n"),
            resources.joinpath(f"{template}.user.md").read_text(encoding="utf-8").replace("\r\n", "\n"),
        ])
        done = []
        for attempt in range(runs):
            if (index or attempt) and delay_seconds:
                await wait(delay_seconds)
            done.append(await _run(case, client, tracked, engine, wait))
        passes = sum(run["passed"] for run in done)
        constraints = sorted({name for run in done for name in run["constraints"]})
        scores = [run["grading"]["auto_score"] if run["grading"] else 0 for run in done]
        failures = Counter(name for run in done for name in
                           (*(run["grading"]["hard_failures"] + run["grading"]["failures"] if run["grading"] else ()),
                            *((run["outcome"],) if run["outcome"] not in ("draft", "blocked") else ())))
        first = done[0]
        results.append({
            "id": case.id, "title": case.title, "capability": case.capability, "draft_type": case.draft_type,
            "tags": case.tags,
            "expected": case.expected, "outcome": first["outcome"],
            "passed": (passes == runs if case.expected == "blocked"
                       else passes / runs >= RUN_PASS_RATE and not constraints),
            "run_pass_rate": round(passes / runs, 4), "score": round(sum(scores) / runs, 2),
            "score_range": [min(scores), max(scores)], "failure_types": dict(sorted(failures.items())),
            "constraint_failures": constraints,
            "grading": first["grading"], "draft": _protected(first["candidate"].model_dump()) if first["candidate"] else None,
            "context": redact(case.context if case.capability == "draft" else _assessment_source(case)),
            "latency_ms": round(sum(run["latency_ms"] for run in done) / runs, 2),
            "provider_calls": sum(run["provider_calls"] for run in done),
            "input_tokens": sum(run["input_tokens"] for run in done),
            "output_tokens": sum(run["output_tokens"] for run in done),
            "runs": [{key: run[key] for key in ("outcome", "passed", "latency_ms", "provider_calls", "input_tokens",
                                                "output_tokens")}
                     | {"auto_score": run["grading"]["auto_score"] if run["grading"] else None,
                        "hard_failures": run["grading"]["hard_failures"] if run["grading"] else [],
                        "failures": run["grading"]["failures"] if run["grading"] else []} for run in done],
        })
    quality = [result for result in results if result["expected"] == "draft"]
    blocked = [result for result in results if result["expected"] == "blocked"]
    generated = [result for result in quality if any(run["auto_score"] is not None for run in result["runs"])]

    def rate(items):
        return round(sum(item["passed"] for item in items) / len(items), 4) if items else None

    return {
        "schema_version": REPORT_VERSION, "run_id": str(uuid4()),
        "generated_at": datetime.now(UTC).isoformat(), "python_version": platform.python_version(),
        "provider": client.provider, "model": client.model,
        "mode": "reference_replay" if isinstance(client, GoldenClient) else "provider_evaluation",
        "runs": runs,
        "corpus_version": corpus.version, "corpus_hash": fingerprint(corpus.model_dump()),
        "rubric_version": RUBRIC_VERSION,
        "rubric_hash": fingerprint({"weights": WEIGHTS, "pass_score": PASS_SCORE,
                                    "min_quality_dimension": MIN_QUALITY_DIMENSION,
                                    "version": RUBRIC_VERSION,
                                    "code": files(__package__).joinpath("rubric.py")
                                    .read_text(encoding="utf-8").replace("\r\n", "\n")}),
        "generation_parameters": {"timeout_seconds": config.llm_timeout_seconds,
                                  "max_input_chars": config.llm_max_input_chars,
                                  "max_output_tokens": config.llm_max_output_tokens,
                                  "delay_seconds": delay_seconds},
        "prompt_templates": templates,
        "guardrails_hash": fingerprint(files("ai_service.capabilities").joinpath("_guardrails.py")
                                       .read_text(encoding="utf-8").replace("\r\n", "\n")),
        "summary": {
            "cases": len(results), "passed": sum(result["passed"] for result in results),
            "failed": sum(not result["passed"] for result in results),
            "quality_cases": len(quality), "quality_pass_rate": rate(quality),
            "generation_success_rate": round(len(generated) / len(quality), 4) if quality else None,
            "mean_generated_auto_score": (round(sum(result["score"] for result in generated) / len(generated), 2)
                                          if generated else None),
            "mean_auto_score": (round(sum(result["score"] for result in quality) / len(quality), 2)
                                if quality else None),
            "guardrail_cases": len(blocked), "guardrail_pass_rate": rate(blocked),
            "provider_calls": tracked.calls, "input_tokens": tracked.input_tokens,
            "output_tokens": tracked.output_tokens,
            "outcomes": dict(sorted(Counter(run["outcome"] for result in results for run in result["runs"]).items())),
            "by_draft_type": {kind: _group([item for item in results if item["draft_type"] == kind])
                              for kind in DRAFT_TYPES},
            "by_capability": {kind: _group([item for item in results if item["capability"] == kind])
                              for kind in CAPABILITIES},
            "human_review": "pending", "machine_scores_are_quality_proxies": True,
        }, "cases": results,
    }


def _assessment_source(case) -> str:
    """An assess or look-for case's sources as one text: the advisory, what its fix changed, the search, then each
    excerpt under its path, first line and kind."""
    searches = "\n".join(f"searched {item['files_searched']} files for {item['term']}: {item['lines_matched']} lines"
                         for item in case.searches or [])
    excerpts = "\n\n".join(f"{item['id']} {item['path']}:{item['start_line']} [{item['kind']}]\n{item['text']}"
                            for item in case.excerpts or [])
    parts = [case.advisory or "", case.fix_summary or "", searches, excerpts]
    return "\n\n".join(part for part in parts if part).strip()


# What fails a run against the baseline, fixed before comparing (CS203-231). Three evaluations of the same code
# (the baseline and two CI runs, 2026-11-07) moved a type's mean score by at most 2.5 points and a case's by at most
# 10 (one run in three going wrong), and broke a constraint in at most 1 run of 99; meanwhile four cases changed
# verdict, both ways, so a case missing its pass rate once is noise, not a regression.
MAX_TYPE_SCORE_DROP = 5
MAX_CASE_SCORE_DROP = 15
EXTRA_CONSTRAINT_RUNS = 2


def _groups(report: dict) -> dict:
    summary = report["summary"]
    return {**{kind: summary["by_draft_type"][kind] for kind in DRAFT_TYPES},
            **{kind: summary["by_capability"][kind] for kind in ("assess", "look_for")}}


def _constraint_runs(report: dict) -> int:
    return sum(bool(({run["outcome"]} | set(run["hard_failures"])) & CONSTRAINTS)
               for case in report["cases"] for run in case["runs"])


def _unanswered_runs(report: dict) -> int:
    # Refused by the provider (a rate or quota limit, an outage, a timeout) or by the token budget after its waits.
    return sum(run["outcome"] == "unavailable" for case in report["cases"] for run in case["runs"])


def compare(report: dict, baseline: dict) -> dict:
    """Regressions against the baseline, judged on what a few runs can measure: a type's mean score falling more
    than ``MAX_TYPE_SCORE_DROP``, a case's more than ``MAX_CASE_SCORE_DROP``, more runs breaking a constraint than the
    baseline had plus ``EXTRA_CONSTRAINT_RUNS``, or a case the guardrails must block not blocked on every run (that is
    code, so never noise). A case changing verdict is listed for a reviewer, not failed on. Only runs of the same
    corpus, rubric and run count compare.

    A run the provider never answered scores zero, which says nothing about quality: with any, no score or verdict is
    compared and the comparison fails as inconclusive. The guardrail and constraint checks still apply, since blocking
    happens before the provider is called and a constraint breaks only in a run it answered."""
    for key in ("schema_version", "corpus_hash", "rubric_hash", "runs"):
        if report[key] != baseline.get(key):
            raise ValueError("Baseline must use the same report schema, corpus, rubric and number of runs")
    current = {case["id"]: case for case in report["cases"]}
    previous = {case["id"]: case for case in baseline["cases"]}
    if current.keys() != previous.keys():
        raise ValueError("Baseline must include the same selected cases")
    if _unanswered_runs(baseline):
        raise ValueError("Baseline must be a run the provider answered every run of")
    unanswered = _unanswered_runs(report)
    scored = not unanswered

    def regression(kind, key, then, now):
        return {"kind": kind, "id": key, "baseline": then, "now": now}

    regressions = []
    then_groups = _groups(baseline)
    for kind, group in _groups(report).items() if scored else ():
        then = then_groups[kind]["mean_score"]
        if then is not None and group["mean_score"] is not None and then - group["mean_score"] > MAX_TYPE_SCORE_DROP:
            regressions.append(regression("type_mean_score", kind, then, group["mean_score"]))
    for key, case in current.items():
        if case["expected"] == "blocked" and not case["passed"]:
            regressions.append(regression("not_blocked", key, previous[key]["passed"], False))
        elif scored and case["expected"] == "draft" and previous[key]["score"] - case["score"] > MAX_CASE_SCORE_DROP:
            regressions.append(regression("case_mean_score", key, previous[key]["score"], case["score"]))
    then, now = _constraint_runs(baseline), _constraint_runs(report)
    if now > then + EXTRA_CONSTRAINT_RUNS:
        regressions.append(regression("constraint_runs", "all", then, now))
    changed = [{"id": key, "baseline_run_pass_rate": previous[key]["run_pass_rate"],
                "run_pass_rate": case["run_pass_rate"], "passed": case["passed"]}
               for key, case in current.items() if scored and case["passed"] != previous[key]["passed"]]
    return {"baseline_run_id": baseline["run_id"], "unanswered_runs": unanswered,
            "all_runs": sum(len(case["runs"]) for case in report["cases"]),
            "regressions": regressions, "changed": changed, "passed": scored and not regressions}


def _regression_text(item: dict) -> str:
    if item["kind"] == "type_mean_score":
        return f"{item['id']}: mean score {item['baseline']:.1f} → {item['now']:.1f}"
    if item["kind"] == "case_mean_score":
        return f"case {item['id']}: mean score {item['baseline']:.1f} → {item['now']:.1f}"
    if item["kind"] == "constraint_runs":
        return f"runs breaking a constraint: {item['baseline']} → {item['now']}"
    return f"case {item['id']}: not blocked on every run"


def _comparison_lines(comparison: dict) -> list[str]:
    if comparison["unanswered_runs"]:
        return [(f"Inconclusive: the provider didn't answer {comparison['unanswered_runs']} of "
                 f"{comparison['all_runs']} runs (a rate or quota limit, an outage or a timeout), so no scores were "
                 "compared with the baseline. Re-run when it answers again."), "",
                "Guardrail and constraint regressions: "
                + ("; ".join(map(_regression_text, comparison["regressions"])) or "none") + "."]
    lines = ["Regressions against the baseline: "
             + ("; ".join(map(_regression_text, comparison["regressions"])) or "none") + "."]
    if comparison["changed"]:
        lines.extend(["", "Changed verdict since the baseline (listed for review; a case near its pass rate changes "
                      "between runs with nothing changed): "
                      + "; ".join(f"{item['id']}: {item['baseline_run_pass_rate']:.0%} → {item['run_pass_rate']:.0%} "
                                  "of runs passing" for item in comparison["changed"]) + "."])
    return lines


def _fenced(text: str) -> str:
    # Longer than any backtick run in the text, so exported model text cannot close the code block.
    fence = "`" * max(3, max((len(run) + 1 for run in re.findall(r"`+", text)), default=3))
    return f"{fence}text\n{text}\n{fence}\n"


def job_summary(report: dict) -> str:
    """The run at a glance, for CI's job summary: each draft type's and capability's pass rates and mean score."""
    groups = _groups(report)
    lines = [f"### AI evaluation: {report['provider']} / {report['model']}", "",
             (f"{report['runs']} run(s) per case. A case passes when its quality checks pass on at least "
              f"{RUN_PASS_RATE:.0%} of its runs and its constraints hold on every run."), "",
             "| Type | Cases | Passed | Run pass rate | Mean score |", "|---|---:|---:|---:|---:|"]
    for kind, group in groups.items():
        if group["cases"]:
            score = "n/a" if group["mean_score"] is None else f"{group['mean_score']:.1f}"
            lines.append(f"| {kind} | {group['cases']} | {group['passed']} | {group['run_pass_rate']:.0%} | {score} |")
    unanswered = {outcome: count for outcome, count in report["summary"]["outcomes"].items()
                  if outcome not in ("draft", "blocked")}
    if unanswered:
        lines.extend(["", "Runs without an answer: "
                      + ", ".join(f"{outcome} {count}" for outcome, count in unanswered.items()) + "."])
    if "comparison" in report:
        lines.extend(["", *_comparison_lines(report["comparison"])])
    return "\n".join(lines) + "\n"


def write_report(report: dict, directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    protected = _protected(report)
    (directory / "report.json").write_text(json.dumps(protected, indent=2, ensure_ascii=False) + "\n",
                                           encoding="utf-8")
    (directory / "summary.md").write_text(job_summary(protected), encoding="utf-8")
    summary = protected["summary"]
    lines = ["# Aegis draft evaluation", "", f"Mode: {protected['mode']}.",
             "Provider/model:", _fenced(f"{protected['provider']} / {protected['model']}"),
             "Automated scores are rule-based proxies. Human semantic review is pending.",
             "Reference replay is harness calibration, not measured live-model quality.", "",
             (f"Cases: {summary['passed']}/{summary['cases']} passed over {protected['runs']} run(s) each. "
              f"Mean quality proxy: {summary['mean_auto_score']} / 100."), "",
             "| Case | Type | Runs passed | Mean score | Range | Failure types | Pass |", "|---|---|---|---|---|---|---|"]
    for case in protected["cases"]:
        passed_runs = sum(run["passed"] for run in case["runs"])
        failures = ", ".join(f"{name} ×{count}" for name, count in case["failure_types"].items()) or "none"
        lines.append(f"| {case['id']} | {case['draft_type'] or case['capability']} | {passed_runs}/{len(case['runs'])} "
                     f"| {case['score']} | {case['score_range'][0]}–{case['score_range'][1]} | {failures} "
                     f"| {case['passed']} |")
    if "comparison" in protected:
        lines.extend(["", *_comparison_lines(protected["comparison"])])
    for case in protected["cases"]:
        lines.extend(["", f"## {case['id']}", _fenced(case["title"]), "", "Source context:",
                      _fenced(case["context"] or "(no context supplied)"), "Candidate:",
                      _fenced(json.dumps(case["draft"], indent=2, ensure_ascii=False))])
        if case["grading"]:
            lines.extend(["Checks:", _fenced(json.dumps(case["grading"], indent=2))])
    (directory / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    review = {"schema_version": "draft-human-review.v1", "run_id": report["run_id"],
              "report_hash": fingerprint(protected), "rubric_version": RUBRIC_VERSION,
              "reviewer": None, "cases": [
                  {"id": case["id"], "status": "pending", "scores": dict.fromkeys(WEIGHTS),
                   "notes": "", "critical_failure": None} for case in report["cases"]]}
    (directory / "human-review.json").write_text(json.dumps(review, indent=2) + "\n", encoding="utf-8")
