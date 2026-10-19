"""Run the actual guarded drafting pipeline and export reproducible E14 inputs."""

import asyncio
import json
import platform
import re
import time
from datetime import UTC, datetime
from importlib.resources import files
from pathlib import Path
from uuid import uuid4

from ai_service.capabilities._guardrails import redact
from ai_service.capabilities.assess import assess
from ai_service.capabilities.draft import draft
from ai_service.capabilities.look_for import look_for
from ai_service.core.config import Settings
from ai_service.core.errors import BadGateway, BadRequest, ServiceUnavailable
from ai_service.evaluation.cases import Corpus, fingerprint
from ai_service.evaluation.rubric import (
    MIN_QUALITY_DIMENSION,
    PASS_SCORE,
    RUBRIC_VERSION,
    WEIGHTS,
    grade,
    grade_assessment,
    grade_look_for,
)
from ai_service.llm.base import LlmResult
from ai_service.llm.structured import StructuredGenerator
from ai_service.prompts import build_prompt
from ai_service.schemas import DraftRequest

REPORT_VERSION = "draft-evaluation.v1"


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


async def evaluate(corpus: Corpus, client, config: Settings, *, delay_seconds: float = 0) -> dict:
    tracked = TrackingClient(client)
    engine = StructuredGenerator(config, tracked)
    results, templates = [], {}
    for index, case in enumerate(corpus.cases):
        if index and delay_seconds:
            await asyncio.sleep(delay_seconds)
        if isinstance(client, GoldenClient):
            client.reference = case.reference
        prompt = (build_prompt(case.capability, [("A", case.advisory)]) if case.capability != "draft"
                  else build_prompt("draft", [("C", case.context)], draft_type=case.draft_type))
        resources = files("ai_service.prompts")
        template = prompt.template_id.rsplit(".", 1)[0]
        templates[prompt.template_id] = fingerprint([
            resources.joinpath(f"{template}.system.md").read_text(encoding="utf-8").replace("\r\n", "\n"),
            resources.joinpath(f"{template}.user.md").read_text(encoding="utf-8").replace("\r\n", "\n"),
        ])
        before = (tracked.calls, tracked.input_tokens, tracked.output_tokens)
        started = time.perf_counter()
        candidate, grading = None, None
        try:
            if case.capability == "assess":
                candidate = await assess(case.assessment_request(), engine)
                grading = grade_assessment(case, candidate)
            elif case.capability == "look_for":
                candidate = await look_for(case.look_for_request(), engine)
                grading = grade_look_for(case, candidate)
            else:
                candidate = await draft(DraftRequest(finding_id=case.id, draft_type=case.draft_type,
                                                   context=case.context), engine)
                grading = grade(case, candidate)
            outcome = "draft"
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
        elapsed = round((time.perf_counter() - started) * 1000, 2)
        calls = tracked.calls - before[0]
        passed = (outcome == "blocked" and calls == 0 if case.expected == "blocked"
                  else outcome == "draft" and grading["passed"])
        results.append({
            "id": case.id, "title": case.title, "capability": case.capability, "draft_type": case.draft_type,
            "tags": case.tags,
            "expected": case.expected, "outcome": outcome, "passed": passed,
            "grading": grading, "draft": _protected(candidate.model_dump()) if candidate else None,
            "context": redact(case.context if case.capability == "draft" else _assessment_source(case)),
            "latency_ms": elapsed, "provider_calls": calls,
            "input_tokens": tracked.input_tokens - before[1],
            "output_tokens": tracked.output_tokens - before[2],
        })
    quality = [result for result in results if result["expected"] == "draft"]
    blocked = [result for result in results if result["expected"] == "blocked"]
    generated = [result for result in quality if result["grading"] is not None]

    def rate(items):
        return round(sum(item["passed"] for item in items) / len(items), 4) if items else None

    return {
        "schema_version": REPORT_VERSION, "run_id": str(uuid4()),
        "generated_at": datetime.now(UTC).isoformat(), "python_version": platform.python_version(),
        "provider": client.provider, "model": client.model,
        "mode": "reference_replay" if isinstance(client, GoldenClient) else "provider_evaluation",
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
            "mean_generated_auto_score": (round(sum(result["grading"]["auto_score"] for result in generated)
                                                 / len(generated), 2) if generated else None),
            "mean_auto_score": (round(sum(result["grading"]["auto_score"] if result["grading"] else 0
                                           for result in quality) / len(quality), 2) if quality else None),
            "guardrail_cases": len(blocked), "guardrail_pass_rate": rate(blocked),
            "provider_calls": tracked.calls, "input_tokens": tracked.input_tokens,
            "output_tokens": tracked.output_tokens,
            "outcomes": {outcome: sum(item["outcome"] == outcome for item in results)
                         for outcome in sorted({item["outcome"] for item in results})},
            "by_draft_type": {
                kind: {"cases": len(items := [item for item in results if item["draft_type"] == kind]),
                       "passed": sum(item["passed"] for item in items)}
                for kind in ("risk_ticket", "upgrade", "memo")
            },
            "by_capability": {
                kind: {"cases": len(items := [item for item in results if item["capability"] == kind]),
                       "passed": sum(item["passed"] for item in items)}
                for kind in ("draft", "assess", "look_for")
            },
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


def compare(report: dict, baseline: dict, max_score_drop: float = 0) -> dict:
    for key in ("schema_version", "corpus_hash", "rubric_hash"):
        if report[key] != baseline.get(key):
            raise ValueError("Baseline must use the same report schema, corpus and rubric")
    current = {case["id"]: case for case in report["cases"]}
    previous = {case["id"]: case for case in baseline["cases"]}
    if current.keys() != previous.keys():
        raise ValueError("Baseline must include the same selected cases")
    regressions = []
    for key, case in current.items():
        old = previous[key]
        old_score = old["grading"]["auto_score"] if old["grading"] else 0
        new_score = case["grading"]["auto_score"] if case["grading"] else 0
        if (old["passed"] and not case["passed"]) or old_score - new_score > max_score_drop:
            regressions.append(key)
    return {"baseline_run_id": baseline["run_id"], "max_score_drop": max_score_drop,
            "regressions": regressions, "passed": not regressions}


def _fenced(text: str) -> str:
    # Longer than any backtick run in the text, so exported model text cannot close the code block.
    fence = "`" * max(3, max((len(run) + 1 for run in re.findall(r"`+", text)), default=3))
    return f"{fence}text\n{text}\n{fence}\n"


def write_report(report: dict, directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    protected = _protected(report)
    (directory / "report.json").write_text(json.dumps(protected, indent=2, ensure_ascii=False) + "\n",
                                           encoding="utf-8")
    summary = protected["summary"]
    lines = ["# Aegis draft evaluation", "", f"Mode: {protected['mode']}.",
             "Provider/model:", _fenced(f"{protected['provider']} / {protected['model']}"),
             "Automated scores are rule-based proxies. Human semantic review is pending.",
             "Reference replay is harness calibration, not measured live-model quality.", "",
             (f"Cases: {summary['passed']}/{summary['cases']} passed. "
              f"Mean quality proxy: {summary['mean_auto_score']} / 100."), "",
             "| Case | Type | Outcome | Score | Pass |", "|---|---|---|---|---|"]
    for case in protected["cases"]:
        score = case["grading"]["auto_score"] if case["grading"] else "n/a"
        lines.append(f"| {case['id']} | {case['draft_type'] or case['capability']} | {case['outcome']} | {score} "
                     f"| {case['passed']} |")
    if "comparison" in protected:
        lines.extend(["", "Baseline regressions: " + ", ".join(protected["comparison"]["regressions"] or ["none"])])
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
