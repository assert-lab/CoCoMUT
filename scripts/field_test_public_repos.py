#!/usr/bin/env python3
"""Run CoCoX against a filtered sample of public Java repositories.

The script expects the repo-mining CSV used in this workspace and writes
resume-safe evidence under target/field-tests/public-repos/.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path


TUTORIAL_EXCLUDE = re.compile(
    r"(sample|demo|tutorial|interview|coding|leetcode|example|"
    r"bootcamp|course|learning|starter|template|awesome|guide|toy|kata|"
    r"algorithm|algorithms|exercise|workshop|hello[- ]?world)",
    re.I,
)
ANDROID_HINT = re.compile(r"(android|gradle plugin|apk|mobile)", re.I)
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
    "compile_attempted",
    "compiles",
    "compile_status",
    "source_roots",
    "test_source_roots",
    "class_output_dirs",
    "dependency_jars",
    "source_resolution",
    "source_set_filter",
    "source_backend_modes",
    "call_graph_requested",
    "call_graph_available",
    "call_graph_effective_algorithm",
    "call_graphs_generated",
    "methods",
    "jsonl_rows",
    "javadoc_see_methods",
    "javadoc_inheritdoc_methods",
    "javadoc_inheritdoc_with_candidates",
    "duration_ms",
    "failure_codes",
    "retry_mode",
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


def select_repositories(
    csv_path: Path,
    limit: int,
    min_stars: int,
    min_contributors: int,
    min_quality: int,
    max_size_kb: int,
    include_android: bool,
) -> list[dict[str, str]]:
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
            if TUTORIAL_EXCLUDE.search(text):
                continue
            if not include_android and ANDROID_HINT.search(text):
                continue
            size = int_field(row, "size_kb")
            stars = int_field(row, "stars")
            contributors = int_field(row, "contributor_count")
            quality = int_field(row, "repo_quality_score")
            if size <= 0 or (max_size_kb > 0 and size > max_size_kb):
                continue
            if stars < min_stars or contributors < min_contributors or quality < min_quality:
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
    selected = [row for _, row in rows]
    return selected if limit <= 0 else selected[:limit]


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


def has_worktree_files(checkout: Path) -> bool:
    if not checkout.exists():
        return False
    try:
        return any(path.name != ".git" for path in checkout.iterdir())
    except OSError:
        return False


def read_log_tail(log_path: Path, max_chars: int = 4000) -> str:
    if not log_path.exists():
        return ""
    text = log_path.read_text(encoding="utf-8", errors="replace")
    return text[-max_chars:]


def run_extraction(
    root: Path,
    checkout: Path,
    artifact_dir: Path,
    log_path: Path,
    timeout: int,
    env: dict[str, str],
    max_source_files: int | None,
    max_methods: int | None,
    resolution: str,
    call_graph: str,
    source_set: str,
) -> tuple[int | None, dict[str, str], str]:
    shutil.rmtree(artifact_dir, ignore_errors=True)
    artifact_dir.mkdir(parents=True, exist_ok=True)
    command = [
        str(root / "bin" / "cocox"),
        "--project",
        str(checkout),
        "--scope",
        "entry-points",
        "--call-graph",
        call_graph,
        "--resolution",
        resolution,
        "--output-dir",
        str(artifact_dir),
    ]
    if source_set and source_set != "all":
        command.extend(["--source-set", source_set])
    if max_source_files and max_source_files > 0:
        command.extend(["--max-source-files", str(max_source_files)])
    if max_methods and max_methods > 0:
        command.extend(["--max-methods", str(max_methods)])
    status = run_command(command, root, log_path, timeout, env)
    return status, parse_report(log_path), read_log_tail(log_path)


def filtered_all_methods(data: dict[str, str]) -> bool:
    """Return true when source-set filtering removed every discovered method."""
    before = data.get("phase_2_source_set_filter_before")
    after = data.get("phase_2_source_set_filter_after")
    try:
        return before is not None and int(before) > 0 and after is not None and int(after) == 0
    except ValueError:
        return False


def run_repo(
    root: Path,
    output_dir: Path,
    repo: str,
    timeout: int,
    retry_max_source_files: int,
    retry_max_methods: int,
    retry_smoke_source_files: int,
    retry_smoke_methods: int,
    resolution: str,
    call_graph: str,
    source_set: str,
    compile_timeout: int,
    java_home: str | None,
) -> dict[str, str]:
    safe = repo.replace("/", "__")
    checkout = output_dir / "checkouts" / safe
    artifact_dir = output_dir / "outputs" / safe
    logs = output_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    checkout.parent.mkdir(parents=True, exist_ok=True)

    if checkout.exists() and not has_worktree_files(checkout):
        shutil.rmtree(checkout, ignore_errors=True)

    if not checkout.exists():
        clone_status = run_command(
            ["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", str(checkout)],
            output_dir,
            logs / f"{safe}.clone.log",
            180,
            os.environ.copy(),
        )
        if clone_status is None:
            shutil.rmtree(checkout, ignore_errors=True)
            clone_status = run_command(
                ["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", str(checkout)],
                output_dir,
                logs / f"{safe}.clone-retry.log",
                600,
                os.environ.copy(),
            )
            if clone_status is None:
                shutil.rmtree(checkout, ignore_errors=True)
                return {"repo": repo, "clone_status": "TIMEOUT", "status": "SKIPPED", "note": "clone timeout after retry"}
        if clone_status != 0:
            shutil.rmtree(checkout, ignore_errors=True)
            return {"repo": repo, "clone_status": f"FAIL_{clone_status}", "status": "SKIPPED", "note": "clone failed"}

    env = os.environ.copy()
    env.setdefault("MAVEN_OPTS", "-Xmx2g")
    env.setdefault("JAVA_TOOL_OPTIONS", "-Xmx2g")
    env.setdefault("COCOX_COMPILE_TIMEOUT_SECONDS", str(compile_timeout))
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    log_path = logs / f"{safe}.cocox.log"
    start = time.time()
    status, data, tail = run_extraction(root, checkout, artifact_dir, log_path, timeout, env,
                                        None, None, resolution, call_graph, source_set)
    retry_mode = "none"
    active_source_set = source_set
    note = "" if status == 0 else f"exit {status}"

    should_retry = status is None or status != 0 or "Java heap space" in tail or "OutOfMemoryError" in tail
    if should_retry and retry_max_source_files > 0:
        retry_mode = f"max_source_files={retry_max_source_files}"
        retry_log = logs / f"{safe}.cocox.retry.log"
        status, data, retry_tail = run_extraction(
            root, checkout, artifact_dir, retry_log, timeout, env, retry_max_source_files, None,
            resolution, call_graph, source_set)
        tail = retry_tail
        note = "retry capped source files" if status == 0 else f"retry exit {status}"

    timed_out_or_oom = status is None or "Java heap space" in tail or "OutOfMemoryError" in tail
    should_retry_methods = status != 0 and not timed_out_or_oom
    if should_retry_methods and retry_max_methods > 0:
        retry_mode = f"max_source_files={retry_max_source_files};max_methods={retry_max_methods}"
        retry_log = logs / f"{safe}.cocox.retry-max-methods.log"
        status, data, retry_tail = run_extraction(
            root,
            checkout,
            artifact_dir,
            retry_log,
            timeout,
            env,
            retry_max_source_files,
            retry_max_methods,
            resolution,
            call_graph,
            source_set,
        )
        tail = retry_tail
        note = "retry capped source files and methods" if status == 0 else f"retry max-methods exit {status}"

    if source_set == "main" and status != 0 and filtered_all_methods(data):
        fallback_source_set = "main,unknown"
        active_source_set = fallback_source_set
        retry_mode = f"{retry_mode};source_set={fallback_source_set}"
        retry_log = logs / f"{safe}.cocox.retry-source-set.log"
        status, data, retry_tail = run_extraction(
            root,
            checkout,
            artifact_dir,
            retry_log,
            timeout,
            env,
            retry_max_source_files,
            retry_max_methods,
            resolution,
            call_graph,
            fallback_source_set,
        )
        tail = retry_tail
        note = (
            "retry source-set main,unknown"
            if status == 0
            else f"retry source-set main,unknown exit {status}"
        )

    should_smoke_retry = status is None or status != 0 or "Java heap space" in tail or "OutOfMemoryError" in tail
    if should_smoke_retry and retry_smoke_source_files > 0 and retry_smoke_methods > 0:
        retry_mode = (
            f"{retry_mode};smoke_resolution=noclasspath;smoke_call_graph=none;"
            f"smoke_max_source_files={retry_smoke_source_files};smoke_max_methods={retry_smoke_methods}"
        )
        retry_log = logs / f"{safe}.cocox.retry-smoke.log"
        status, data, retry_tail = run_extraction(
            root,
            checkout,
            artifact_dir,
            retry_log,
            timeout,
            env,
            retry_smoke_source_files,
            retry_smoke_methods,
            "noclasspath",
            "none",
            active_source_set,
        )
        tail = retry_tail
        note = (
            "retry smoke capped source files and methods"
            if status == 0
            else f"retry smoke exit {status}"
        )

    elapsed_ms = str(int((time.time() - start) * 1000))
    if status is None:
        return {
            "repo": repo,
            "clone_status": "OK",
            "status": "TIMEOUT",
            "duration_ms": elapsed_ms,
            "retry_mode": retry_mode,
            "note": "analysis timeout",
        }

    javadoc_counts = inspect_javadoc_tags(artifact_dir)
    return {
        "repo": repo,
        "clone_status": "OK",
        "status": data.get("status", f"EXIT_{status}"),
        "build_system": data.get("phase_1_build_system", ""),
        "compile_attempted": data.get("phase_1_compile_attempted", ""),
        "compiles": data.get("phase_1_compiles", ""),
        "compile_status": data.get("phase_1_compile_status", ""),
        "source_roots": data.get("phase_1_source_roots", ""),
        "test_source_roots": data.get("phase_1_test_source_roots", ""),
        "class_output_dirs": data.get("phase_1_class_output_dirs", ""),
        "dependency_jars": data.get("phase_1_dependency_jars", ""),
        "source_resolution": data.get("phase_1_source_resolution_requested", resolution),
        "source_set_filter": data.get("phase_2_source_set_filter", source_set),
        "source_backend_modes": javadoc_counts.get("source_backend_modes", ""),
        "call_graph_requested": data.get("phase_3_algorithm", call_graph),
        "call_graph_available": data.get("phase_3_available", ""),
        "call_graph_effective_algorithm": data.get("phase_3_effective_algorithm", ""),
        "call_graphs_generated": data.get("phase_3_call_graphs_generated", ""),
        "methods": data.get("phase_2_methods_identified", ""),
        "jsonl_rows": data.get("phase_5_jsonl_rows", ""),
        "javadoc_see_methods": javadoc_counts.get("see_methods", ""),
        "javadoc_inheritdoc_methods": javadoc_counts.get("inheritdoc_methods", ""),
        "javadoc_inheritdoc_with_candidates": javadoc_counts.get("inheritdoc_with_candidates", ""),
        "duration_ms": data.get("duration_ms", elapsed_ms),
        "failure_codes": data.get("failure_codes", ""),
        "retry_mode": retry_mode,
        "note": note,
    }


def inspect_javadoc_tags(artifact_dir: Path) -> dict[str, str]:
    jsonl_files = sorted(artifact_dir.glob("*.jsonl"))
    if not jsonl_files:
        return {}
    jsonl = jsonl_files[0]
    see_methods = 0
    inheritdoc_methods = 0
    inheritdoc_with_candidates = 0
    modes: set[str] = set()
    try:
        with jsonl.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                metadata = row.get("metadata") or {}
                mode = metadata.get("source_backend_mode")
                if mode:
                    modes.add(str(mode))
                javadoc = row.get("javadoc_metadata") or {}
                if javadoc.get("see"):
                    see_methods += 1
                if javadoc.get("uses_inheritdoc"):
                    inheritdoc_methods += 1
                    if javadoc.get("inherited_javadoc_candidates"):
                        inheritdoc_with_candidates += 1
    except OSError:
        return {}
    return {
        "see_methods": str(see_methods),
        "inheritdoc_methods": str(inheritdoc_methods),
        "inheritdoc_with_candidates": str(inheritdoc_with_candidates),
        "source_backend_modes": ",".join(sorted(modes)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=Path("../cleaned_mined_repos.csv"))
    parser.add_argument("--limit", type=int, default=100,
                        help="Maximum selected repositories. Use 0 for all after filters.")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--output-dir", type=Path, default=Path("target/field-tests/public-repos"))
    parser.add_argument("--min-stars", type=int, default=0)
    parser.add_argument("--min-contributors", type=int, default=0)
    parser.add_argument("--min-quality", type=int, default=0)
    parser.add_argument("--max-size-kb", type=int, default=300_000,
                        help="Skip very large repos before cloning. Use 0 for no size cap.")
    parser.add_argument("--include-android", action="store_true",
                        help="Do not exclude Android-heavy repos.")
    parser.add_argument("--retry-max-source-files", type=int, default=1500,
                        help="Retry failed/timeout/OOM repos with a source-file cap. Use 0 to disable.")
    parser.add_argument("--retry-max-methods", type=int, default=5000,
                        help="Final retry method cap for huge repos that still fail. Use 0 to disable.")
    parser.add_argument("--retry-smoke-source-files", type=int, default=100,
                        help="Last-resort source-file cap for timeout/OOM repos. Use 0 to disable.")
    parser.add_argument("--retry-smoke-methods", type=int, default=250,
                        help="Last-resort method cap for timeout/OOM repos. Use 0 to disable.")
    parser.add_argument("--resolution", default="noclasspath",
                        choices=["noclasspath", "classpath", "auto"],
                        help="CoCoX source resolution mode.")
    parser.add_argument("--call-graph", default="none",
                        choices=["none", "cha", "rta", "auto"],
                        help="CoCoX call graph mode.")
    parser.add_argument("--source-set", default="all",
                        help="CoCoX source-set filter: all, main, test, integration_test, generated, example, unknown.")
    parser.add_argument("--compile-timeout", type=int, default=120,
                        help="Per-repository Maven/Gradle compile timeout in seconds for auto/compile runs.")
    parser.add_argument("--java-home", default="",
                        help="Optional JAVA_HOME for CoCoX and build-tool attempts.")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    csv_path = (root / args.csv).resolve() if not args.csv.is_absolute() else args.csv
    output_dir = (root / args.output_dir).resolve() if not args.output_dir.is_absolute() else args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    selected = select_repositories(
        csv_path,
        args.limit,
        args.min_stars,
        args.min_contributors,
        args.min_quality,
        args.max_size_kb,
        args.include_android,
    )
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
        result = run_repo(
            root,
            output_dir,
            repo,
            args.timeout,
            args.retry_max_source_files,
            args.retry_max_methods,
            args.retry_smoke_source_files,
            args.retry_smoke_methods,
            args.resolution,
            args.call_graph,
            args.source_set,
            args.compile_timeout,
            args.java_home or None,
        )
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
