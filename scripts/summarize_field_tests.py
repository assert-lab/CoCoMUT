#!/usr/bin/env python3
"""Summarize CoCoMUT public-repository field-test TSV evidence."""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path


def int_value(value: str) -> int:
    try:
        return int(float(value or "0"))
    except ValueError:
        return 0


def pct(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return "-"
    return f"{(100.0 * numerator / denominator):.2f}%"


def load_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def status_label(row: dict[str, str]) -> str:
    if row.get("status") != "SUCCESS":
        return row.get("status") or "UNKNOWN"
    methods = int_value(row.get("methods", ""))
    jsonl = int_value(row.get("jsonl_rows", ""))
    if methods > 0 and methods == jsonl:
        return "CLEAN_SUCCESS"
    return "DEGRADED_SUCCESS"


def write_report(rows: list[dict[str, str]], output: Path, title: str) -> None:
    labels = Counter(status_label(row) for row in rows)
    methods = sum(int_value(row.get("methods", "")) for row in rows if row.get("status") == "SUCCESS")
    jsonl = sum(int_value(row.get("jsonl_rows", "")) for row in rows if row.get("status") == "SUCCESS")
    retries = sum(1 for row in rows if row.get("retry_mode") and row.get("retry_mode") != "none")
    degraded = [
        row for row in rows
        if row.get("status") == "SUCCESS"
        and int_value(row.get("methods", "")) != int_value(row.get("jsonl_rows", ""))
    ]
    failures = [row for row in rows if row.get("status") != "SUCCESS"]

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        handle.write(f"# {title}\n\n")
        handle.write("## Summary\n\n")
        handle.write(f"- repositories tested: {len(rows)}\n")
        handle.write(f"- clean successes: {labels.get('CLEAN_SUCCESS', 0)}\n")
        handle.write(f"- degraded successes: {labels.get('DEGRADED_SUCCESS', 0)}\n")
        handle.write(f"- failed/timeouts/skipped: {len(failures)}\n")
        handle.write(f"- capped retry runs: {retries}\n")
        handle.write(f"- identified methods in successful runs: {methods}\n")
        handle.write(f"- generated JSONL rows in successful runs: {jsonl}\n")
        handle.write(f"- method-to-context coverage: {pct(jsonl, methods)}\n\n")

        handle.write("## Status Breakdown\n\n")
        handle.write("| Status | Count |\n| --- | ---: |\n")
        for label, count in labels.most_common():
            handle.write(f"| `{label}` | {count} |\n")
        handle.write("\n")

        handle.write("## Degraded Successes\n\n")
        handle.write("| Repository | Methods | JSONL rows | Coverage | Failure codes | Retry | Note |\n")
        handle.write("| --- | ---: | ---: | ---: | --- | --- | --- |\n")
        for row in sorted(degraded, key=lambda r: (int_value(r.get("jsonl_rows", "")) / max(1, int_value(r.get("methods", ""))))):
            m = int_value(row.get("methods", ""))
            j = int_value(row.get("jsonl_rows", ""))
            handle.write(
                f"| `{row.get('repo')}` | {m} | {j} | {pct(j, m)} | "
                f"`{row.get('failure_codes', '')}` | `{row.get('retry_mode', '')}` | {row.get('note', '')} |\n"
            )
        if not degraded:
            handle.write("| - | - | - | - | - | - | - |\n")
        handle.write("\n")

        handle.write("## Failures And Timeouts\n\n")
        handle.write("| Repository | Status | Clone | Retry | Note |\n")
        handle.write("| --- | --- | --- | --- | --- |\n")
        for row in failures:
            handle.write(
                f"| `{row.get('repo')}` | `{row.get('status')}` | `{row.get('clone_status')}` | "
                f"`{row.get('retry_mode', '')}` | {row.get('note', '')} |\n"
            )
        if not failures:
            handle.write("| - | - | - | - | - |\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path, help="results.tsv produced by field_test_public_repos.py")
    parser.add_argument("--output", type=Path, default=Path("FIELD_TEST_RESULTS_EXPANDED.md"))
    parser.add_argument("--title", default="Expanded Field Test Results")
    args = parser.parse_args()

    rows = load_rows(args.results)
    write_report(rows, args.output, args.title)
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
