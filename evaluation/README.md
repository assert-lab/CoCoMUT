# Reduced 20-Repository Evaluation

This directory contains the reproducible evaluation harness for the reduced
CoCoMUT tool paper study:

- 20 real-world Java repositories;
- 10 Maven and 10 Gradle projects;
- pinned commit SHA for every subject;
- subject set frozen before the publication rerun;
- strict bytecode-backed CoCoMUT extraction;
- two RQs: build-ecosystem robustness and source-bytecode reconciliation.

The evaluation deliberately excludes downstream LLM generation, manual
annotation, source-only success claims, and Javadoc-specific RQs.

## Research Questions

**RQ1: Build-Ecosystem Robustness.** How robustly does CoCoMUT preserve its
method-context extraction contract across Java projects with different build
ecosystems?

**RQ2: Source-Bytecode Reconciliation and Abstention.** How often does CoCoMUT
deterministically reconcile bytecode call targets with source-backed method
identities, and how does it classify cases where it abstains?

RQ2 does not measure manual accuracy. The source-join rate is the frequency
with which CoCoMUT attaches a deterministic source `method_uri` to a bytecode
`target_uri`, not recall or semantic correctness.

## Inputs

`subjects.csv` contains the frozen evaluation subject set. It must contain
exactly 10 Maven and 10 Gradle repositories. Required columns:

```text
repo,build_system,commit_sha,size_bin,module_shape,notes
```

The selection protocol is documented in `subject-selection.md`. Do not replace
subjects after observing CoCoMUT failures; failed subjects remain in the
denominator.

## Running

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

The runner creates:

```text
evaluation/environment.json
evaluation/outputs/<repo-safe-name>/checkout/
evaluation/outputs/<repo-safe-name>/cocomut_output/
evaluation/outputs/<repo-safe-name>/logs/
evaluation/results/repository-results.tsv
evaluation/results/call-edge-results.tsv
evaluation/results/failures.tsv
evaluation/results/artifacts/<repo-safe-name>/
```

The `artifacts/` subdirectory stores compact per-repository diagnostics:
CoCoMUT reports, manifests, failed-source lists when present, and logs. Bulky
checkouts and full method-context JSONL files remain under `evaluation/outputs/`
and are not committed by default.

The analyzer creates:

```text
evaluation/results/rq1-table.tsv
evaluation/results/rq2-table.tsv
evaluation/results/aggregate-summary.md
```

## CoCoMUT Command

Each repository is run once with:

```bash
./bin/cocomut \
  --project <checkout> \
  --scope all \
  --source-set main \
  --call-graph rta \
  --allow-build \
  --output-dir <output>
```

Use `--build-policy externally-sandboxed-build` only when the command is run
inside a documented external sandbox or VM/container isolation boundary. A
plain shared SSH worker run should use `--build-policy allow-build`.

The evaluation does not use:

```text
--max-methods
--max-source-files
--allow-preexisting-bytecode-after-build-failure
```

## Worker Execution

The intended long run is performed on the SSH worker under a task-specific
directory:

```text
~/agent-runs/cocomut-publication-eval/
```

The local repository branch is synced to the worker before execution, and the
compact `evaluation/results/` and `evaluation/environment.json` outputs are
copied back for paper writing.

## Paper Interpretation

Allowed claims:

- CoCoMUT completed extraction on X/20 projects.
- CoCoMUT produced X method-context rows.
- CoCoMUT parsed X/Y discovered Java source files.
- CoCoMUT matched X/Y focal methods to bytecode call-graph entries.
- Maven and Gradle differed in build success, call-graph availability, runtime,
  parse coverage, method-bytecode matching, or emitted context volume over this
  descriptive cohort.
- X% of serialized call edges preserved bytecode `target_uri`.
- X% of bytecode targets were deterministically joined to source `method_uri`.
- X unique directed `(source_uri, target_uri)` call relations were observed.
- Abstentions are classified by `target_kind`, `resolution`, and
  `unresolved_reason`.

Avoid claims:

- source-join rate is accuracy or recall;
- unresolved edges are bugs;
- edges without source URI are necessarily unresolved project failures;
- CoCoMUT supports source-only extraction as success;
- Javadoc handling or downstream LLM quality was evaluated here.
