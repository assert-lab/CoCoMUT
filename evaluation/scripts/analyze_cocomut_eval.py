#!/usr/bin/env python3
"""Aggregate reduced CoCoMUT evaluation results."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


RQ1_COLUMNS = [
    "build_system",
    "repos_attempted",
    "repos_success",
    "repos_partial",
    "repos_failed",
    "build_success",
    "bytecode_available",
    "call_graph_available",
    "source_files_discovered",
    "source_files_parsed",
    "source_parse_rate",
    "methods_identified",
    "focal_methods_matched_to_bytecode",
    "focal_bytecode_match_rate",
    "contexts_extracted",
    "jsonl_rows",
    "call_edges_serialized",
    "median_duration_ms",
]


RQ2_COLUMNS = [
    "build_system",
    "serialized_edges",
    "unique_directed_relations",
    "edges_with_target_uri",
    "target_uri_rate",
    "edges_with_method_uri",
    "source_join_rate",
    "edges_without_source_uri",
    "project_target_edges",
    "project_target_edges_with_method_uri",
    "recognized_project_target_join_rate",
    "ambiguous_edges",
    "candidate_edges",
    "project_method_edges",
    "unresolved_project_method_edges",
    "jdk_method_edges",
    "external_method_edges",
    "synthetic_or_compiler_edges",
    "invokedynamic_edges",
    "median_serialized_edges",
    "iqr_serialized_edges",
    "median_unique_directed_relations",
    "iqr_unique_directed_relations",
    "median_source_join_rate",
    "iqr_source_join_rate",
    "median_recognized_project_target_join_rate",
    "iqr_recognized_project_target_join_rate",
]


def main() -> int:
    args = parse_args()
    results_dir = args.results_dir.resolve()
    repo_rows = read_tsv(results_dir / "repository-results.tsv")
    edge_rows = read_tsv(results_dir / "call-edge-results.tsv")
    if not repo_rows:
        raise SystemExit("repository-results.tsv has no rows")
    if not edge_rows:
        raise SystemExit("call-edge-results.tsv has no rows")

    rq1 = [rq1_row(label, subset(repo_rows, label)) for label in ["maven", "gradle"]]
    rq1.append(rq1_row("total", repo_rows))
    rq2 = [rq2_row(label, subset(edge_rows, label)) for label in ["maven", "gradle"]]
    rq2.append(rq2_row("total", edge_rows))

    write_tsv(results_dir / "rq1-table.tsv", RQ1_COLUMNS, rq1)
    write_tsv(results_dir / "rq2-table.tsv", RQ2_COLUMNS, rq2)
    write_summary(results_dir / "aggregate-summary.md", repo_rows, edge_rows, rq1, rq2)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", type=Path, default=Path("evaluation/results"))
    return parser.parse_args()


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def write_tsv(path: Path, columns: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, delimiter="\t",
                                extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def subset(rows: list[dict[str, str]], build_system: str) -> list[dict[str, str]]:
    return [row for row in rows if row.get("build_system") == build_system]


def rq1_row(label: str, rows: list[dict[str, str]]) -> dict[str, Any]:
    durations = [to_int(row.get("duration_ms")) for row in rows if to_int(row.get("duration_ms")) is not None]
    return {
        "build_system": label,
        "repos_attempted": len(rows),
        "repos_success": count_eq(rows, "status", "SUCCESS"),
        "repos_partial": count_eq(rows, "status", "PARTIAL"),
        "repos_failed": sum(1 for row in rows if row.get("status") in {"FAILED", "ERROR", "TIMEOUT"} or row.get("status", "").startswith("EXIT_")),
        "build_success": count_bool(rows, "phase_1_build_succeeded"),
        "bytecode_available": count_bool(rows, "phase_1_bytecode_available"),
        "call_graph_available": count_bool(rows, "phase_3_available"),
        "source_files_discovered": sum_int(rows, "source_files_discovered"),
        "source_files_parsed": sum_int(rows, "source_files_parsed"),
        "source_parse_rate": rate(sum_int(rows, "source_files_parsed"), sum_int(rows, "source_files_discovered")),
        "methods_identified": sum_int(rows, "phase_2_methods_identified"),
        "focal_methods_matched_to_bytecode": sum_int(rows, "phase_3_focal_methods_matched_to_bytecode"),
        "focal_bytecode_match_rate": rate(
            sum_int(rows, "phase_3_focal_methods_matched_to_bytecode"),
            sum_int(rows, "phase_2_methods_identified"),
        ),
        "contexts_extracted": sum_int(rows, "phase_4_contexts_extracted"),
        "jsonl_rows": sum_int(rows, "phase_5_jsonl_rows"),
        "call_edges_serialized": sum_int(rows, "phase_5_call_edges_serialized"),
        "median_duration_ms": int(statistics.median(durations)) if durations else "",
    }


def rq2_row(label: str, rows: list[dict[str, str]]) -> dict[str, Any]:
    total = sum_int(rows, "serialized_edges")
    with_target = sum_int(rows, "edges_with_target_uri")
    with_method = sum_int(rows, "edges_with_method_uri")
    project_targets = sum_int(rows, "project_target_edges")
    project_targets_with_method = sum_int(rows, "project_target_edges_with_method_uri")
    return {
        "build_system": label,
        "serialized_edges": total,
        "unique_directed_relations": sum_int(rows, "unique_directed_relations"),
        "edges_with_target_uri": with_target,
        "target_uri_rate": rate(with_target, total),
        "edges_with_method_uri": with_method,
        "source_join_rate": rate(with_method, with_target),
        "edges_without_source_uri": sum_int(rows, "edges_without_source_uri"),
        "project_target_edges": project_targets,
        "project_target_edges_with_method_uri": project_targets_with_method,
        "recognized_project_target_join_rate": rate(project_targets_with_method, project_targets),
        "ambiguous_edges": sum_int(rows, "ambiguous_edges"),
        "candidate_edges": sum_int(rows, "candidate_edges"),
        "project_method_edges": sum_int(rows, "project_method_edges"),
        "unresolved_project_method_edges": sum_int(rows, "unresolved_project_method_edges"),
        "jdk_method_edges": sum_int(rows, "jdk_method_edges"),
        "external_method_edges": sum_int(rows, "external_method_edges"),
        "synthetic_or_compiler_edges": sum_int(rows, "synthetic_or_compiler_edges"),
        "invokedynamic_edges": sum_int(rows, "invokedynamic_edges"),
        "median_serialized_edges": median_int(rows, "serialized_edges"),
        "iqr_serialized_edges": iqr_int(rows, "serialized_edges"),
        "median_unique_directed_relations": median_int(rows, "unique_directed_relations"),
        "iqr_unique_directed_relations": iqr_int(rows, "unique_directed_relations"),
        "median_source_join_rate": median_float(rows, "source_join_rate"),
        "iqr_source_join_rate": iqr_float(rows, "source_join_rate"),
        "median_recognized_project_target_join_rate": median_float(rows, "recognized_project_target_join_rate"),
        "iqr_recognized_project_target_join_rate": iqr_float(rows, "recognized_project_target_join_rate"),
    }


def write_summary(path: Path,
                  repo_rows: list[dict[str, str]],
                  edge_rows: list[dict[str, str]],
                  rq1: list[dict[str, Any]],
                  rq2: list[dict[str, Any]]) -> None:
    lines: list[str] = []
    lines.append("# CoCoMUT Evaluation Summary")
    lines.append("")
    lines.append("## Subjects")
    lines.append(f"- {len(repo_rows)} repositories")
    lines.append(f"- {count_eq(repo_rows, 'build_system', 'maven')} Maven")
    lines.append(f"- {count_eq(repo_rows, 'build_system', 'gradle')} Gradle")
    lines.append("")
    lines.append("## RQ1: Build-Ecosystem Robustness")
    total = rq1[-1]
    lines.append(f"- Completion: {total['repos_success']} SUCCESS, {total['repos_partial']} PARTIAL, {total['repos_failed']} failed/error/timeout.")
    lines.append(f"- Build success: {total['build_success']} / {total['repos_attempted']} repositories.")
    lines.append(f"- Bytecode available: {total['bytecode_available']} / {total['repos_attempted']} repositories.")
    lines.append(f"- Call graph available: {total['call_graph_available']} / {total['repos_attempted']} repositories.")
    lines.append(f"- Source parsing: {total['source_files_parsed']} / {total['source_files_discovered']} files ({percent(total['source_parse_rate'])}).")
    lines.append(f"- Focal methods matched to bytecode: {total['focal_methods_matched_to_bytecode']} / {total['methods_identified']} ({percent(total['focal_bytecode_match_rate'])}).")
    lines.append(f"- Methods identified: {total['methods_identified']}; contexts extracted: {total['contexts_extracted']}; JSONL rows: {total['jsonl_rows']}.")
    lines.append("")
    lines.extend(markdown_table(RQ1_COLUMNS, rq1))
    lines.append("")
    lines.append("## RQ2: Source-Bytecode Reconciliation and Abstention")
    total2 = rq2[-1]
    lines.append(f"- Serialized call-edge adjacency entries: {total2['serialized_edges']}.")
    lines.append(f"- Unique directed relations: {total2['unique_directed_relations']}.")
    lines.append(f"- `target_uri` coverage: {percent(total2['target_uri_rate'])}.")
    lines.append(f"- All-edge source-join rate: {percent(total2['source_join_rate'])}.")
    lines.append(f"- Recognized-project-target join rate: {percent(total2['recognized_project_target_join_rate'])}.")
    lines.append(f"- Edges without source URI: {total2['edges_without_source_uri']}; ambiguous edges: {total2['ambiguous_edges']}; candidate edges: {total2['candidate_edges']}.")
    lines.append("")
    lines.extend(markdown_table(RQ2_COLUMNS, rq2))
    lines.append("")
    for label in ["total", "maven", "gradle"]:
        rows = edge_rows if label == "total" else subset(edge_rows, label)
        lines.append(f"### {label.title()} Distributions")
        lines.append("")
        lines.append("Target-kind distribution:")
        lines.extend(counter_lines(merge_json_counters(rows, "target_kind_counts_json")))
        lines.append("")
        lines.append("Resolution distribution:")
        lines.extend(counter_lines(merge_json_counters(rows, "resolution_counts_json")))
        lines.append("")
        lines.append("Unresolved-reason distribution:")
        lines.extend(counter_lines(merge_json_counters(rows, "unresolved_reason_counts_json")))
        lines.append("")
    lines.append("## Interpretation")
    lines.append("- RQ1 measures construction robustness, not semantic correctness.")
    lines.append("- A repository can build and emit method-context JSONL while still being `PARTIAL` because source parsing or focal-method bytecode matching is incomplete.")
    lines.append("- RQ2 measures deterministic source-join behavior and abstention taxonomy, not manual accuracy.")
    lines.append("- `source_join_rate` is not recall or correctness; it is the fraction of bytecode targets with deterministic source-backed `method_uri` joins.")
    lines.append("- `edges_without_source_uri` includes intentional non-source targets such as JDK, external-library, synthetic/compiler, and invokedynamic targets.")
    path.write_text("\n".join(lines) + "\n")


def markdown_table(columns: list[str], rows: list[dict[str, Any]]) -> list[str]:
    lines = ["| " + " | ".join(columns) + " |", "| " + " | ".join("---" for _ in columns) + " |"]
    for row in rows:
        lines.append("| " + " | ".join(str(row.get(column, "")) for column in columns) + " |")
    return lines


def counter_lines(counter: Counter[str]) -> list[str]:
    if not counter:
        return ["- none"]
    return [f"- `{key or '<empty>'}`: {value}" for key, value in counter.most_common()]


def merge_json_counters(rows: list[dict[str, str]], column: str) -> Counter[str]:
    counter: Counter[str] = Counter()
    for row in rows:
        raw = row.get(column) or "{}"
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            data = {}
        for key, value in data.items():
            counter[key] += int(value)
    return counter


def count_eq(rows: list[dict[str, str]], column: str, value: str) -> int:
    return sum(1 for row in rows if row.get(column) == value)


def count_bool(rows: list[dict[str, str]], column: str) -> int:
    return sum(1 for row in rows if str(row.get(column)).lower() == "true")


def sum_int(rows: list[dict[str, str]], column: str) -> int:
    return sum(value for row in rows if (value := to_int(row.get(column))) is not None)


def median_int(rows: list[dict[str, str]], column: str) -> str:
    values = [value for row in rows if (value := to_int(row.get(column))) is not None]
    return str(int(statistics.median(values))) if values else ""


def iqr_int(rows: list[dict[str, str]], column: str) -> str:
    values = [float(value) for row in rows if (value := to_int(row.get(column))) is not None]
    return format_float(iqr(values))


def median_float(rows: list[dict[str, str]], column: str) -> str:
    values = [value for row in rows if (value := to_float(row.get(column))) is not None]
    return format_float(statistics.median(values)) if values else ""


def iqr_float(rows: list[dict[str, str]], column: str) -> str:
    values = [value for row in rows if (value := to_float(row.get(column))) is not None]
    return format_float(iqr(values))


def iqr(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return percentile(ordered, 0.75) - percentile(ordered, 0.25)


def percentile(values: list[float], quantile: float) -> float:
    if len(values) == 1:
        return values[0]
    position = (len(values) - 1) * quantile
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    fraction = position - lower
    return values[lower] + (values[upper] - values[lower]) * fraction


def format_float(value: float | None) -> str:
    if value is None:
        return ""
    return f"{value:.6f}"


def to_int(value: str | None) -> int | None:
    if value in ("", None):
        return None
    try:
        return int(str(value))
    except ValueError:
        return None


def to_float(value: str | None) -> float | None:
    if value in ("", None):
        return None
    try:
        return float(str(value))
    except ValueError:
        return None


def rate(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return ""
    return f"{numerator / denominator:.6f}"


def percent(value: Any) -> str:
    if value in ("", None):
        return ""
    return f"{float(value) * 100:.2f}%"


if __name__ == "__main__":
    raise SystemExit(main())
