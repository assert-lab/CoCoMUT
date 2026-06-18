#!/usr/bin/env python3
"""Run CoCoX on OE25 plus the representative public-repo checkouts.

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


def discover_representative_targets(root: Path) -> list[Target]:
    checkout_root = root / "experiments" / "expanded-public-repos-auto-main-representative" / "checkouts"
    targets: list[Target] = []
    for checkout in sorted(checkout_root.iterdir()):
        if not checkout.is_dir():
            continue
        repo = checkout.name.replace("__", "/", 1)
        targets.append(Target("representative", repo, checkout))
    return targets


def clone_oe25_target(root: Path, output_dir: Path, repo: str, timeout: int) -> Target:
    checkout = output_dir / "checkouts" / repo.replace("/", "__")
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


def run_cocox(
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
        str(root / "bin" / "cocox"),
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

    CoCoX is a Java static-analysis tool. For field testing, we avoid invoking
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
    return {
        "source_backend_modes": ",".join(sorted(source_modes)),
        "see_methods": str(see_methods),
        "see_references": str(see_references),
        "see_resolution_counts": json.dumps(dict(sorted(see_resolution_counts.items())), sort_keys=True),
        "see_kind_counts": json.dumps(dict(sorted(see_kind_counts.items())), sort_keys=True),
        "inline_link_methods": str(inline_link_methods),
        "inline_link_references": str(inline_link_references),
        "inheritdoc_methods": str(inheritdoc_methods),
        "inheritdoc_with_candidates": str(inheritdoc_with_candidates),
    }


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
    env.setdefault("COCOX_COMPILE_TIMEOUT_SECONDS", str(args.compile_timeout))
    start = time.time()
    compile_project = not has_frontend_package_manager(target.project_path)

    log_path = logs / f"{target.dataset}__{target.safe_name}.cocox.log"
    status, report, tail = run_cocox(
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
        status, report, tail = run_cocox(
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
        status, report, tail = run_cocox(
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
        "This run tests CoCoX on the OE25 repository list plus the representative",
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
        f"- `@see` references observed: {total_see_refs}",
        f"- methods using `{{@inheritDoc}}`: {total_inheritdoc}",
        "",
        "## Attention Cases",
        "",
    ]
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

    representative_targets = discover_representative_targets(root)
    oe25_targets = [clone_oe25_target(root, output_dir, repo, args.clone_timeout) for repo in OE25_REPOS]
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
