#!/usr/bin/env python3
"""Run CoCoMUT on OE25 plus the representative public-repo checkouts.

The script writes all new artifacts under a fresh experiment directory and never
deletes or overwrites these protected folders:

- experiments/expanded-public-repos-auto-main
- experiments/expanded-public-repos-auto-main-representative
- experiments/manual-commons-lang-rerun-2026-06-17-final

Default extraction policy:

- try classpath-aware source extraction and SootUp call graph with
  ``--compile --resolution auto --call-graph auto``;
- if that fails, retry without call graph;
- if that still fails or runs out of memory, retry source-only with bounded
  source files/methods.

Examples:

    python3 scripts/run_oe25_plus_representative_study.py

    python3 scripts/run_oe25_plus_representative_study.py \
      --output-dir experiments/oe25-plus-representative-auto-main \
      --timeout 900 --compile-timeout 180

    python3 scripts/run_oe25_plus_representative_study.py --limit 5
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


OE25_REPOS = [
    "stleary/JSON-java",
    "AsyncHttpClient/async-http-client",
    "apache/commons-bcel",
    "apache/commons-beanutils",
    "apache/commons-collections",
    "apache/commons-configuration",
    "apache/commons-dbutils",
    "apache/commons-geometry",
    "apache/commons-imaging",
    "apache/commons-jcs",
    "apache/commons-jexl",
    "apache/commons-lang",
    "apache/commons-net",
    "apache/commons-numbers",
    "apache/commons-pool",
    "apache/commons-rng",
    "apache/commons-validator",
    "apache/commons-vfs",
    "apache/commons-weaver",
    "kevinsawicki/http-request",
    "JodaOrg/joda-time",
    "jhy/jsoup",
    "scribejava/scribejava",
    "perwendel/spark",
    "springside/springside4",
]

PROTECTED_EXPERIMENT_DIRS = {
    "expanded-public-repos-auto-main",
    "expanded-public-repos-auto-main-representative",
    "manual-commons-lang-rerun-2026-06-17-final",
}

RESULT_FIELDS = [
    "set",
    "repo",
    "project_path",
    "clone_status",
    "status",
    "retry_mode",
    "build_system",
    "compile_attempted",
    "compiles",
    "compile_status",
    "source_resolution_requested",
    "source_backend_modes",
    "call_graph_requested",
    "call_graph_available",
    "call_graph_effective_algorithm",
    "call_graphs_generated",
    "call_graph_non_empty_results",
    "call_graph_edges_generated",
    "call_edges",
    "call_edges_with_target_uri",
    "call_edges_with_method_uri",
    "call_edge_source_match_rate",
    "call_edge_project_jdk_external_edges",
    "call_edge_project_jdk_external_rate",
    "call_edge_target_kind_counts",
    "call_edge_resolution_counts",
    "call_edge_unresolved_reason_counts",
    "call_edge_ambiguous_edges",
    "call_edge_candidate_edges",
    "methods",
    "jsonl_rows",
    "see_methods",
    "see_references",
    "see_resolution_counts",
    "see_kind_counts",
    "inline_link_methods",
    "inline_link_references",
    "inheritdoc_methods",
    "inheritdoc_with_candidates",
    "duration_ms",
    "failure_codes",
    "output_dir",
    "note",
]


@dataclass(frozen=True)
class Target:
    dataset: str
    repo: str
    project_path: Path | None

    @property
    def safe_name(self) -> str:
        return self.repo.replace("/", "__")


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


def parse_report(log_path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    if not log_path.exists():
        return data
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line and not line.startswith("["):
            key, value = line.split("=", 1)
            data[key] = value
    return data


def read_tail(path: Path, max_chars: int = 6000) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")[-max_chars:]


def discover_representative_targets(root: Path, checkout_root: Path | None = None) -> list[Target]:
    checkout_root = checkout_root or root / "experiments" / "expanded-public-repos-auto-main-representative" / "checkouts"
    targets: list[Target] = []
    if not checkout_root.is_dir():
        return targets
    for checkout in sorted(checkout_root.iterdir()):
        if not checkout.is_dir():
            continue
        repo = checkout.name.replace("__", "/", 1)
        targets.append(Target("representative", repo, checkout))
    return targets


def clone_oe25_target(root: Path, output_dir: Path, repo: str, timeout: int,
                      existing_checkout_root: Path | None = None) -> Target:
    checkout = output_dir / "checkouts" / repo.replace("/", "__")
    if existing_checkout_root is not None:
        existing = existing_checkout_root / repo.replace("/", "__")
        if existing.is_dir() and any(existing.iterdir()):
            return Target("oe25", repo, existing)
    checkout.parent.mkdir(parents=True, exist_ok=True)
    logs = output_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    if checkout.exists() and any(checkout.iterdir()):
        return Target("oe25", repo, checkout)
    shutil.rmtree(checkout, ignore_errors=True)
    status = run_command(
        ["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", str(checkout)],
        root,
        logs / f"{repo.replace('/', '__')}.clone.log",
        timeout,
        os.environ.copy(),
    )
    if status != 0:
        shutil.rmtree(checkout, ignore_errors=True)
        return Target("oe25", repo, None)
    return Target("oe25", repo, checkout)


def run_cocomut(
    root: Path,
    target: Target,
    artifact_dir: Path,
    log_path: Path,
    timeout: int,
    env: dict[str, str],
    *,
    compile_project: bool,
    resolution: str,
    call_graph: str,
    max_source_files: int | None = None,
    max_methods: int | None = None,
) -> tuple[int | None, dict[str, str], str]:
    shutil.rmtree(artifact_dir, ignore_errors=True)
    artifact_dir.mkdir(parents=True, exist_ok=True)
    command = [
        str(root / "bin" / "cocomut"),
        "--project",
        str(target.project_path),
        "--scope",
        "entry-points",
        "--source-set",
        "main",
        "--resolution",
        resolution,
        "--call-graph",
        call_graph,
        "--output-dir",
        str(artifact_dir),
    ]
    if compile_project:
        command.append("--compile")
    if max_source_files and max_source_files > 0:
        command.extend(["--max-source-files", str(max_source_files)])
    if max_methods and max_methods > 0:
        command.extend(["--max-methods", str(max_methods)])
    status = run_command(command, root, log_path, timeout, env)
    return status, parse_report(log_path), read_tail(log_path)


def failed_or_unstable(status: int | None, tail: str) -> bool:
    return (
        status is None
        or status != 0
        or "Java heap space" in tail
        or "OutOfMemoryError" in tail
        or "StackOverflowError" in tail
    )


def has_frontend_package_manager(project_path: Path) -> bool:
    """Return true for Java repos whose compile may trigger JS package setup.

    CoCoMUT is a Java static-analysis tool. For field testing, we avoid invoking
    build steps that commonly install or build frontend dependencies as a side
    effect of Maven/Gradle compilation. Source-only extraction can still run.
    """
    frontend_markers = {
        "package.json",
        "yarn.lock",
        "package-lock.json",
        "pnpm-lock.yaml",
        "bun.lockb",
    }
    return any((project_path / marker).exists() for marker in frontend_markers)


def timeout_or_memory_failure(status: int | None, tail: str) -> bool:
    return (
        status is None
        or "Java heap space" in tail
        or "OutOfMemoryError" in tail
        or "StackOverflowError" in tail
    )


def inspect_jsonl(artifact_dir: Path) -> dict[str, str]:
    jsonl_files = sorted(path for path in artifact_dir.glob("*.jsonl") if path.name != "method_context_failures.jsonl")
    if not jsonl_files:
        return {}
    source_modes: set[str] = set()
    see_methods = 0
    see_references = 0
    see_resolution_counts: Counter[str] = Counter()
    see_kind_counts: Counter[str] = Counter()
    inline_link_methods = 0
    inline_link_references = 0
    inheritdoc_methods = 0
    inheritdoc_with_candidates = 0
    call_edges = 0
    call_edges_with_target_uri = 0
    call_edges_with_method_uri = 0
    call_edge_target_kind_counts: Counter[str] = Counter()
    call_edge_resolution_counts: Counter[str] = Counter()
    call_edge_unresolved_reason_counts: Counter[str] = Counter()
    call_edge_ambiguous_edges = 0
    call_edge_candidate_edges = 0
    for jsonl in jsonl_files:
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
                    source_modes.add(str(mode))
                for edge in (row.get("callers") or []) + (row.get("callees") or []):
                    if not isinstance(edge, dict):
                        continue
                    call_edges += 1
                    if edge.get("target_uri"):
                        call_edges_with_target_uri += 1
                    if edge.get("method_uri"):
                        call_edges_with_method_uri += 1
                    call_edge_target_kind_counts[str(edge.get("target_kind") or edge.get("kind") or "missing")] += 1
                    call_edge_resolution_counts[str(edge.get("resolution") or "missing")] += 1
                    if edge.get("unresolved_reason"):
                        call_edge_unresolved_reason_counts[str(edge.get("unresolved_reason"))] += 1
                    if edge.get("resolution") == "ambiguous":
                        call_edge_ambiguous_edges += 1
                    if edge.get("candidate_method_uris"):
                        call_edge_candidate_edges += 1
                javadoc = row.get("javadoc_metadata") or {}
                if javadoc.get("see"):
                    see_methods += 1
                if javadoc.get("inline_links"):
                    inline_link_methods += 1
                references = javadoc.get("javadoc_references") or []
                for reference in references:
                    tag = reference.get("tag")
                    if tag == "see":
                        see_references += 1
                        see_resolution_counts[str(reference.get("resolution", "missing"))] += 1
                        see_kind_counts[str(reference.get("kind", "missing"))] += 1
                    elif tag in {"link", "linkplain"}:
                        inline_link_references += 1
                if javadoc.get("uses_inheritdoc"):
                    inheritdoc_methods += 1
                    if javadoc.get("inherited_javadoc_candidates"):
                        inheritdoc_with_candidates += 1
    project_jdk_external = (
        call_edge_target_kind_counts.get("project_method", 0)
        + call_edge_target_kind_counts.get("jdk_method", 0)
        + call_edge_target_kind_counts.get("external_method", 0)
    )
    return {
        "source_backend_modes": ",".join(sorted(source_modes)),
        "call_edges": str(call_edges),
        "call_edges_with_target_uri": str(call_edges_with_target_uri),
        "call_edges_with_method_uri": str(call_edges_with_method_uri),
        "call_edge_source_match_rate": percent_string(call_edges_with_method_uri, call_edges),
        "call_edge_project_jdk_external_edges": str(project_jdk_external),
        "call_edge_project_jdk_external_rate": percent_string(project_jdk_external, call_edges),
        "call_edge_target_kind_counts": json.dumps(dict(sorted(call_edge_target_kind_counts.items())), sort_keys=True),
        "call_edge_resolution_counts": json.dumps(dict(sorted(call_edge_resolution_counts.items())), sort_keys=True),
        "call_edge_unresolved_reason_counts": json.dumps(dict(sorted(call_edge_unresolved_reason_counts.items())), sort_keys=True),
        "call_edge_ambiguous_edges": str(call_edge_ambiguous_edges),
        "call_edge_candidate_edges": str(call_edge_candidate_edges),
        "see_methods": str(see_methods),
        "see_references": str(see_references),
        "see_resolution_counts": json.dumps(dict(sorted(see_resolution_counts.items())), sort_keys=True),
        "see_kind_counts": json.dumps(dict(sorted(see_kind_counts.items())), sort_keys=True),
        "inline_link_methods": str(inline_link_methods),
        "inline_link_references": str(inline_link_references),
        "inheritdoc_methods": str(inheritdoc_methods),
        "inheritdoc_with_candidates": str(inheritdoc_with_candidates),
    }


def percent_string(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return ""
    return f"{100.0 * numerator / denominator:.2f}%"


def mem_available_gb() -> float:
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / (1024 * 1024)
    except OSError:
        return 999.0
    return 999.0


def wait_for_resources(args: argparse.Namespace) -> None:
    while True:
        available = mem_available_gb()
        load_1m = os.getloadavg()[0] if hasattr(os, "getloadavg") else 0.0
        if available >= args.min_available_gb and load_1m <= args.max_load:
            return
        print(
            f"resource-wait available_gb={available:.2f} load_1m={load_1m:.2f} "
            f"thresholds=({args.min_available_gb:.2f}GB,{args.max_load:.2f})",
            flush=True,
        )
        time.sleep(args.resource_check_interval)


def run_target(root: Path, output_dir: Path, target: Target, args: argparse.Namespace) -> dict[str, str]:
    if target.project_path is None:
        return {
            "set": target.dataset,
            "repo": target.repo,
            "clone_status": "FAIL",
            "status": "SKIPPED",
            "note": "clone failed",
        }
    logs = output_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    artifact_dir = output_dir / "outputs" / target.dataset / target.safe_name
    env = os.environ.copy()
    env.setdefault("MAVEN_OPTS", f"-Xmx{args.heap_gb}g")
    env.setdefault("JAVA_TOOL_OPTIONS", f"-Xmx{args.heap_gb}g")
    env.setdefault("COCOMUT_COMPILE_TIMEOUT_SECONDS", str(args.compile_timeout))
    start = time.time()
    compile_project = not has_frontend_package_manager(target.project_path)

    log_path = logs / f"{target.dataset}__{target.safe_name}.cocomut.log"
    status, report, tail = run_cocomut(
        root,
        target,
        artifact_dir,
        log_path,
        args.timeout,
        env,
        compile_project=compile_project,
        resolution="auto",
        call_graph="auto",
    )
    retry_mode = "none"
    note = "" if status == 0 else f"exit {status}"
    if not compile_project:
        note = "compile skipped: frontend package-manager marker"

    if failed_or_unstable(status, tail) and not timeout_or_memory_failure(status, tail):
        retry_mode = "no_call_graph"
        retry_log = logs / f"{target.dataset}__{target.safe_name}.retry-no-callgraph.log"
        status, report, tail = run_cocomut(
            root,
            target,
            artifact_dir,
            retry_log,
            args.timeout,
            env,
            compile_project=compile_project,
            resolution="auto",
            call_graph="none",
        )
        note = (
            "retry without call graph"
            if status == 0
            else f"retry no-callgraph exit {status}"
        )
        if not compile_project:
            note += "; compile skipped: frontend package-manager marker"

    if failed_or_unstable(status, tail):
        retry_mode = (
            "source_only_bounded"
            if retry_mode == "none"
            else f"{retry_mode};source_only_bounded"
        )
        retry_log = logs / f"{target.dataset}__{target.safe_name}.retry-source-only.log"
        status, report, tail = run_cocomut(
            root,
            target,
            artifact_dir,
            retry_log,
            args.timeout,
            env,
            compile_project=False,
            resolution="noclasspath",
            call_graph="none",
            max_source_files=args.retry_max_source_files,
            max_methods=args.retry_max_methods,
        )
        note = "retry source-only bounded" if status == 0 else f"retry source-only exit {status}"

    duration_ms = str(int((time.time() - start) * 1000))
    stats = inspect_jsonl(artifact_dir)
    return {
        "set": target.dataset,
        "repo": target.repo,
        "project_path": str(target.project_path),
        "clone_status": "OK",
        "status": report.get("status", "TIMEOUT" if status is None else f"EXIT_{status}"),
        "retry_mode": retry_mode,
        "build_system": report.get("phase_1_build_system", ""),
        "compile_attempted": report.get("phase_1_compile_attempted", ""),
        "compiles": report.get("phase_1_compiles", ""),
        "compile_status": report.get("phase_1_compile_status", ""),
        "source_resolution_requested": report.get("phase_1_source_resolution_requested", ""),
        "source_backend_modes": stats.get("source_backend_modes", ""),
        "call_graph_requested": report.get("phase_3_algorithm", ""),
        "call_graph_available": report.get("phase_3_available", ""),
        "call_graph_effective_algorithm": report.get("phase_3_effective_algorithm", ""),
        "call_graphs_generated": report.get("phase_3_call_graphs_generated", ""),
        "call_graph_non_empty_results": report.get("phase_3_non_empty_call_graphs", ""),
        "call_graph_edges_generated": report.get("phase_3_call_edges_generated", ""),
        "call_edges": stats.get("call_edges", ""),
        "call_edges_with_target_uri": stats.get("call_edges_with_target_uri", ""),
        "call_edges_with_method_uri": stats.get("call_edges_with_method_uri", ""),
        "call_edge_source_match_rate": stats.get("call_edge_source_match_rate", ""),
        "call_edge_project_jdk_external_edges": stats.get("call_edge_project_jdk_external_edges", ""),
        "call_edge_project_jdk_external_rate": stats.get("call_edge_project_jdk_external_rate", ""),
        "call_edge_target_kind_counts": stats.get("call_edge_target_kind_counts", ""),
        "call_edge_resolution_counts": stats.get("call_edge_resolution_counts", ""),
        "call_edge_unresolved_reason_counts": stats.get("call_edge_unresolved_reason_counts", ""),
        "call_edge_ambiguous_edges": stats.get("call_edge_ambiguous_edges", ""),
        "call_edge_candidate_edges": stats.get("call_edge_candidate_edges", ""),
        "methods": report.get("phase_2_methods_identified", ""),
        "jsonl_rows": report.get("phase_5_jsonl_rows", ""),
        "see_methods": stats.get("see_methods", ""),
        "see_references": stats.get("see_references", ""),
        "see_resolution_counts": stats.get("see_resolution_counts", ""),
        "see_kind_counts": stats.get("see_kind_counts", ""),
        "inline_link_methods": stats.get("inline_link_methods", ""),
        "inline_link_references": stats.get("inline_link_references", ""),
        "inheritdoc_methods": stats.get("inheritdoc_methods", ""),
        "inheritdoc_with_candidates": stats.get("inheritdoc_with_candidates", ""),
        "duration_ms": report.get("duration_ms", duration_ms),
        "failure_codes": report.get("failure_codes", ""),
        "output_dir": str(artifact_dir),
        "note": note,
    }


def write_row(path: Path, row: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\t".join(str(row.get(field, "")) for field in RESULT_FIELDS))
        handle.write("\n")


def write_summary(output_dir: Path, results_path: Path) -> None:
    rows: list[dict[str, str]] = []
    with results_path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            rows.append(row)
    status_counts = Counter(row["status"] for row in rows)
    call_graph_counts = Counter(row["call_graph_available"] for row in rows)
    compile_counts = Counter(row["compiles"] for row in rows)
    total_methods = sum(int(row["methods"] or 0) for row in rows if (row["methods"] or "").isdigit())
    total_rows = sum(int(row["jsonl_rows"] or 0) for row in rows if (row["jsonl_rows"] or "").isdigit())
    total_call_edges = sum(int(row["call_edges"] or 0) for row in rows if (row["call_edges"] or "").isdigit())
    total_target_uri_edges = sum(int(row["call_edges_with_target_uri"] or 0) for row in rows if (row["call_edges_with_target_uri"] or "").isdigit())
    total_method_uri_edges = sum(int(row["call_edges_with_method_uri"] or 0) for row in rows if (row["call_edges_with_method_uri"] or "").isdigit())
    total_project_jdk_external_edges = sum(int(row["call_edge_project_jdk_external_edges"] or 0) for row in rows if (row["call_edge_project_jdk_external_edges"] or "").isdigit())
    total_ambiguous_edges = sum(int(row["call_edge_ambiguous_edges"] or 0) for row in rows if (row["call_edge_ambiguous_edges"] or "").isdigit())
    target_kind_counts: Counter[str] = Counter()
    resolution_counts: Counter[str] = Counter()
    unresolved_reason_counts: Counter[str] = Counter()
    for row in rows:
        for field, counter in [
            ("call_edge_target_kind_counts", target_kind_counts),
            ("call_edge_resolution_counts", resolution_counts),
            ("call_edge_unresolved_reason_counts", unresolved_reason_counts),
        ]:
            try:
                counter.update(json.loads(row.get(field) or "{}"))
            except json.JSONDecodeError:
                pass
    total_see_refs = sum(int(row["see_references"] or 0) for row in rows if (row["see_references"] or "").isdigit())
    total_inheritdoc = sum(int(row["inheritdoc_methods"] or 0) for row in rows if (row["inheritdoc_methods"] or "").isdigit())
    attention = [
        row for row in rows
        if row["status"] != "SUCCESS"
        or row["retry_mode"] != "none"
        or row["failure_codes"] not in {"", "[NONE]", "[CALL_GRAPH_DISABLED]"}
    ]
    lines = [
        "# OE25 Plus Representative Study",
        "",
        "This run tests CoCoMUT on the OE25 repository list plus the representative",
        "public-repository checkouts. It is intentionally written to a separate",
        "experiment directory and does not overwrite the previous experiment folders.",
        "",
        "Default policy: `--compile --resolution auto --call-graph auto`, with",
        "fallbacks to no-call-graph and bounded source-only extraction when needed.",
        "",
        "## Summary",
        "",
        f"- repositories attempted: {len(rows)}",
        f"- status counts: `{dict(status_counts)}`",
        f"- compile result counts: `{dict(compile_counts)}`",
        f"- call graph availability counts: `{dict(call_graph_counts)}`",
        f"- methods identified: {total_methods}",
        f"- JSONL rows emitted: {total_rows}",
        f"- call edges observed: {total_call_edges}",
        f"- call edges with `target_uri`: {total_target_uri_edges} ({percent_string(total_target_uri_edges, total_call_edges) or '-'})",
        f"- call edges joined to source `method_uri`: {total_method_uri_edges} ({percent_string(total_method_uri_edges, total_call_edges) or '-'})",
        f"- call edges classified as project/JDK/external method targets: {total_project_jdk_external_edges} ({percent_string(total_project_jdk_external_edges, total_call_edges) or '-'})",
        f"- ambiguous call edges: {total_ambiguous_edges}",
        f"- `@see` references observed: {total_see_refs}",
        f"- methods using `{{@inheritDoc}}`: {total_inheritdoc}",
        "",
        "## Call-Edge Matching",
        "",
        "`target_uri` is bytecode identity and should be present for every SootUp edge.",
        "`method_uri` is source identity and is present only when the SootUp target",
        "joins to one unique CoCoMUT/Spoon project method.",
        "",
        "Target-kind counts:",
        "",
        "```text",
    ]
    lines.extend(f"{key}: {value}" for key, value in sorted(target_kind_counts.items()))
    lines.extend([
        "```",
        "",
        "Resolution counts:",
        "",
        "```text",
    ])
    lines.extend(f"{key}: {value}" for key, value in sorted(resolution_counts.items()))
    lines.extend([
        "```",
        "",
        "Unresolved-reason counts:",
        "",
        "```text",
    ])
    lines.extend(f"{key}: {value}" for key, value in sorted(unresolved_reason_counts.items()))
    lines.extend([
        "```",
        "",
        "## Attention Cases",
        "",
    ])
    if attention:
        lines.extend([
            "| Set | Repository | Status | Retry | Failure codes | Note |",
            "| --- | --- | --- | --- | --- | --- |",
        ])
        for row in attention[:80]:
            lines.append(
                f"| {row['set']} | `{row['repo']}` | {row['status']} | "
                f"{row['retry_mode']} | `{row['failure_codes']}` | {row['note']} |"
            )
    else:
        lines.append("No attention cases.")
    lines.extend([
        "",
        "## Javadoc Reference Policy",
        "",
        "Any parser changes motivated by this run should be accepted only when they",
        "fit the standard Javadoc doc-comment reference forms documented by Oracle/JDK",
        "standard doclet guides, for example `@see`, `{@link ...}`, and",
        "`{@linkplain ...}` program-element references. Project-specific tag",
        "formatting should be recorded as a limitation, not hard-coded.",
        "",
        "Primary result table: `results.tsv`.",
    ])
    (output_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("experiments/oe25-plus-representative-auto-main"))
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--clone-timeout", type=int, default=600)
    parser.add_argument("--compile-timeout", type=int, default=180)
    parser.add_argument("--heap-gb", type=int, default=2)
    parser.add_argument("--retry-max-source-files", type=int, default=1500)
    parser.add_argument("--retry-max-methods", type=int, default=5000)
    parser.add_argument("--oe25-checkouts-dir", type=Path, default=None,
                        help="Optional existing OE25 checkout directory keyed by owner__repo.")
    parser.add_argument("--representative-checkouts-dir", type=Path, default=None,
                        help="Optional representative checkout directory keyed by owner__repo.")
    parser.add_argument("--min-available-gb", type=float, default=3.0,
                        help="Wait before each target until MemAvailable is at least this value.")
    parser.add_argument("--max-load", type=float, default=6.0,
                        help="Wait before each target until 1-minute load is at most this value.")
    parser.add_argument("--resource-check-interval", type=int, default=30,
                        help="Seconds between resource guard checks.")
    parser.add_argument("--limit", type=int, default=0, help="Limit total targets for smoke testing. 0 means all.")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    output_dir = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
    output_dir = output_dir.resolve()
    experiments_root = (root / "experiments").resolve()
    if output_dir.parent != experiments_root or output_dir.name in PROTECTED_EXPERIMENT_DIRS:
        raise SystemExit(f"Refusing unsafe output directory: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "logs").mkdir(exist_ok=True)

    representative_root = args.representative_checkouts_dir.resolve() if args.representative_checkouts_dir else None
    oe25_root = args.oe25_checkouts_dir.resolve() if args.oe25_checkouts_dir else None
    representative_targets = discover_representative_targets(root, representative_root)
    oe25_targets = [clone_oe25_target(root, output_dir, repo, args.clone_timeout, oe25_root) for repo in OE25_REPOS]
    targets = oe25_targets + representative_targets
    if args.limit and args.limit > 0:
        targets = targets[: args.limit]

    manifest_path = output_dir / "targets.tsv"
    with manifest_path.open("w", encoding="utf-8") as handle:
        handle.write("set\trepo\tproject_path\n")
        for target in targets:
            handle.write(f"{target.dataset}\t{target.repo}\t{target.project_path or ''}\n")

    results_path = output_dir / "results.tsv"
    if not results_path.exists():
        results_path.write_text("\t".join(RESULT_FIELDS) + "\n", encoding="utf-8")
    completed = set()
    with results_path.open(encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if index > 0 and line.strip():
                parts = line.split("\t", 2)
                if len(parts) >= 2:
                    completed.add((parts[0], parts[1]))

    print(f"targets={len(targets)} completed={len(completed)} output={output_dir}", flush=True)
    for index, target in enumerate(targets, 1):
        key = (target.dataset, target.repo)
        if key in completed:
            continue
        wait_for_resources(args)
        row = run_target(root, output_dir, target, args)
        write_row(results_path, row)
        write_summary(output_dir, results_path)
        print(
            f"{index}/{len(targets)} {target.dataset}:{target.repo} {row['status']} "
            f"methods={row.get('methods', '')} see={row.get('see_references', '')} "
            f"cg={row.get('call_graph_available', '')}/{row.get('call_graph_effective_algorithm', '')} "
            f"{row.get('note', '')}",
            flush=True,
        )
    write_summary(output_dir, results_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
