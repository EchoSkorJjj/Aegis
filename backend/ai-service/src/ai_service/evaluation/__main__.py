"""python -m ai_service.evaluation --provider golden --output evaluation-results/run"""

import argparse
import asyncio
import json
import math
import os
import sys
from pathlib import Path

from ai_service.core.config import Settings
from ai_service.evaluation.cases import load_corpus
from ai_service.evaluation.runner import (
    GoldenClient,
    compare,
    evaluate,
    offline_settings,
    write_report,
)
from ai_service.llm.groq import GroqLlmClient
from ai_service.llm.stub import StubLlmClient


def positive(value: str) -> int:
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("Must be at least 1")
    return number


def nonnegative(value: str) -> float:
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise argparse.ArgumentTypeError("Must be a finite nonnegative number")
    return number


async def run(args) -> int:
    corpus = load_corpus(args.corpus)
    if args.case:
        wanted = set(args.case)
        if not wanted.issubset({case.id for case in corpus.cases}):
            raise ValueError("Unknown selected case")
        corpus = corpus.model_copy(update={"cases": [case for case in corpus.cases if case.id in wanted]})
    if args.provider == "groq":
        overrides = {"groq_model": args.model} if args.model else {}
        config = Settings(_env_file=None, llm_provider="groq", **overrides)
        client = GroqLlmClient(config)
    else:
        if args.model:
            raise ValueError("Model override is for Groq only")
        config = offline_settings()
        client = GoldenClient() if args.provider == "golden" else StubLlmClient()
    try:
        report = await evaluate(corpus, client, config, delay_seconds=args.delay_seconds, runs=args.runs)
    finally:
        await client.aclose()
    if args.baseline:
        report["comparison"] = compare(report, json.loads(args.baseline.read_text(encoding="utf-8")))
    write_report(report, args.output)
    summary = report["summary"]
    print(f"{report['mode']}: {summary['passed']}/{summary['cases']} cases passed over {args.runs} run(s) each; "
          f"quality proxy {summary['mean_auto_score']}/100. Human review pending.")
    print(f"Reports: {args.output / 'report.json'}, {args.output / 'report.md'}, "
          f"{args.output / 'human-review.json'}")
    # Against a baseline, only a regression fails the run; on its own, any case below its threshold does.
    if "comparison" in report:
        return int(not report["comparison"]["passed"])
    return int(summary["failed"] > 0)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate synthetic draft narratives; defaults to offline replay.")
    parser.add_argument("--provider", choices=("golden", "stub", "groq"), default="golden")
    parser.add_argument("--model", help="Groq model override; never used by offline runs")
    parser.add_argument("--corpus", type=Path, help="Versioned corpus JSON; packaged corpus by default")
    parser.add_argument("--case", action="append", help="Select case ID; repeat to select several")
    parser.add_argument("--output", type=Path, default=Path("evaluation-results/latest"))
    parser.add_argument("--baseline", type=Path, help="Prior report.json for the same corpus selection/rubric")
    parser.add_argument("--runs", type=positive, default=1,
                        help="Runs per case; a case passes on its pass rate (rubric.RUN_PASS_RATE)")
    parser.add_argument("--delay-seconds", type=nonnegative, default=0,
                        help="Pause between live cases; configured rate/token budgets remain enforced")
    args = parser.parse_args(argv)
    try:
        code = asyncio.run(run(args))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as job:
                job.write((args.output / "summary.md").read_text(encoding="utf-8"))
        return code
    except (OSError, ValueError, KeyError, TypeError):
        # Corpus/provider/baseline exceptions may contain supplied text; never echo them.
        print("Evaluation configuration failed: check corpus, case selection, provider and baseline.",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
