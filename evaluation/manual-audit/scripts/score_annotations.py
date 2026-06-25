#!/usr/bin/env python3
"""Score RQ3 manual-audit annotations and compute Cohen's kappa."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path


LABELS = {"PASS", "FAIL", "NA"}
CATEGORIES = ["method_identity", "doc_refs", "call_context", "inheritdoc"]
ANNOTATOR_COLUMNS = [
    "sample_id",
    "repo",
    "commit_sha",
    "method_uri",
    "jsonl_file",
    "jsonl_line_number",
    *CATEGORIES,
    "notes",
]


def main() -> int:
    args = parse_args()
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    a1 = read_annotations(args.annotator_1)
    a2 = read_annotations(args.annotator_2)
    if set(a1) != set(a2):
        raise SystemExit("annotator CSVs have different sample_id sets")

    rows = []
    for sample_id in sorted(a1):
        row1 = a1[sample_id]
        row2 = a2[sample_id]
        rows.append({
            "sample_id": sample_id,
            "repo": row1["repo"],
            "commit_sha": row1["commit_sha"],
            "method_uri": row1["method_uri"],
            "jsonl_file": row1["jsonl_file"],
            "jsonl_line_number": row1["jsonl_line_number"],
            "annotator_1_overall": overall(row1),
            "annotator_2_overall": overall(row2),
            **{f"annotator_1_{category}": row1[category] for category in CATEGORIES},
            **{f"annotator_2_{category}": row2[category] for category in CATEGORIES},
            "annotator_1_notes": row1.get("notes", ""),
            "annotator_2_notes": row2.get("notes", ""),
        })

    summary = summarize(rows)
    write_json(out_dir / "agreement-summary.json", summary)
    write_markdown(out_dir / "agreement-summary.md", summary)
    disagreements = [row for row in rows if row["annotator_1_overall"] != row["annotator_2_overall"]
                     or any(row[f"annotator_1_{cat}"] != row[f"annotator_2_{cat}"] for cat in CATEGORIES)]
    write_csv(out_dir / "disagreements.csv", disagreement_columns(), disagreements)
    write_adjudication_template(out_dir / "adjudication_template.csv", disagreements)
    if args.adjudicated:
        adjudicated = read_adjudicated(args.adjudicated)
        final_summary = summarize_final(rows, adjudicated)
        write_json(out_dir / "adjudication-summary.json", final_summary)
        write_adjudication_markdown(out_dir / "adjudication-summary.md", final_summary)
    print(f"wrote {out_dir / 'agreement-summary.md'}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotator-1", dest="annotator_1", type=Path, required=True)
    parser.add_argument("--annotator-2", dest="annotator_2", type=Path, required=True)
    parser.add_argument("--adjudicated", type=Path,
                        help="Optional senior-adjudicated CSV produced from adjudication_template.csv")
    parser.add_argument("--out-dir", type=Path, default=Path("evaluation/manual-audit/results"))
    return parser.parse_args()


def read_annotations(path: Path) -> dict[str, dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = [column for column in ANNOTATOR_COLUMNS if column not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"{path}: missing columns {missing}")
        rows = {}
        for row in reader:
            sample_id = row["sample_id"]
            for category in CATEGORIES:
                value = row.get(category, "").strip().upper()
                if value not in LABELS:
                    raise SystemExit(f"{path}:{sample_id}: invalid {category} label {value!r}")
                row[category] = value
            rows[sample_id] = row
        return rows


def overall(row: dict[str, str]) -> str:
    return "YES" if (
        row["method_identity"] == "PASS"
        and row["doc_refs"] in {"PASS", "NA"}
        and row["call_context"] in {"PASS", "NA"}
        and row["inheritdoc"] in {"PASS", "NA"}
    ) else "NO"


def summarize(rows: list[dict[str, str]]) -> dict[str, object]:
    overall_pairs = [(row["annotator_1_overall"], row["annotator_2_overall"]) for row in rows]
    summary: dict[str, object] = {
        "sampled_methods": len(rows),
        "overall_agreement": agreement(overall_pairs),
        "overall_kappa": cohen_kappa(overall_pairs),
        "overall_counts_annotator_1": dict(Counter(first for first, _ in overall_pairs)),
        "overall_counts_annotator_2": dict(Counter(second for _, second in overall_pairs)),
        "number_of_disagreements": sum(1 for first, second in overall_pairs if first != second),
    }
    category_summary = {}
    for category in CATEGORIES:
        pairs = [(row[f"annotator_1_{category}"], row[f"annotator_2_{category}"]) for row in rows]
        filtered = [(a, b) for a, b in pairs if not (a == "NA" and b == "NA")]
        category_summary[category] = {
            "n": len(filtered),
            "agreement": agreement(filtered),
            "kappa": cohen_kappa(filtered),
            "annotator_1_counts": dict(Counter(a for a, _ in pairs)),
            "annotator_2_counts": dict(Counter(b for _, b in pairs)),
        }
    summary["categories"] = category_summary
    return summary


def agreement(pairs: list[tuple[str, str]]) -> float | None:
    if not pairs:
        return None
    return sum(1 for a, b in pairs if a == b) / len(pairs)


def cohen_kappa(pairs: list[tuple[str, str]]) -> float | None:
    if not pairs:
        return None
    observed = agreement(pairs)
    left = Counter(a for a, _ in pairs)
    right = Counter(b for _, b in pairs)
    n = len(pairs)
    labels = set(left) | set(right)
    expected = sum((left[label] / n) * (right[label] / n) for label in labels)
    if expected == 1:
        return 1.0 if observed == 1 else None
    return (observed - expected) / (1 - expected)


def disagreement_columns() -> list[str]:
    return [
        "sample_id",
        "repo",
        "commit_sha",
        "method_uri",
        "jsonl_file",
        "jsonl_line_number",
        "annotator_1_overall",
        "annotator_2_overall",
        *[f"annotator_1_{category}" for category in CATEGORIES],
        *[f"annotator_2_{category}" for category in CATEGORIES],
        "annotator_1_notes",
        "annotator_2_notes",
    ]


def write_adjudication_template(path: Path, disagreements: list[dict[str, str]]) -> None:
    columns = [
        "sample_id",
        "repo",
        "commit_sha",
        "method_uri",
        "jsonl_file",
        "jsonl_line_number",
        "final_method_identity",
        "final_doc_refs",
        "final_call_context",
        "final_inheritdoc",
        "senior_notes",
    ]
    rows = [{
        "sample_id": row["sample_id"],
        "repo": row["repo"],
        "commit_sha": row["commit_sha"],
        "method_uri": row["method_uri"],
        "jsonl_file": row["jsonl_file"],
        "jsonl_line_number": row["jsonl_line_number"],
        "final_method_identity": "",
        "final_doc_refs": "",
        "final_call_context": "",
        "final_inheritdoc": "",
        "senior_notes": "",
    } for row in disagreements]
    write_csv(path, columns, rows)


def read_adjudicated(path: Path) -> list[dict[str, str]]:
    required = [
        "sample_id",
        "final_method_identity",
        "final_doc_refs",
        "final_call_context",
        "final_inheritdoc",
    ]
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = [column for column in required if column not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"{path}: missing columns {missing}")
        rows = []
        for row in reader:
            for column in required[1:]:
                value = row.get(column, "").strip().upper()
                if value not in LABELS:
                    raise SystemExit(f"{path}:{row.get('sample_id', '')}: invalid {column} label {value!r}")
                row[column] = value
            rows.append(row)
        return rows


def summarize_final(rows: list[dict[str, str]], adjudicated_rows: list[dict[str, str]]) -> dict[str, object]:
    adjudicated = {row["sample_id"]: row for row in adjudicated_rows}
    final_rows = []
    for row in rows:
        sample_id = row["sample_id"]
        if sample_id in adjudicated:
            final_rows.append(adjudicated[sample_id])
        else:
            final_rows.append({
                "sample_id": sample_id,
                "final_method_identity": row["annotator_1_method_identity"],
                "final_doc_refs": row["annotator_1_doc_refs"],
                "final_call_context": row["annotator_1_call_context"],
                "final_inheritdoc": row["annotator_1_inheritdoc"],
            })
    return summarize_final_labels(final_rows, len(adjudicated))


def summarize_final_labels(rows: list[dict[str, str]], adjudicated_count: int) -> dict[str, object]:
    category_columns = {
        "method_identity": "final_method_identity",
        "doc_refs": "final_doc_refs",
        "call_context": "final_call_context",
        "inheritdoc": "final_inheritdoc",
    }
    overall_labels = [overall({
        "method_identity": row["final_method_identity"],
        "doc_refs": row["final_doc_refs"],
        "call_context": row["final_call_context"],
        "inheritdoc": row["final_inheritdoc"],
    }) for row in rows]
    summary: dict[str, object] = {
        "sampled_methods": len(rows),
        "adjudicated_cases": adjudicated_count,
        "overall_correct_yes": overall_labels.count("YES"),
        "overall_correct_no": overall_labels.count("NO"),
        "overall_correct_rate": overall_labels.count("YES") / len(rows) if rows else None,
    }
    categories = {}
    for name, column in category_columns.items():
        labels = [row[column] for row in rows]
        applicable = [label for label in labels if label != "NA"]
        categories[name] = {
            "applicable": len(applicable),
            "pass": applicable.count("PASS"),
            "fail": applicable.count("FAIL"),
            "na": labels.count("NA"),
            "pass_rate": applicable.count("PASS") / len(applicable) if applicable else None,
        }
    summary["categories"] = categories
    return summary


def write_csv(path: Path, columns: list[str], rows: list[dict[str, object]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_markdown(path: Path, summary: dict[str, object]) -> None:
    def fmt(value: object) -> str:
        if value is None:
            return "NA"
        if isinstance(value, float):
            return f"{value:.4f}"
        return str(value)
    lines = [
        "# Manual Audit Agreement Summary",
        "",
        f"- Sampled methods: {summary['sampled_methods']}",
        f"- Overall agreement: {fmt(summary['overall_agreement'])}",
        f"- Cohen's kappa on overall_correct: {fmt(summary['overall_kappa'])}",
        f"- Overall disagreements: {summary['number_of_disagreements']}",
        "",
        "| Category | N | Agreement | Cohen's kappa |",
        "| --- | ---: | ---: | ---: |",
    ]
    categories = summary["categories"]
    assert isinstance(categories, dict)
    for category in CATEGORIES:
        item = categories[category]
        lines.append(f"| {category} | {item['n']} | {fmt(item['agreement'])} | {fmt(item['kappa'])} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_adjudication_markdown(path: Path, summary: dict[str, object]) -> None:
    def fmt(value: object) -> str:
        if value is None:
            return "NA"
        if isinstance(value, float):
            return f"{value:.4f}"
        return str(value)
    lines = [
        "# Manual Audit Adjudication Summary",
        "",
        f"- Sampled methods: {summary['sampled_methods']}",
        f"- Adjudicated cases: {summary['adjudicated_cases']}",
        f"- Overall correct: {summary['overall_correct_yes']}",
        f"- Overall incorrect: {summary['overall_correct_no']}",
        f"- Overall correctness rate: {fmt(summary['overall_correct_rate'])}",
        "",
        "| Category | Applicable | Pass | Fail | NA | Pass rate |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    categories = summary["categories"]
    assert isinstance(categories, dict)
    for category in CATEGORIES:
        item = categories[category]
        lines.append(
            f"| {category} | {item['applicable']} | {item['pass']} | {item['fail']} | "
            f"{item['na']} | {fmt(item['pass_rate'])} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
