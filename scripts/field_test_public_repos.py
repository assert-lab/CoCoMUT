#!/usr/bin/env python3
"""Run C4DG against a filtered sample of public Java repositories.

The script expects the repo-mining CSV used in this workspace and writes
resume-safe evidence under target/field-tests/public-repos/.
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
import time
from pathlib import Path


EXCLUDE = re.compile(
    r"(android|sample|demo|tutorial|interview|coding|leetcode|example|"
    r"bootcamp|course|learning|starter|template|awesome|guide|toy|kata|"
    r"algorithm|algorithms|exercise|workshop|hello[- ]?world)",
    re.I,
)
PREFER = re.compile(
    r"(parser|database|client|library|framework|tool|server|engine|plugin|"
    r"sdk|api|driver|testing|test|compiler|maven|gradle|search|crawler|"
    r"cache|queue|json|xml|http|jdbc|spring|monitor|metrics|logging|"
    r"security|auth|validation|serialization|workflow|stream|messaging|"
    r"redis|kafka|elastic)",
    re.I,
)


FIELDS = [
    "repo",
    "clone_status",
    "status",
    "build_system",
    "source_roots",
    "test_source_roots",
    "methods",
    "jsonl_rows",
    "duration_ms",
    "failure_codes",
    "note",
]


def is_english(text: str) -> bool:
    if not text:
        return True
    printable = sum(1 for char in text if char.isascii() and (char.isprintable() or char.isspace()))
    return printable / max(1, len(text)) >= 0.92


def int_field(row: dict[str, str], name: str) -> int:
    value = row.get(name) or "0"
    return int(float(value))


def select_repositories(csv_path: Path, limit: int) -> list[dict[str, str]]:
    rows: list[tuple[tuple[int, int, int, int, int], dict[str, str]]] = []
    with csv_path.open(newline="", encoding="utf-8", errors="replace") as handle:
        for row in csv.DictReader(handle):
            repo = row["repo"]
            description = row.get("description", "") or ""
            text = f"{repo} {description}"
            if row.get("language") != "Java":
                continue
            if row.get("filter_not_fork") != "True" or row.get("filter_not_archived") != "True":
                continue
            if row.get("has_readme") != "True":
                continue
            if not is_english(description):
                continue
            if EXCLUDE.search(text):
                continue
            size = int_field(row, "size_kb")
            stars = int_field(row, "stars")
            contributors = int_field(row, "contributor_count")
            quality = int_field(row, "repo_quality_score")
            if size <= 0 or size > 120_000:
                continue
            if stars < 300 or contributors < 5 or quality < 3:
                continue
            score = (
                1 if PREFER.search(text) else 0,
                quality,
                min(contributors, 50),
                min(stars, 5000),
                -size,
            )
            rows.append((score, row))
    rows.sort(key=lambda item: item[0], reverse=True)
    return [row for _, row in rows[:limit]]


def parse_report(log_path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    if not log_path.exists():
        return data
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line and not line.startswith("["):
            key, value = line.split("=", 1)
            data[key] = value
    return data


def append_tsv(path: Path, row: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\t".join(str(row.get(field, "")) for field in FIELDS))
        handle.write("\n")


def run_command(command: list[str], cwd: Path, log_path: Path, timeout: int, env: dict[str, str]) -> int | None:
    with log_path.open("w", encoding="utf-8") as log:
        try:
            result = subprocess.run(
                command,
                cwd=cwd,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                env=env,
                check=False,
            )
            return result.returncode
        except subprocess.TimeoutExpired:
            return None


def run_repo(root: Path, output_dir: Path, repo: str, timeout: int) -> dict[str, str]:
    safe = repo.replace("/", "__")
    checkout = output_dir / "checkouts" / safe
    logs = output_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    checkout.parent.mkdir(parents=True, exist_ok=True)

    if not checkout.exists():
        clone_status = run_command(
            ["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", str(checkout)],
            output_dir,
            logs / f"{safe}.clone.log",
            180,
            os.environ.copy(),
        )
        if clone_status is None:
            return {"repo": repo, "clone_status": "TIMEOUT", "status": "SKIPPED", "note": "clone timeout"}
        if clone_status != 0:
            return {"repo": repo, "clone_status": f"FAIL_{clone_status}", "status": "SKIPPED", "note": "clone failed"}

    env = os.environ.copy()
    env.setdefault("MAVEN_OPTS", "-Xmx2g")
    log_path = logs / f"{safe}.c4dg.log"
    start = time.time()
    status = run_command(
        [
            str(root / "bin" / "c4dg"),
            "extract",
            "--project",
            str(checkout),
            "--scope",
            "entry-points",
            "--call-graph",
            "none",
            "--output",
            "jsonl",
        ],
        root,
        log_path,
        timeout,
        env,
    )
    elapsed_ms = str(int((time.time() - start) * 1000))
    if status is None:
        return {"repo": repo, "clone_status": "OK", "status": "TIMEOUT", "duration_ms": elapsed_ms, "note": "analysis timeout"}

    data = parse_report(log_path)
    return {
        "repo": repo,
        "clone_status": "OK",
        "status": data.get("status", f"EXIT_{status}"),
        "build_system": data.get("phase_1_build_system", ""),
        "source_roots": data.get("phase_1_source_roots", ""),
        "test_source_roots": data.get("phase_1_test_source_roots", ""),
        "methods": data.get("phase_2_methods_identified", ""),
        "jsonl_rows": data.get("phase_5_jsonl_rows", ""),
        "duration_ms": data.get("duration_ms", elapsed_ms),
        "failure_codes": data.get("failure_codes", ""),
        "note": "" if status == 0 else f"exit {status}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=Path("../cleaned_mined_repos.csv"))
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--output-dir", type=Path, default=Path("target/field-tests/public-repos"))
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    csv_path = (root / args.csv).resolve() if not args.csv.is_absolute() else args.csv
    output_dir = (root / args.output_dir).resolve() if not args.output_dir.is_absolute() else args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    selected = select_repositories(csv_path, args.limit)
    repos_path = output_dir / "repos.tsv"
    with repos_path.open("w", encoding="utf-8") as handle:
        handle.write("repo\tstars\tsize_kb\tdescription\n")
        for row in selected:
            description = (row.get("description") or "").replace("\t", " ")[:180]
            handle.write(f"{row['repo']}\t{row['stars']}\t{row['size_kb']}\t{description}\n")

    results_path = output_dir / "results.tsv"
    if not results_path.exists():
        results_path.write_text("\t".join(FIELDS) + "\n", encoding="utf-8")
    completed = set()
    with results_path.open(encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if index > 0 and line.strip():
                completed.add(line.split("\t", 1)[0])

    print(f"selected={len(selected)} completed={len(completed)} output={output_dir}", flush=True)
    for index, row in enumerate(selected, 1):
        repo = row["repo"]
        if repo in completed:
            continue
        result = run_repo(root, output_dir, repo, args.timeout)
        append_tsv(results_path, result)
        print(
            f"{index}/{len(selected)} {repo} {result.get('status')} "
            f"methods={result.get('methods', '')} jsonl={result.get('jsonl_rows', '')} "
            f"codes={result.get('failure_codes', '')} {result.get('note', '')}",
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
