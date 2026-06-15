#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_ROOT/experiments/expanded-public-repos-auto-main}"
CSV="${CSV:-$PROJECT_ROOT/../cleaned_mined_repos.csv}"
LIMIT="${LIMIT:-0}"
TIMEOUT="${TIMEOUT:-420}"
COMPILE_TIMEOUT="${COMPILE_TIMEOUT:-60}"
MAX_SIZE_KB="${MAX_SIZE_KB:-300000}"
RETRY_MAX_SOURCE_FILES="${RETRY_MAX_SOURCE_FILES:-1500}"
RETRY_MAX_METHODS="${RETRY_MAX_METHODS:-5000}"
SOURCE_SET="${SOURCE_SET:-main}"
RESOLUTION="${RESOLUTION:-auto}"
CALL_GRAPH="${CALL_GRAPH:-auto}"
JAVA_HOME_VALUE="${JAVA_HOME_VALUE:-/usr/lib/jvm/java-17-openjdk}"
INCLUDE_ANDROID="${INCLUDE_ANDROID:-1}"
BUILD_JAR="${BUILD_JAR:-1}"

cd "$PROJECT_ROOT"

mkdir -p "$OUTPUT_DIR"

{
  echo "started_at=$(date -Is)"
  echo "project_root=$PROJECT_ROOT"
  echo "git_commit=$(git rev-parse HEAD 2>/dev/null || true)"
  echo "git_branch=$(git branch --show-current 2>/dev/null || true)"
  echo "output_dir=$OUTPUT_DIR"
  echo "csv=$CSV"
  echo "limit=$LIMIT"
  echo "timeout=$TIMEOUT"
  echo "compile_timeout=$COMPILE_TIMEOUT"
  echo "max_size_kb=$MAX_SIZE_KB"
  echo "retry_max_source_files=$RETRY_MAX_SOURCE_FILES"
  echo "retry_max_methods=$RETRY_MAX_METHODS"
  echo "source_set=$SOURCE_SET"
  echo "resolution=$RESOLUTION"
  echo "call_graph=$CALL_GRAPH"
  echo "java_home=$JAVA_HOME_VALUE"
  echo "include_android=$INCLUDE_ANDROID"
} > "$OUTPUT_DIR/run_manifest.txt"

if [ "$BUILD_JAR" = "1" ]; then
  scripts/build_release_jar.sh
fi

INCLUDE_ANDROID_FLAG=""
if [ "$INCLUDE_ANDROID" = "1" ]; then
  INCLUDE_ANDROID_FLAG="--include-android"
fi

python scripts/field_test_public_repos.py \
  --csv "$CSV" \
  --limit "$LIMIT" \
  --timeout "$TIMEOUT" \
  $INCLUDE_ANDROID_FLAG \
  --max-size-kb "$MAX_SIZE_KB" \
  --resolution "$RESOLUTION" \
  --call-graph "$CALL_GRAPH" \
  --source-set "$SOURCE_SET" \
  --compile-timeout "$COMPILE_TIMEOUT" \
  --retry-max-source-files "$RETRY_MAX_SOURCE_FILES" \
  --retry-max-methods "$RETRY_MAX_METHODS" \
  --java-home "$JAVA_HOME_VALUE" \
  --output-dir "$OUTPUT_DIR"

python scripts/summarize_field_tests.py \
  "$OUTPUT_DIR/results.tsv" \
  --output "$OUTPUT_DIR/summary.md" \
  --title "Expanded Auto Field Study"

python scripts/extract_javadoc_tag_cases.py \
  --output-dir "$OUTPUT_DIR" \
  --csv "$OUTPUT_DIR/javadoc_tag_cases.csv"

python - "$OUTPUT_DIR" <<'PY'
import csv
import json
import sys
from collections import Counter
from pathlib import Path

output = Path(sys.argv[1])
results_tsv = output / "results.tsv"
results_csv = output / "field_test_results_auto.csv"
sample_csv = output / "javadoc_tag_cases_sample.csv"
summary_txt = output / "summary_counts.txt"

with results_tsv.open(encoding="utf-8", newline="") as handle:
    rows = list(csv.DictReader(handle, delimiter="\t"))

if rows:
    with results_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

def iv(value):
    try:
        return int(float(value or "0"))
    except ValueError:
        return 0

tag_path = output / "javadoc_tag_cases.csv"
tag_rows = []
if tag_path.exists():
    with tag_path.open(encoding="utf-8", newline="") as handle:
        tag_rows = list(csv.DictReader(handle))

sample = []
seen = set()
for row in tag_rows:
    keep = row.get("see") != "[]" or (
        row.get("uses_inheritdoc") == "true"
        and iv(row.get("inherited_candidate_count")) > 0
    )
    key = (row.get("repo"), row.get("method_uri"))
    if keep and key not in seen:
        sample.append(row)
        seen.add(key)
    if len(sample) >= 500:
        break

if tag_rows:
    with sample_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=tag_rows[0].keys())
        writer.writeheader()
        writer.writerows(sample)

status = Counter(row.get("status", "") for row in rows)
compile_status = Counter(row.get("compiles", "") for row in rows)
call_graph = Counter(row.get("call_graph_available", "") for row in rows)
retry = Counter(row.get("retry_mode", "") for row in rows)
inheritdoc_resolution = Counter(
    row.get("inheritdoc_resolution", "")
    for row in tag_rows
    if row.get("uses_inheritdoc") == "true"
)

with summary_txt.open("w", encoding="utf-8") as handle:
    handle.write(f"repositories={len(rows)}\n")
    handle.write(f"status={dict(status)}\n")
    handle.write(f"compiles={dict(compile_status)}\n")
    handle.write(f"call_graph_available={dict(call_graph)}\n")
    handle.write(f"retry={dict(retry)}\n")
    handle.write(f"methods={sum(iv(row.get('methods')) for row in rows)}\n")
    handle.write(f"jsonl_rows={sum(iv(row.get('jsonl_rows')) for row in rows)}\n")
    handle.write(f"javadoc_see_methods={sum(iv(row.get('javadoc_see_methods')) for row in rows)}\n")
    handle.write(f"javadoc_inheritdoc_methods={sum(iv(row.get('javadoc_inheritdoc_methods')) for row in rows)}\n")
    handle.write(f"javadoc_inheritdoc_with_candidates={sum(iv(row.get('javadoc_inheritdoc_with_candidates')) for row in rows)}\n")
    handle.write(f"javadoc_tag_cases={len(tag_rows)}\n")
    handle.write(f"inheritdoc_resolution={dict(inheritdoc_resolution)}\n")

print(f"wrote={results_csv}")
print(f"wrote={sample_csv}")
print(f"wrote={summary_txt}")
PY

{
  echo "finished_at=$(date -Is)"
  echo "results_tsv=$OUTPUT_DIR/results.tsv"
  echo "results_csv=$OUTPUT_DIR/field_test_results_auto.csv"
  echo "summary=$OUTPUT_DIR/summary.md"
  echo "summary_counts=$OUTPUT_DIR/summary_counts.txt"
  echo "javadoc_tag_cases=$OUTPUT_DIR/javadoc_tag_cases.csv"
  echo "javadoc_tag_cases_sample=$OUTPUT_DIR/javadoc_tag_cases_sample.csv"
} >> "$OUTPUT_DIR/run_manifest.txt"

printf 'Experiment artifacts written to: %s\n' "$OUTPUT_DIR"
