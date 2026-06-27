# 20-Repository Evaluation

This directory contains the evaluation harness and compact outputs for the
CoCoMUT tool paper study.

The study uses:

- 20 pinned real-world Java repositories;
- 10 Maven and 10 Gradle projects;
- main-source, all-method extraction;
- RTA call graphs over compiled project bytecode;
- manual output-quality auditing for 200 sampled records.

## Layout

```text
subjects.csv                 Evaluation subject list
subject-selection.md          Subject-selection notes
environment.json              Recorded run environment
scripts/                      Evaluation runner and analyzer
results/                      Publication run summaries and compact artifacts
manual-audit/                 RQ3 sampling, annotations, and adjudication
pilot-results/                Earlier pilot outputs retained for audit only
```

The active publication results are in `evaluation/results/`. The older
`evaluation/pilot-results/` directory is not used for the paper tables.

## Research Questions

- **RQ1: Build-ecosystem robustness.** Can CoCoMUT produce method-context
  records across Maven and Gradle projects?
- **RQ2: Source-bytecode reconciliation.** How often can CoCoMUT attach a
  deterministic source `method_uri` to bytecode call targets, and when does it
  abstain?
- **RQ3: Output quality.** Do sampled records contain the intended method
  identity, documentation references, caller/callee context, and
  inherited-documentation metadata?

## Run The Evaluation

From the repository root:

```bash
python3 evaluation/scripts/run_cocomut_eval.py \
  --subjects evaluation/subjects.csv \
  --output-root evaluation/outputs \
  --results-dir evaluation/results \
  --timeout 1200 \
  --compile-timeout 300 \
  --heap-gb 4 \
  --cocomut-command ./bin/cocomut \
  --build-policy allow-build

python3 evaluation/scripts/analyze_cocomut_eval.py \
  --results-dir evaluation/results
```

Each subject is analyzed with:

```bash
./bin/cocomut \
  --project <checkout> \
  --scope all \
  --source-set main \
  --call-graph rta \
  --allow-build \
  --output-dir <output>
```

## Outputs

The runner writes:

```text
evaluation/environment.json
evaluation/outputs/<repo-safe-name>/
evaluation/results/repository-results.tsv
evaluation/results/call-edge-results.tsv
evaluation/results/failures.tsv
evaluation/results/artifacts/<repo-safe-name>/
```

The analyzer writes:

```text
evaluation/results/rq1-table.tsv
evaluation/results/rq2-table.tsv
evaluation/results/aggregate-summary.md
```

`results/artifacts/` keeps compact per-repository diagnostics such as
`extraction_report.json`, `extraction_manifest.json`, failed-source lists when
present, and run logs. Full checkouts and bulky generated JSONL outputs are
kept under `evaluation/outputs/` during a run and are not committed by default.

## Manual Audit

RQ3 materials live in `evaluation/manual-audit/`.

Important files:

```text
sample_200.csv          Sample manifest for the audited records
sample_200.jsonl        The 200 sampled method-context records
annotator_1.csv         Annotator 1 labels
annotator_2.csv         Annotator 2 labels
adjudicated.csv         Final adjudicated labels
annotation-guidelines.md
```

See `evaluation/manual-audit/README.md` for the sampling and scoring workflow.
