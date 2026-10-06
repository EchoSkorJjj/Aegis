"""python -m ai_service.evaluation --provider golden --output evaluation-results/run"""

import argparse
import asyncio
import json
import math
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
        report = await evaluate(corpus, client, config, delay_seconds=args.delay_seconds)
    finally:
        await client.aclose()
    if args.baseline:
        report["comparison"] = compare(report, json.loads(args.baseline.read_text(encoding="utf-8")),
                                       args.max_score_drop)
    write_report(report, args.output)
    summary = report["summary"]
    print(f"{report['mode']}: {summary['passed']}/{summary['cases']} cases passed; "
          f"quality proxy {summary['mean_auto_score']}/100. Human review pending.")
    print(f"Reports: {args.output / 'report.json'}, {args.output / 'report.md'}, "
          f"{args.output / 'human-review.json'}")
    return int(summary["failed"] > 0 or not report.get("comparison", {}).get("passed", True))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate synthetic draft narratives; defaults to offline replay.")
    parser.add_argument("--provider", choices=("golden", "stub", "groq"), default="golden")
    parser.add_argument("--model", help="Groq model override; never used by offline runs")
    parser.add_argument("--corpus", type=Path, help="Versioned corpus JSON; packaged corpus by default")
    parser.add_argument("--case", action="append", help="Select case ID; repeat to select several")
    parser.add_argument("--output", type=Path, default=Path("evaluation-results/latest"))
    parser.add_argument("--baseline", type=Path, help="Prior report.json for the same corpus selection/rubric")
    parser.add_argument("--max-score-drop", type=nonnegative, default=0)
    parser.add_argument("--delay-seconds", type=nonnegative, default=0,
                        help="Pause between live cases; configured rate/token budgets remain enforced")
    args = parser.parse_args(argv)
    try:
        return asyncio.run(run(args))
    except (OSError, ValueError, KeyError, TypeError):
        # Corpus/provider/baseline exceptions may contain supplied text; never echo them.
        print("Evaluation configuration failed: check corpus, case selection, provider and baseline.",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
