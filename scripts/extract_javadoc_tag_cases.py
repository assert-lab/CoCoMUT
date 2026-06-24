#!/usr/bin/env python3
"""Extract @see and {@inheritDoc} cases from CoCoMUT field-test JSONL outputs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


FIELDS = [
    "repo",
    "method_uri",
    "method_name",
    "qualified_name",
    "source_set",
    "source_backend_mode",
    "uses_inheritdoc",
    "inheritdoc_resolution",
    "inherited_candidate_count",
    "see",
    "inline_links",
    "javadoc_excerpt",
    "inherited_javadoc_excerpt",
]


def truncate(value: str, limit: int) -> str:
    value = " ".join((value or "").split())
    if len(value) <= limit:
        return value
    return value[: max(0, limit - 3)] + "..."


def iter_repo_jsonl(output_dir: Path):
    checkouts = output_dir / "checkouts"
    if not checkouts.is_dir():
        return
    for checkout in sorted(checkouts.iterdir()):
        for jsonl in sorted(checkout.glob("method_contexts*.jsonl")):
            if jsonl.is_file() and jsonl.name != "method_context_failures.jsonl":
                yield checkout.name.replace("__", "/", 1), jsonl


def extract_cases(output_dir: Path, excerpt_chars: int):
    for repo, jsonl in iter_repo_jsonl(output_dir):
        with jsonl.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                javadoc = row.get("javadoc_metadata") or {}
                see = javadoc.get("see") or []
                uses_inheritdoc = bool(javadoc.get("uses_inheritdoc"))
                if not see and not uses_inheritdoc:
                    continue

                mut = row.get("MUT") or {}
                metadata = row.get("metadata") or {}
                candidates = javadoc.get("inherited_javadoc_candidates") or []
                yield {
                    "repo": repo,
                    "method_uri": mut.get("method_uri", ""),
                    "method_name": mut.get("method_name", ""),
                    "qualified_name": mut.get("qualified_name", ""),
                    "source_set": mut.get("source_set", ""),
                    "source_backend_mode": metadata.get("source_backend_mode", ""),
                    "uses_inheritdoc": str(uses_inheritdoc).lower(),
                    "inheritdoc_resolution": javadoc.get("inheritdoc_resolution", ""),
                    "inherited_candidate_count": len(candidates),
                    "see": json.dumps(see, ensure_ascii=False),
                    "inline_links": json.dumps(javadoc.get("inline_links") or [], ensure_ascii=False),
                    "javadoc_excerpt": truncate(mut.get("javadoc", ""), excerpt_chars),
                    "inherited_javadoc_excerpt": truncate(candidates[0] if candidates else "", excerpt_chars),
                }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Field-test output directory containing checkouts/*/method_contexts*.jsonl.")
    parser.add_argument("--csv", type=Path, default=None,
                        help="Output CSV path. Defaults to <output-dir>/javadoc_tag_cases.csv.")
    parser.add_argument("--excerpt-chars", type=int, default=800)
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    csv_path = args.csv.resolve() if args.csv else output_dir / "javadoc_tag_cases.csv"
    csv_path.parent.mkdir(parents=True, exist_ok=True)

    count = 0
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        for case in extract_cases(output_dir, args.excerpt_chars):
            writer.writerow(case)
            count += 1

    print(f"wrote={csv_path}")
    print(f"cases={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
