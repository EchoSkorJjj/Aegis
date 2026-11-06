#!/usr/bin/env python3
"""Reports one service's test coverage against its target, and fails below it.

    python3 testing/check_coverage.py <service> <report.xml>

Reads a JaCoCo report (the Java services) or a Cobertura one (pytest-cov), compares it with
testing/coverage-targets.json, prints a Markdown table and adds it to the GitHub job summary.
"""
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TARGETS = Path(__file__).with_name("coverage-targets.json")


def measured(report: Path) -> dict[str, float]:
    root = ET.parse(report).getroot()
    if root.tag == "report":  # JaCoCo: the totals are the counters directly under the root.
        counters = {c.get("type"): c for c in root.findall("counter")}

        def percent(kind: str) -> float:
            covered, missed = int(counters[kind].get("covered")), int(counters[kind].get("missed"))
            return 100.0 * covered / (covered + missed) if covered + missed else 100.0

        return {"line": percent("LINE"), "branch": percent("BRANCH")}
    if root.tag == "coverage":  # Cobertura.
        return {"line": 100.0 * float(root.get("line-rate")), "branch": 100.0 * float(root.get("branch-rate"))}
    raise ValueError(f"{report} is neither a JaCoCo nor a Cobertura report")


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    service, report = argv[1], Path(argv[2])
    targets = json.loads(TARGETS.read_text(encoding="utf-8"))
    if service not in targets:
        print(f"{service} has no target in {TARGETS.name}", file=sys.stderr)
        return 2
    if not report.is_file():
        print(f"No coverage report at {report}: did the tests run?", file=sys.stderr)
        return 2

    actual = measured(report)
    rows = [f"### Coverage: {service}", "", "| Measure | Covered | Target | |", "|---|---:|---:|---|"]
    failed = False
    for measure, target in targets[service].items():
        ok = actual[measure] >= target
        failed = failed or not ok
        rows.append(f"| {measure} | {actual[measure]:.1f}% | {target:g}% | {'met' if ok else '**below target**'} |")
    table = "\n".join(rows) + "\n"
    print(table)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(table)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
