#!/usr/bin/env python3
"""Run the reduced 20-project CoCoMUT evaluation.

The runner is intentionally conservative: it validates the subject table,
records the environment, continues after per-repository failures, and writes
machine-readable TSV files for the two paper RQs.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import platform
import shutil
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any


REPOSITORY_COLUMNS = [
    "repo",
    "build_system",
    "commit_sha",
    "size_bin",
    "module_shape",
    "clone_status",
    "checkout_status",
    "cocomut_exit_code",
    "status",
    "failure_codes",
    "duration_ms",
    "phase_1_build_system",
    "phase_1_java_version",
    "phase_1_compiles",
    "phase_1_compile_status",
    "phase_1_build_attempted",
    "phase_1_build_succeeded",
    "phase_1_build_timed_out",
    "phase_1_bytecode_available",
    "phase_1_bytecode_origin",
    "phase_1_analysis_can_proceed",
    "source_files_discovered",
    "source_files_parsed",
    "source_files_failed",
    "phase_2_analysis_universe_methods",
    "phase_2_methods_identified",
    "phase_4_contexts_extracted",
    "phase_5_jsonl_rows",
    "jsonl_files",
    "jsonl_parseable_rows",
    "jsonl_malformed_rows",
    "row_count_matches_contexts",
    "phase_3_available",
    "phase_3_call_graph_artifact_exists",
    "phase_3_focal_methods_matched_to_bytecode",
    "phase_3_call_edges_generated",
    "phase_5_call_edges_serialized",
]


CALL_EDGE_COLUMNS = [
    "repo",
    "build_system",
    "total_edges",
    "edges_with_target_uri",
    "edges_with_method_uri",
    "source_join_rate",
    "unresolved_edges",
    "ambiguous_edges",
    "candidate_edges",
    "project_method_edges",
    "unresolved_project_method_edges",
    "jdk_method_edges",
    "external_method_edges",
    "synthetic_or_compiler_edges",
    "invokedynamic_edges",
    "bytecode_method_edges",
    "missing_target_kind_edges",
    "resolution_counts_json",
    "target_kind_counts_json",
    "unresolved_reason_counts_json",
]


FAILURE_COLUMNS = [
    "repo",
    "build_system",
    "status",
    "failed_at_phase",
    "failure_codes",
    "phase_1_compile_status",
    "phase_1_error",
    "phase_2_error",
    "phase_3_error",
    "phase_4_error",
    "phase_5_error",
    "note",
]


REQUIRED_SUBJECT_COLUMNS = ["repo", "build_system", "commit_sha", "size_bin", "module_shape", "notes"]
ALLOWED_BUILD_SYSTEMS = {"maven", "gradle"}
ALLOWED_SIZE_BINS = {"small", "medium", "large"}
ALLOWED_MODULE_SHAPES = {"single-module", "multi-module", "unknown"}


def main() -> int:
    args = parse_args()
    subjects = load_subjects(args.subjects)
    if args.repo:
        subjects = [row for row in subjects if row["repo"] == args.repo]
        if not subjects:
            raise SystemExit(f"requested repo not in subjects.csv: {args.repo}")
    if args.limit is not None:
        subjects = subjects[: args.limit]

    output_root = args.output_root.resolve()
    results_dir = args.results_dir.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    write_environment(args, results_dir.parent / "environment.json")

    existing = completed_repos(results_dir / "repository-results.tsv") if args.resume else set()
    repository_rows: list[dict[str, Any]] = []
    call_edge_rows: list[dict[str, Any]] = []
    failure_rows: list[dict[str, Any]] = []

    for subject in subjects:
        repo = subject["repo"]
        if repo in existing and not args.force:
            print(f"[skip] {repo} already complete", flush=True)
            continue
        print(f"[run] {repo}", flush=True)
        repo_result, call_result, failure_result = run_subject(subject, args, output_root)
        repository_rows.append(repo_result)
        call_edge_rows.append(call_result)
        if failure_result:
            failure_rows.append(failure_result)
        append_tsv(results_dir / "repository-results.tsv", REPOSITORY_COLUMNS, [repo_result])
        append_tsv(results_dir / "call-edge-results.tsv", CALL_EDGE_COLUMNS, [call_result])
        if failure_result:
            append_tsv(results_dir / "failures.tsv", FAILURE_COLUMNS, [failure_result])

    # Ensure files exist even if every row was skipped.
    for path, columns in [
        (results_dir / "repository-results.tsv", REPOSITORY_COLUMNS),
        (results_dir / "call-edge-results.tsv", CALL_EDGE_COLUMNS),
        (results_dir / "failures.tsv", FAILURE_COLUMNS),
    ]:
        if not path.exists():
            write_tsv(path, columns, [])
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subjects", type=Path, default=Path("evaluation/subjects.csv"))
    parser.add_argument("--output-root", type=Path, default=Path("evaluation/outputs"))
    parser.add_argument("--results-dir", type=Path, default=Path("evaluation/results"))
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--compile-timeout", type=int, default=300)
    parser.add_argument("--heap-gb", type=int, default=4)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--repo")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--cocomut-command", default="./bin/cocomut")
    return parser.parse_args()


def load_subjects(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        raise SystemExit(f"subjects.csv does not exist: {path}")
    with path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        missing = [column for column in REQUIRED_SUBJECT_COLUMNS if column not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"subjects.csv missing required columns: {missing}")
        rows = list(reader)
    counts = Counter()
    for i, row in enumerate(rows, 1):
        repo = row.get("repo", "").strip()
        build_system = row.get("build_system", "").strip()
        commit_sha = row.get("commit_sha", "").strip()
        size_bin = row.get("size_bin", "").strip()
        module_shape = row.get("module_shape", "").strip()
        if not repo:
            raise SystemExit(f"subjects.csv row {i} missing repo")
        if build_system not in ALLOWED_BUILD_SYSTEMS:
            raise SystemExit(f"{repo}: build_system must be maven or gradle")
        if not commit_sha:
            raise SystemExit(f"{repo}: commit_sha missing")
        if size_bin not in ALLOWED_SIZE_BINS:
            raise SystemExit(f"{repo}: invalid size_bin {size_bin}")
        if module_shape not in ALLOWED_MODULE_SHAPES:
            raise SystemExit(f"{repo}: invalid module_shape {module_shape}")
        counts[build_system] += 1
    if counts["maven"] != 10 or counts["gradle"] != 10:
        raise SystemExit(f"subjects.csv must contain exactly 10 Maven and 10 Gradle repos, found {dict(counts)}")
    return rows


def write_environment(args: argparse.Namespace, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    env = {
        "date": dt.datetime.now(dt.timezone.utc).isoformat(),
        "machine": {
            "os": platform.platform(),
            "cpu_count": os.cpu_count() or 0,
            "memory_gb": memory_gb(),
        },
        "java": {
            "java_version": command_version(["java", "-version"]),
            "javac_version": command_version(["javac", "-version"]),
        },
        "tools": {
            "git": command_version(["git", "--version"]),
            "maven": command_version(["mvn", "-version"]),
            "gradle": command_version(["gradle", "--version"]),
        },
        "cocomut": {
            "repo_commit": os.environ.get("COCOMUT_REPO_COMMIT") or command_text(["git", "rev-parse", "HEAD"]),
            "command": args.cocomut_command,
            "call_graph": "rta",
            "source_set": "main",
            "scope": "all",
        },
        "limits": {
            "repo_timeout_seconds": args.timeout,
            "compile_timeout_seconds": args.compile_timeout,
            "heap_gb": args.heap_gb,
        },
    }
    path.write_text(json.dumps(env, indent=2, sort_keys=True) + "\n")


def run_subject(subject: dict[str, str], args: argparse.Namespace, output_root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any] | None]:
    repo = subject["repo"]
    build_system = subject["build_system"]
    safe_name = repo.replace("/", "__")
    repo_root = output_root / safe_name
    checkout_dir = repo_root / "checkout"
    cocomut_output = repo_root / "cocomut_output"
    log_dir = repo_root / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    clone_status = clone_or_reuse(repo, checkout_dir, args.force, log_dir)
    checkout_status = checkout_commit(checkout_dir, subject["commit_sha"], log_dir) if clone_status == "OK" else "SKIPPED"

    command = [
        args.cocomut_command,
        "--project",
        str(checkout_dir),
        "--scope",
        "all",
        "--source-set",
        "main",
        "--call-graph",
        "rta",
        "--externally-sandboxed-build",
        "--output-dir",
        str(cocomut_output),
    ]

    report: dict[str, Any] = {}
    exit_code: str | int = ""
    timed_out = False
    duration_ms = ""
    if clone_status == "OK" and checkout_status == "OK":
        if args.force and cocomut_output.exists():
            shutil.rmtree(cocomut_output)
        cocomut_output.mkdir(parents=True, exist_ok=True)
        start = time.monotonic()
        exit_code, timed_out = run_logged(command, log_dir / "cocomut.log", args.timeout, args)
        duration_ms = int((time.monotonic() - start) * 1000)
        report = read_extraction_report(cocomut_output)

    jsonl_files = method_context_jsonl_files(cocomut_output)
    jsonl_metrics, call_metrics = parse_jsonl_edges(jsonl_files)

    row = repository_row(subject, clone_status, checkout_status, exit_code, timed_out, duration_ms,
                         report, jsonl_files, jsonl_metrics)
    call_row = call_edge_row(repo, build_system, call_metrics)
    failure_row = failure_row_from(row, report, subject.get("notes", ""))
    return row, call_row, failure_row


def method_context_jsonl_files(cocomut_output: Path) -> list[Path]:
    if not cocomut_output.exists():
        return []
    files = sorted(cocomut_output.rglob("method_contexts*.jsonl"))
    if files:
        return files
    return sorted(
        path for path in cocomut_output.rglob("*.jsonl")
        if not path.name.startswith("failed_")
    )


def clone_or_reuse(repo: str, checkout_dir: Path, force: bool, log_dir: Path) -> str:
    if force and checkout_dir.exists():
        shutil.rmtree(checkout_dir)
    if checkout_dir.exists():
        return "OK"
    checkout_dir.parent.mkdir(parents=True, exist_ok=True)
    code, timed_out = run_logged(
        ["git", "clone", f"https://github.com/{repo}.git", str(checkout_dir)],
        log_dir / "clone.log",
        timeout=600,
    )
    return "TIMEOUT" if timed_out else ("OK" if code == 0 else f"EXIT_{code}")


def checkout_commit(checkout_dir: Path, commit_sha: str, log_dir: Path) -> str:
    code, timed_out = run_logged(["git", "-C", str(checkout_dir), "checkout", commit_sha],
                                 log_dir / "checkout.log", timeout=120)
    return "TIMEOUT" if timed_out else ("OK" if code == 0 else f"EXIT_{code}")


def run_logged(command: list[str], log_path: Path, timeout: int, args: argparse.Namespace | None = None) -> tuple[int, bool]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    heap = args.heap_gb if args else 4
    compile_timeout = args.compile_timeout if args else 300
    env["MAVEN_OPTS"] = f"-Xmx{heap}g"
    env["JAVA_TOOL_OPTIONS"] = f"-Xmx{heap}g"
    env["COCOMUT_COMPILE_TIMEOUT_SECONDS"] = str(compile_timeout)
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        log.write("$ " + " ".join(command) + "\n\n")
        log.flush()
        try:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                       env=env, timeout=timeout, check=False)
            return completed.returncode, False
        except subprocess.TimeoutExpired as exc:
            log.write(f"\n[TIMEOUT] after {timeout}s: {exc}\n")
            return 124, True


def read_extraction_report(output_dir: Path) -> dict[str, Any]:
    candidates = sorted(output_dir.rglob("extraction_report.json"))
    if not candidates:
        return {}
    try:
        return json.loads(candidates[0].read_text(encoding="utf-8", errors="replace"))
    except Exception as exc:
        return {"status": "ERROR", "phase_5_error": f"could not parse extraction_report.json: {exc}"}


def parse_jsonl_edges(jsonl_files: list[Path]) -> tuple[dict[str, Any], dict[str, Any]]:
    parseable = malformed = 0
    edge_rows: list[dict[str, Any]] = []
    for path in jsonl_files:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                    parseable += 1
                except json.JSONDecodeError:
                    malformed += 1
                    continue
                edge_rows.extend(row.get("callers", []) or [])
                edge_rows.extend(row.get("callees", []) or [])

    total = len(edge_rows)
    with_target = sum(1 for edge in edge_rows if edge.get("target_uri"))
    with_method = sum(1 for edge in edge_rows if edge.get("method_uri"))
    target_kind_counts = Counter(edge.get("target_kind") or "missing" for edge in edge_rows)
    resolution_counts = Counter(edge.get("resolution") or "missing" for edge in edge_rows)
    unresolved_reason_counts = Counter(edge.get("unresolved_reason") or "" for edge in edge_rows if edge.get("unresolved_reason"))
    candidate_edges = sum(1 for edge in edge_rows if edge.get("candidate_method_uris"))
    ambiguous_edges = sum(1 for edge in edge_rows
                          if "ambiguous" in str(edge.get("resolution") or "").lower()
                          or edge.get("candidate_method_uris"))
    unresolved_edges = sum(1 for edge in edge_rows
                           if not edge.get("method_uri")
                           or "unresolved" in str(edge.get("resolution") or "").lower()
                           or str(edge.get("target_kind") or "").startswith("unresolved"))

    metrics = {
        "jsonl_parseable_rows": parseable,
        "jsonl_malformed_rows": malformed,
    }
    call_metrics = {
        "total_edges": total,
        "edges_with_target_uri": with_target,
        "edges_with_method_uri": with_method,
        "source_join_rate": rate(with_method, with_target),
        "unresolved_edges": unresolved_edges,
        "ambiguous_edges": ambiguous_edges,
        "candidate_edges": candidate_edges,
        "project_method_edges": target_kind_counts.get("project_method", 0),
        "unresolved_project_method_edges": target_kind_counts.get("unresolved_project_method", 0),
        "jdk_method_edges": target_kind_counts.get("jdk_method", 0),
        "external_method_edges": target_kind_counts.get("external_method", 0),
        "synthetic_or_compiler_edges": target_kind_counts.get("synthetic_or_compiler_method", 0),
        "invokedynamic_edges": target_kind_counts.get("invokedynamic_method", 0),
        "bytecode_method_edges": target_kind_counts.get("bytecode_method", 0),
        "missing_target_kind_edges": target_kind_counts.get("missing", 0),
        "resolution_counts_json": json.dumps(dict(sorted(resolution_counts.items())), sort_keys=True),
        "target_kind_counts_json": json.dumps(dict(sorted(target_kind_counts.items())), sort_keys=True),
        "unresolved_reason_counts_json": json.dumps(dict(sorted(unresolved_reason_counts.items())), sort_keys=True),
    }
    return metrics, call_metrics


def repository_row(subject: dict[str, str],
                   clone_status: str,
                   checkout_status: str,
                   exit_code: str | int,
                   timed_out: bool,
                   duration_ms: str | int,
                   report: dict[str, Any],
                   jsonl_files: list[Path],
                   jsonl_metrics: dict[str, Any]) -> dict[str, Any]:
    row: dict[str, Any] = {column: "" for column in REPOSITORY_COLUMNS}
    row.update({key: subject.get(key, "") for key in ["repo", "build_system", "commit_sha", "size_bin", "module_shape"]})
    row["clone_status"] = clone_status
    row["checkout_status"] = checkout_status
    row["cocomut_exit_code"] = "TIMEOUT" if timed_out else exit_code
    row["duration_ms"] = report.get("duration_ms", duration_ms)
    status = report.get("status")
    if not status:
        status = "TIMEOUT" if timed_out else (f"EXIT_{exit_code}" if exit_code not in ("", 0) else "ERROR")
    row["status"] = status
    for key in REPOSITORY_COLUMNS:
        if key in report:
            row[key] = format_value(report[key])
    row["failure_codes"] = format_value(report.get("failure_codes", ""))
    row["jsonl_files"] = len(jsonl_files)
    row["jsonl_parseable_rows"] = jsonl_metrics["jsonl_parseable_rows"]
    row["jsonl_malformed_rows"] = jsonl_metrics["jsonl_malformed_rows"]
    expected_rows = as_int(row.get("phase_5_jsonl_rows"))
    context_rows = as_int(row.get("phase_4_contexts_extracted"))
    row["row_count_matches_contexts"] = ""
    if expected_rows is not None:
        row["row_count_matches_contexts"] = str(expected_rows == jsonl_metrics["jsonl_parseable_rows"]).lower()
    if expected_rows is not None and context_rows is not None:
        row["row_count_matches_contexts"] = str(
            expected_rows == context_rows == jsonl_metrics["jsonl_parseable_rows"]
        ).lower()
    return row


def call_edge_row(repo: str, build_system: str, metrics: dict[str, Any]) -> dict[str, Any]:
    row: dict[str, Any] = {column: "" for column in CALL_EDGE_COLUMNS}
    row["repo"] = repo
    row["build_system"] = build_system
    row.update(metrics)
    return row


def failure_row_from(row: dict[str, Any], report: dict[str, Any], note: str) -> dict[str, Any] | None:
    failure_codes = str(row.get("failure_codes") or "")
    malformed = as_int(row.get("jsonl_malformed_rows")) or 0
    mismatch = row.get("row_count_matches_contexts") == "false"
    status = str(row.get("status") or "")
    has_failure_code = failure_codes not in {"", "[NONE]", "NONE", "[]"}
    if status == "SUCCESS" and not has_failure_code and malformed == 0 and not mismatch:
        return None
    phase = ""
    for i in range(1, 6):
        if report.get(f"phase_{i}_error"):
            phase = f"phase_{i}"
            break
    return {
        "repo": row["repo"],
        "build_system": row["build_system"],
        "status": status,
        "failed_at_phase": phase,
        "failure_codes": failure_codes,
        "phase_1_compile_status": row.get("phase_1_compile_status", ""),
        "phase_1_error": report.get("phase_1_error", ""),
        "phase_2_error": report.get("phase_2_error", ""),
        "phase_3_error": report.get("phase_3_error", ""),
        "phase_4_error": report.get("phase_4_error", ""),
        "phase_5_error": report.get("phase_5_error", ""),
        "note": note,
    }


def completed_repos(path: Path) -> set[str]:
    if not path.exists():
        return set()
    with path.open(newline="") as handle:
        return {row["repo"] for row in csv.DictReader(handle, delimiter="\t")}


def append_tsv(path: Path, columns: list[str], rows: list[dict[str, Any]]) -> None:
    exists = path.exists()
    with path.open("a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, delimiter="\t", extrasaction="ignore")
        if not exists:
            writer.writeheader()
        writer.writerows(rows)


def write_tsv(path: Path, columns: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def format_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, dict)):
        return json.dumps(value, sort_keys=True)
    return str(value)


def as_int(value: Any) -> int | None:
    if value in ("", None):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def rate(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return ""
    return f"{numerator / denominator:.6f}"


def memory_gb() -> int:
    try:
        text = Path("/proc/meminfo").read_text()
        for line in text.splitlines():
            if line.startswith("MemTotal:"):
                kb = int(line.split()[1])
                return round(kb / 1024 / 1024)
    except Exception:
        pass
    return 0


def command_text(command: list[str]) -> str:
    try:
        return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()
    except Exception as exc:
        return f"unavailable: {exc}"


def command_version(command: list[str]) -> str:
    text = command_text(command)
    return "\\n".join(text.splitlines()[:4])


if __name__ == "__main__":
    raise SystemExit(main())
