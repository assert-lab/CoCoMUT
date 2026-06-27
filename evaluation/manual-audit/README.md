# RQ3 Manual Audit Package

This directory contains the manual output-quality audit for 200 sampled
CoCoMUT method-context records.

## Files

```text
sample_200.csv              Master sample assigned to both annotators
sample_200.jsonl            The 200 sampled JSONL records, in sample_id order
annotator_1.csv             Annotator 1 labels
annotator_2.csv             Annotator 2 labels
adjudicated.csv             Final adjudicated labels
sample-allocation.tsv       Proportional per-repository allocation
sample-manifest.json        Sampling configuration and selected repositories
audit-repos.txt             Default 10 repositories used for RQ3 sampling
jsonl/                      Compressed per-repository method_contexts JSONL corpus
scripts/generate_sample.py  Regenerate sample/templates from the corpus
scripts/score_annotations.py Compute agreement, Cohen kappa, and disagreements
```

The full per-repository JSONL corpus is retained under `jsonl/` so the sample can
be regenerated if the repository list, sample size, or random seed changes. Large
compressed JSONL files may be split as `method_contexts.jsonl.gz.part-0000`,
`method_contexts.jsonl.gz.part-0001`, etc.; the sampling script reads those
parts transparently.

`sample_id` order matches `sample_200.jsonl` order: `S001` is line 1, `S200` is
line 200. The `jsonl_file` and `jsonl_line_number` columns point back to the
retained per-repository corpus and original line number.

## Regenerate The Sample

From the repository root:

```bash
python3 evaluation/manual-audit/scripts/generate_sample.py \
  --audit-root evaluation/manual-audit \
  --repos-file evaluation/manual-audit/audit-repos.txt \
  --subjects evaluation/subjects.csv \
  --sample-size 200 \
  --seed 20260626
```

The generator uses proportional allocation:

```text
sample_i = round((jsonl_rows_i / total_jsonl_rows_for_selected_repos) * 200)
```

It then adjusts by one sample at a time until the total is exactly 200.

## Annotator Workflow

1. Open `sample_200.jsonl` in the viewer:

   ```bash
   python3 scripts/method_contexts_viewer.py \
     evaluation/manual-audit/sample_200.jsonl \
     --port 8090 \
     --no-open
   ```

2. Give both annotators the same `sample_200.csv` and the instructions in
   `annotation-guidelines.md`.
3. Annotator 1 fills `annotator_1.csv`; annotator 2 fills `annotator_2.csv`.
4. Compute agreement:

   ```bash
   python3 evaluation/manual-audit/scripts/score_annotations.py \
     --annotator-1 evaluation/manual-audit/annotator_1.csv \
     --annotator-2 evaluation/manual-audit/annotator_2.csv \
     --out-dir evaluation/manual-audit/results
   ```

The script writes agreement summaries and disagreement cases.

5. Rerun scoring with the adjudicated labels:

   ```bash
   python3 evaluation/manual-audit/scripts/score_annotations.py \
     --annotator-1 evaluation/manual-audit/annotator_1.csv \
     --annotator-2 evaluation/manual-audit/annotator_2.csv \
     --adjudicated evaluation/manual-audit/adjudicated.csv \
     --out-dir evaluation/manual-audit/results
   ```

   This additionally writes `adjudication-summary.json` and
   `adjudication-summary.md` with final overall correctness and category pass
   rates.

## Paper Scope

Manual audit wording:

> Two independent annotators inspected the same 200 sampled CoCoMUT
> method-context records from 10 pinned repositories. For each record,
> annotators checked method identity, Javadoc references, caller/callee links,
> and inherited-documentation metadata when applicable. A record was considered
> correct only when all applicable categories passed. We measured
> inter-annotator agreement using Cohen's kappa and adjudicated disagreements
> with the senior developer.

This audit checks emitted output quality for the sampled records. It does not
measure full call-graph completeness, full Javadoc completeness across the
corpus, source-join recall, or downstream LLM usefulness.
