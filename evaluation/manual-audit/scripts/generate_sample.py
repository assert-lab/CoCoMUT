#!/usr/bin/env python3
"""Generate the RQ3 200-method manual-audit sample."""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


SAMPLE_COLUMNS = [
    "sample_id",
    "repo",
    "commit_sha",
    "method_uri",
    "jsonl_file",
    "jsonl_line_number",
]

ANNOTATOR_COLUMNS = [
    *SAMPLE_COLUMNS,
    "method_identity",
    "doc_refs",
    "call_context",
    "inheritdoc",
    "notes",
]


@dataclass(frozen=True)
class RepoInput:
    repo: str
    safe_name: str
    commit_sha: str
    jsonl_rows: int
    files: tuple[Path, ...]


def main() -> int:
    args = parse_args()
    audit_root = args.audit_root
    selected_repos = read_repo_list(args.repos_file)
    subjects = read_subjects(args.subjects)
    manifest = read_jsonl_manifest(audit_root / "jsonl" / "jsonl-manifest.tsv")
    repo_inputs = []
    for repo in selected_repos:
        if repo not in subjects:
            raise SystemExit(f"{repo}: not found in {args.subjects}")
        repo_inputs.append(repo_input(audit_root, repo, subjects[repo]["commit_sha"], manifest))

    allocation = allocate(repo_inputs, args.sample_size)
    rng = random.Random(args.seed)
    sample_rows: list[dict[str, str]] = []
    sample_records: list[dict] = []
    allocation_rows = []
    sample_id = 1
    for item in repo_inputs:
        count = allocation[item.repo]
        selected_lines = sorted(rng.sample(range(1, item.jsonl_rows + 1), count))
        selected_set = set(selected_lines)
        records_by_line = read_selected_records(item.files, selected_set)
        allocation_rows.append({
            "repo": item.repo,
            "commit_sha": item.commit_sha,
            "jsonl_rows": item.jsonl_rows,
            "sample_count": count,
        })
        for line_number in selected_lines:
            record = records_by_line[line_number]
            method_uri = method_uri_of(record)
            sample_record = dict(record)
            sample_record.setdefault("manual_audit", {})
            sample_record["manual_audit"].update({
                "sample_id": f"S{sample_id:03d}",
                "repo": item.repo,
                "commit_sha": item.commit_sha,
                "original_jsonl_file": display_jsonl_path(audit_root, item),
                "original_jsonl_line_number": line_number,
            })
            sample_rows.append({
                "sample_id": f"S{sample_id:03d}",
                "repo": item.repo,
                "commit_sha": item.commit_sha,
                "method_uri": method_uri,
                "jsonl_file": display_jsonl_path(audit_root, item),
                "jsonl_line_number": str(line_number),
            })
            sample_records.append(sample_record)
            sample_id += 1

    write_csv(audit_root / "sample_200.csv", SAMPLE_COLUMNS, sample_rows)
    blank_annotator_rows = [dict(row, method_identity="", doc_refs="", call_context="", inheritdoc="", notes="")
                            for row in sample_rows]
    write_csv(audit_root / "annotator_1.csv", ANNOTATOR_COLUMNS, blank_annotator_rows)
    write_csv(audit_root / "annotator_2.csv", ANNOTATOR_COLUMNS, blank_annotator_rows)
    write_csv(audit_root / "sample-allocation.tsv",
              ["repo", "commit_sha", "jsonl_rows", "sample_count"],
              allocation_rows, delimiter="\t")
    with (audit_root / "sample_200.jsonl").open("w", encoding="utf-8") as handle:
        for record in sample_records:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
    write_manifest(audit_root, args, repo_inputs, allocation, sample_rows)
    print(f"wrote {len(sample_rows)} samples to {audit_root / 'sample_200.csv'}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit-root", type=Path, default=Path("evaluation/manual-audit"))
    parser.add_argument("--repos-file", type=Path, default=Path("evaluation/manual-audit/audit-repos.txt"))
    parser.add_argument("--subjects", type=Path, default=Path("evaluation/subjects.csv"))
    parser.add_argument("--sample-size", type=int, default=200)
    parser.add_argument("--seed", type=int, default=20260626)
    return parser.parse_args()


def read_repo_list(path: Path) -> list[str]:
    repos = [line.strip() for line in path.read_text().splitlines()
             if line.strip() and not line.strip().startswith("#")]
    if len(repos) != 10:
        raise SystemExit(f"{path}: expected exactly 10 repositories, found {len(repos)}")
    return repos


def read_subjects(path: Path) -> dict[str, dict[str, str]]:
    with path.open(newline="") as handle:
        return {row["repo"]: row for row in csv.DictReader(handle)}


def read_jsonl_manifest(path: Path) -> dict[str, dict[str, str]]:
    with path.open(newline="") as handle:
        return {row["repo"]: row for row in csv.DictReader(handle, delimiter="\t")}


def repo_input(audit_root: Path, repo: str, commit_sha: str, manifest: dict[str, dict[str, str]]) -> RepoInput:
    if repo not in manifest:
        raise SystemExit(f"{repo}: missing from jsonl manifest")
    row = manifest[repo]
    safe_name = row["safe_name"]
    repo_dir = audit_root / "jsonl" / safe_name
    parts = []
    storage = row["storage"].split(";")
    for name in storage:
        path = repo_dir / name
        if not path.exists():
            raise SystemExit(f"{repo}: missing JSONL storage file {path}")
        parts.append(path)
    return RepoInput(repo=repo, safe_name=safe_name, commit_sha=commit_sha,
                     jsonl_rows=int(row["jsonl_rows"]), files=tuple(parts))


def allocate(inputs: list[RepoInput], sample_size: int) -> dict[str, int]:
    total = sum(item.jsonl_rows for item in inputs)
    exact = {item.repo: item.jsonl_rows / total * sample_size for item in inputs}
    counts = {repo: int(value + 0.5) for repo, value in exact.items()}
    while sum(counts.values()) < sample_size:
        repo = max(exact, key=lambda key: (exact[key] - counts[key], exact[key], key))
        counts[repo] += 1
    while sum(counts.values()) > sample_size:
        removable = [repo for repo, count in counts.items() if count > 0]
        repo = min(removable, key=lambda key: (exact[key] - counts[key], exact[key], key))
        counts[repo] -= 1
    return counts


def read_selected_records(files: tuple[Path, ...], selected_lines: set[int]) -> dict[int, dict]:
    records: dict[int, dict] = {}
    for line_number, line in enumerate(open_jsonl_gzip(files), 1):
        if line_number in selected_lines:
            records[line_number] = json.loads(line)
        if len(records) == len(selected_lines):
            break
    missing = selected_lines - set(records)
    if missing:
        raise SystemExit(f"missing selected lines: {sorted(missing)[:10]}")
    return records


def open_jsonl_gzip(files: tuple[Path, ...]) -> Iterable[str]:
    if len(files) == 1 and files[0].suffix == ".gz":
        with gzip.open(files[0], "rt", encoding="utf-8") as handle:
            yield from handle
        return
    def bytes_iter() -> Iterable[bytes]:
        for path in files:
            with path.open("rb") as handle:
                while chunk := handle.read(1024 * 1024):
                    yield chunk
    class ChunkReader(io.RawIOBase):
        def __init__(self) -> None:
            self._chunks = iter(bytes_iter())
            self._buffer = b""
        def readable(self) -> bool:
            return True
        def readinto(self, target: bytearray) -> int:
            while not self._buffer:
                try:
                    self._buffer = next(self._chunks)
                except StopIteration:
                    return 0
            n = min(len(target), len(self._buffer))
            target[:n] = self._buffer[:n]
            self._buffer = self._buffer[n:]
            return n
    with gzip.open(ChunkReader(), "rt", encoding="utf-8") as handle:
        yield from handle


def method_uri_of(record: dict) -> str:
    mut = record.get("MUT") or {}
    return str(mut.get("method_uri") or "")


def display_jsonl_path(audit_root: Path, item: RepoInput) -> str:
    if len(item.files) == 1:
        return str(item.files[0].relative_to(audit_root))
    return str((audit_root / "jsonl" / item.safe_name / "method_contexts.jsonl.gz.part-*").relative_to(audit_root))


def write_csv(path: Path, columns: list[str], rows: list[dict[str, object]], delimiter: str = ",") -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, delimiter=delimiter, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_manifest(audit_root: Path,
                   args: argparse.Namespace,
                   inputs: list[RepoInput],
                   allocation: dict[str, int],
                   sample_rows: list[dict[str, str]]) -> None:
    digest = hashlib.sha256()
    for row in sample_rows:
        digest.update(json.dumps(row, sort_keys=True).encode("utf-8"))
        digest.update(b"\n")
    manifest = {
        "sample_size": args.sample_size,
        "random_seed": args.seed,
        "repository_count": len(inputs),
        "repositories": [
            {
                "repo": item.repo,
                "commit_sha": item.commit_sha,
                "jsonl_rows": item.jsonl_rows,
                "sample_count": allocation[item.repo],
            }
            for item in inputs
        ],
        "sample_csv_sha256": digest.hexdigest(),
    }
    (audit_root / "sample-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
