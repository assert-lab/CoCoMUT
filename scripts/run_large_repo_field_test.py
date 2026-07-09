#!/usr/bin/env python3
"""Run CoCoMUT over a large repo list and record compact diagnostics.

This is a field-test harness, not the publication evaluation harness. It accepts
repo lists such as DocuMine's cleaned_mined_repos.csv, which may not contain
pinned commits or build-system labels. Each repository is cloned from its
current default branch, analyzed once, summarized, and then bulky outputs are
removed.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any


REPO_COLUMNS = [
    "repo",
    "index",
    "clone_status",
    "detected_build_files",
    "cocomut_exit_code",
    "timed_out",
    "wall_duration_ms",
    "status",
    "failure_codes",
    "phase_1_build_system",
    "phase_1_compiles",
    "phase_1_compile_status",
    "phase_1_build_attempted",
    "phase_1_build_succeeded",
    "phase_1_build_timed_out",
    "phase_1_bytecode_available",
    "phase_1_analysis_can_proceed",
    "source_files_discovered",
    "source_files_parsed",
    "source_files_failed",
    "phase_2_methods_identified",
    "phase_3_available",
    "phase_3_call_graph_artifact_exists",
    "phase_3_focal_methods_matched_to_bytecode",
    "phase_3_call_edges_generated",
    "phase_3_warning",
    "phase_4_contexts_extracted",
    "phase_5_jsonl_rows",
    "phase_5_call_edges_serialized",
    "jsonl_files",
    "jsonl_parseable_rows",
    "jsonl_malformed_rows",
    "row_count_matches_contexts",
    "focal_bytecode_match_rate",
    "artifact_dir",
]


ANOMALY_COLUMNS = [
    "repo",
    "index",
    "severity",
    "kind",
    "detail",
]


def main() -> int:
    args = parse_args()
    if args.force and args.resume:
        raise SystemExit("--force and --resume are mutually exclusive")
    if args.force and args.output_root.exists():
        shutil.rmtree(args.output_root)
    args.output_root.mkdir(parents=True, exist_ok=True)
    (args.output_root / "artifacts").mkdir(exist_ok=True)
    rows = load_repos(args.repos_csv)
    if args.limit is not None:
        rows = rows[: args.limit]
    completed = completed_repos(args.output_root / "repository-results.tsv") if args.resume else set()
    write_environment(args, rows)

    for idx, row in enumerate(rows, 1):
        repo = row["repo"].strip()
        if not repo:
            continue
        if repo in completed:
            print(f"[skip] {idx}/{len(rows)} {repo}", flush=True)
            continue
        print(f"[run] {idx}/{len(rows)} {repo}", flush=True)
        result, anomalies = run_repo(args, repo, idx)
        append_tsv(args.output_root / "repository-results.tsv", REPO_COLUMNS, [result])
        if anomalies:
            append_tsv(args.output_root / "anomalies.tsv", ANOMALY_COLUMNS, anomalies)
        if args.prune:
            prune_repo_outputs(args, repo)
    ensure_tsv(args.output_root / "repository-results.tsv", REPO_COLUMNS)
    ensure_tsv(args.output_root / "anomalies.tsv", ANOMALY_COLUMNS)
    write_summary(args.output_root)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repos-csv", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--cocomut-command", default="./bin/cocomut")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--compile-timeout", type=int, default=600)
    parser.add_argument("--heap-gb", type=int, default=4)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--prune", action="store_true",
                        help="Remove checkouts and bulky JSONL after summarization.")
    return parser.parse_args()


def load_repos(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if "repo" not in (reader.fieldnames or []):
            raise SystemExit(f"{path} must contain a repo column")
        return list(reader)


def completed_repos(path: Path) -> set[str]:
    if not path.exists():
        return set()
    with path.open(newline="", encoding="utf-8") as handle:
        return {row["repo"] for row in csv.DictReader(handle, delimiter="\t") if row.get("repo")}


def run_repo(args: argparse.Namespace, repo: str, index: int) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    safe = repo.replace("/", "__")
    repo_dir = args.output_root / "work" / safe
    checkout = repo_dir / "checkout"
    cocomut_output = repo_dir / "cocomut_output"
    artifact_dir = args.output_root / "artifacts" / safe
    log_dir = artifact_dir / "logs"
    if args.force and repo_dir.exists():
        shutil.rmtree(repo_dir)
    log_dir.mkdir(parents=True, exist_ok=True)

    clone_status = clone_repo(repo, checkout, log_dir)
    detected_build_files = detect_build_files(checkout) if clone_status == "OK" else []
    report: dict[str, Any] = {}
    exit_code: int | str = ""
    timed_out = False
    wall_duration_ms = ""
    jsonl_metrics = {"jsonl_files": 0, "jsonl_parseable_rows": 0, "jsonl_malformed_rows": 0}
    call_metrics = {"serialized_edges": 0}

    if clone_status == "OK":
        if cocomut_output.exists():
            shutil.rmtree(cocomut_output)
        cocomut_output.mkdir(parents=True, exist_ok=True)
        command = [
            *shlex.split(args.cocomut_command),
            "--project", str(checkout),
            "--scope", "all",
            "--source-set", "main",
            "--call-graph", "rta",
            "--allow-build",
            "--output-dir", str(cocomut_output),
        ]
        start = time.monotonic()
        exit_code, timed_out = run_logged(command, log_dir / "cocomut.log", args.timeout, args)
        wall_duration_ms = int((time.monotonic() - start) * 1000)
        report = read_report(cocomut_output)
        jsonl_metrics, call_metrics = parse_jsonl(cocomut_output)
        copy_small_artifacts(cocomut_output, artifact_dir)

    result = result_row(
        repo=repo,
        index=index,
        clone_status=clone_status,
        detected_build_files=detected_build_files,
        exit_code=exit_code,
        timed_out=timed_out,
        wall_duration_ms=wall_duration_ms,
        report=report,
        jsonl_metrics=jsonl_metrics,
        artifact_dir=artifact_dir,
    )
    anomalies = detect_anomalies(result, report, call_metrics)
    for anomaly in anomalies:
        anomaly["repo"] = repo
        anomaly["index"] = index
    return result, anomalies


def clone_repo(repo: str, checkout: Path, log_dir: Path) -> str:
    if checkout.exists():
        return "OK"
    checkout.parent.mkdir(parents=True, exist_ok=True)
    code, timed_out = run_logged(
        ["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", str(checkout)],
        log_dir / "clone.log",
        timeout=900,
        args=None,
    )
    if timed_out:
        return "TIMEOUT"
    return "OK" if code == 0 else f"EXIT_{code}"


def detect_build_files(checkout: Path) -> list[str]:
    if not checkout.exists():
        return []
    names = ["pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew", "mvnw"]
    found = [name for name in names if (checkout / name).exists()]
    if found:
        return found
    nested = []
    for path in checkout.glob("*"):
        if path.is_dir():
            for name in names[:3]:
                if (path / name).exists():
                    nested.append(str(path.name + "/" + name))
    return nested[:20]


def run_logged(command: list[str], log_path: Path, timeout: int, args: argparse.Namespace | None) -> tuple[int, bool]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    heap = args.heap_gb if args else 4
    compile_timeout = args.compile_timeout if args else 600
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


def read_report(output_dir: Path) -> dict[str, Any]:
    reports = sorted(output_dir.rglob("extraction_report.json"))
    if not reports:
        return {}
    try:
        return json.loads(reports[0].read_text(encoding="utf-8", errors="replace"))
    except Exception as exc:
        return {"status": "ERROR", "phase_5_error": f"could not parse extraction_report.json: {exc}"}


def parse_jsonl(output_dir: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    files = sorted(path for path in output_dir.rglob("*.jsonl")
                   if path.name == "method_contexts.jsonl" or path.name.startswith("method_contexts__"))
    parseable = 0
    malformed = 0
    edges = 0
    for path in files:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    malformed += 1
                    continue
                parseable += 1
                edges += len(row.get("callers") or []) + len(row.get("callees") or [])
    return {
        "jsonl_files": len(files),
        "jsonl_parseable_rows": parseable,
        "jsonl_malformed_rows": malformed,
    }, {"serialized_edges": edges}


def copy_small_artifacts(output_dir: Path, artifact_dir: Path) -> None:
    artifact_dir.mkdir(parents=True, exist_ok=True)
    for name in ["extraction_report.json", "extraction_manifest.json", "method_context_failures.jsonl",
                 "failed_source_files.jsonl"]:
        for path in output_dir.rglob(name):
            target = artifact_dir / path.name
            if target.exists():
                target = artifact_dir / f"{path.parent.name}-{path.name}"
            shutil.copy2(path, target)


def result_row(repo: str,
               index: int,
               clone_status: str,
               detected_build_files: list[str],
               exit_code: int | str,
               timed_out: bool,
               wall_duration_ms: int | str,
               report: dict[str, Any],
               jsonl_metrics: dict[str, Any],
               artifact_dir: Path) -> dict[str, Any]:
    row: dict[str, Any] = {column: "" for column in REPO_COLUMNS}
    row["repo"] = repo
    row["index"] = index
    row["clone_status"] = clone_status
    row["detected_build_files"] = json.dumps(detected_build_files)
    row["cocomut_exit_code"] = "TIMEOUT" if timed_out else exit_code
    row["timed_out"] = str(timed_out).lower()
    row["wall_duration_ms"] = wall_duration_ms
    row["status"] = report.get("status") or ("TIMEOUT" if timed_out else (f"EXIT_{exit_code}" if exit_code not in ("", 0) else "NO_REPORT"))
    for key in REPO_COLUMNS:
        if key in report:
            row[key] = format_value(report[key])
    row["failure_codes"] = format_value(report.get("failure_codes", ""))
    row.update(jsonl_metrics)
    expected = as_int(row.get("phase_5_jsonl_rows"))
    contexts = as_int(row.get("phase_4_contexts_extracted"))
    row["row_count_matches_contexts"] = ""
    if expected is not None:
        row["row_count_matches_contexts"] = str(expected == row["jsonl_parseable_rows"]).lower()
    if expected is not None and contexts is not None:
        row["row_count_matches_contexts"] = str(expected == contexts == row["jsonl_parseable_rows"]).lower()
    row["focal_bytecode_match_rate"] = rate(
        as_int(row.get("phase_3_focal_methods_matched_to_bytecode")) or 0,
        as_int(row.get("phase_2_methods_identified")) or 0,
    )
    row["artifact_dir"] = str(artifact_dir)
    return row


def detect_anomalies(row: dict[str, Any],
                     report: dict[str, Any],
                     call_metrics: dict[str, Any]) -> list[dict[str, Any]]:
    anomalies: list[dict[str, Any]] = []

    def add(severity: str, kind: str, detail: str) -> None:
        anomalies.append({"severity": severity, "kind": kind, "detail": detail})

    status = str(row.get("status") or "")
    failure_codes = str(row.get("failure_codes") or "")
    exit_code = str(row.get("cocomut_exit_code") or "")
    if status == "SUCCESS" and exit_code not in {"0", ""}:
        add("high", "success_nonzero_exit", f"status SUCCESS but exit code is {exit_code}")
    if status == "SUCCESS" and failure_codes not in {"", "[NONE]", "NONE", "[]", "[\"NONE\"]"}:
        add("high", "success_failure_codes", f"status SUCCESS has failure_codes={failure_codes}")
    if row.get("row_count_matches_contexts") == "false":
        add("high", "row_count_mismatch", "JSONL rows do not match report/context counts")
    if int(row.get("jsonl_malformed_rows") or 0) > 0:
        add("high", "malformed_jsonl", f"malformed rows={row.get('jsonl_malformed_rows')}")
    if "CALL_GRAPH_UNAVAILABLE" in failure_codes and str(row.get("phase_3_call_graph_artifact_exists")).lower() == "true":
        add("high", "misleading_call_graph_failure",
            "CALL_GRAPH_UNAVAILABLE reported even though call-graph artifact exists")
    if str(row.get("phase_3_available")).lower() == "true" and int(row.get("phase_5_call_edges_serialized") or 0) == 0:
        add("medium", "available_call_graph_without_edges", "phase_3_available=true but no serialized edges")
    if report.get("phase_3_warning") and "Call graph generated" not in str(report.get("phase_3_warning")):
        add("low", "phase_3_warning_wording", str(report.get("phase_3_warning")))
    if row.get("clone_status") == "OK" and status == "NO_REPORT":
        add("high", "missing_report", "CoCoMUT produced no extraction_report.json")
    reported_edges = as_int(row.get("phase_5_call_edges_serialized"))
    if reported_edges is not None and call_metrics.get("serialized_edges") != reported_edges:
        add("medium", "edge_count_mismatch",
            f"parsed={call_metrics.get('serialized_edges')} report={row.get('phase_5_call_edges_serialized')}")
    return anomalies


def prune_repo_outputs(args: argparse.Namespace, repo: str) -> None:
    safe = repo.replace("/", "__")
    repo_dir = args.output_root / "work" / safe
    if not repo_dir.exists():
        return
    checkout = repo_dir / "checkout"
    output = repo_dir / "cocomut_output"
    if checkout.exists():
        shutil.rmtree(checkout)
    if output.exists():
        shutil.rmtree(output)


def write_environment(args: argparse.Namespace, rows: list[dict[str, str]]) -> None:
    env = {
        "repo_count": len(rows),
        "repos_csv": str(args.repos_csv),
        "cocomut_command": args.cocomut_command,
        "timeout": args.timeout,
        "compile_timeout": args.compile_timeout,
        "heap_gb": args.heap_gb,
        "python": sys.version,
        "java": command_text(["java", "-version"]),
        "maven": command_text(["mvn", "-version"]),
        "gradle": command_text(["gradle", "--version"]),
    }
    (args.output_root / "environment.json").write_text(json.dumps(env, indent=2) + "\n")


def write_summary(output_root: Path) -> None:
    path = output_root / "repository-results.tsv"
    if not path.exists():
        return
    rows = list(csv.DictReader(path.open(newline="", encoding="utf-8"), delimiter="\t"))
    statuses = Counter(row.get("status") or "" for row in rows)
    build_systems = Counter(row.get("phase_1_build_system") or "unknown" for row in rows)
    by_build_status: dict[str, Counter[str]] = {}
    durations_by_build: dict[str, list[int | None]] = {}
    for row in rows:
        build = row.get("phase_1_build_system") or "unknown"
        by_build_status.setdefault(build, Counter())[row.get("status") or ""] += 1
        durations_by_build.setdefault(build, []).append(as_int(row.get("wall_duration_ms")))
    success_rows = [row for row in rows if row.get("status") == "SUCCESS"]
    lines = ["# CoCoMUT Large Field Test", ""]
    lines.append(f"- Repositories attempted: {len(rows)}")
    lines.append(f"- Statuses: {dict(statuses)}")
    lines.append(f"- Reported build systems: {dict(build_systems)}")
    lines.append("- Status by build system:")
    for build, counter in sorted(by_build_status.items()):
        lines.append(f"  - {build}: {dict(counter)}, median wall time ms={median_int(durations_by_build[build])}")
    lines.append(f"- Successful outputs: {len(success_rows)}")
    if success_rows:
        lines.append(f"- Median wall time ms: {median_int([as_int(row.get('wall_duration_ms')) for row in success_rows])}")
        lines.append(f"- Total JSONL rows: {sum(as_int(row.get('jsonl_parseable_rows')) or 0 for row in success_rows)}")
        lines.append(f"- Total serialized edges: {sum(as_int(row.get('phase_5_call_edges_serialized')) or 0 for row in success_rows)}")
    anomalies_path = output_root / "anomalies.tsv"
    if anomalies_path.exists():
        anomalies = list(csv.DictReader(anomalies_path.open(newline="", encoding="utf-8"), delimiter="\t"))
        lines.append(f"- Anomalies recorded: {len(anomalies)}")
        lines.append(f"- Anomaly kinds: {dict(Counter(row.get('kind') for row in anomalies))}")
    output_root.joinpath("summary.md").write_text("\n".join(lines) + "\n")


def append_tsv(path: Path, columns: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    exists = path.exists()
    with path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, columns, delimiter="\t", extrasaction="ignore", lineterminator="\n")
        if not exists:
            writer.writeheader()
        writer.writerows(rows)


def ensure_tsv(path: Path, columns: list[str]) -> None:
    if not path.exists():
        append_tsv(path, columns, [])


def command_text(command: list[str]) -> str:
    try:
        return subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, timeout=30, check=False).stdout.strip()
    except Exception as exc:
        return str(exc)


def format_value(value: Any) -> str:
    if isinstance(value, (list, dict)):
        return json.dumps(value, sort_keys=True)
    return "" if value is None else str(value)


def as_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(str(value))
    except ValueError:
        return None


def rate(num: int, den: int) -> str:
    if den <= 0:
        return ""
    return f"{num / den:.6f}"


def median_int(values: list[int | None]) -> str:
    clean = sorted(v for v in values if v is not None)
    if not clean:
        return ""
    mid = len(clean) // 2
    if len(clean) % 2:
        return str(clean[mid])
    return str((clean[mid - 1] + clean[mid]) // 2)


if __name__ == "__main__":
    raise SystemExit(main())
