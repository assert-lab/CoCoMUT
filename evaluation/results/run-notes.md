# 20-Repository Evaluation Run Notes

## Execution

- Execution environment: Linux x86-64 machine with JDK 17
- Run directory: task-specific checkout outside the repository artifact
- Evaluation branch: `task/eval-success-cohort-fixes`
- Tool commit: `0d6be48344f1ae10f0034a25d59dd419a7182cdc`
- Base-run harness commit: `0d6be48344f1ae10f0034a25d59dd419a7182cdc`
- Single-repository refresh harness commit:
  `e63e0ba93ff10703b1bcb5e20145e07f5c50862c`
- Subject set: selected 10 Maven + 10 Gradle cohort in `evaluation/subjects.csv`
- Java: 17
- Build policy: `allow-build`

The run used `allow-build`, which permits Maven and Gradle build execution.
Analyses of untrusted repositories should be run in an externally isolated
environment.

## Tool Fixes Before Rerun

Two general CoCoMUT robustness fixes were applied before this rerun:

- Spoon Javadoc parser `AssertionError`s are handled per element, so unsupported
  or parser-hostile Javadoc tags no longer abort phase 4.
- Spoon source parsing retries without a source classpath when classpath loading
  fails with a `LinkageError`, such as a dependency or project class compiled
  for a newer Java runtime than the CoCoMUT JVM.

These are general fallbacks. They do not add repository-specific rules or fake
source identities.

## Subject Set

The final subject set contains 20 real-world Java repositories with pinned
commits, balanced across Maven and Gradle. The selected subjects produced usable
project bytecode and RTA call graphs under the evaluation
command.

Two subjects exercised robustness fixes before the publication rerun:

- `spring-cloud/spring-cloud-gateway`
- `graphhopper/graphhopper`

Both complete as `PARTIAL` after the general fixes above.

A final Gradle subject was refreshed with a single-repository rerun to keep the
committed results aligned with the 20-subject cohort. The other 19 repository
rows were retained from the base run.

## Runner Command

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
MAVEN_OPTS=-Xmx4g \
JAVA_TOOL_OPTIONS=-Xmx4g \
COCOMUT_TOOL_COMMIT=0d6be48344f1ae10f0034a25d59dd419a7182cdc \
COCOMUT_HARNESS_COMMIT=0d6be48344f1ae10f0034a25d59dd419a7182cdc \
python3 evaluation/scripts/run_cocomut_eval.py \
  --subjects evaluation/subjects.csv \
  --output-root evaluation/outputs \
  --results-dir evaluation/results \
  --timeout 1200 \
  --compile-timeout 300 \
  --heap-gb 4 \
  --cocomut-command ./bin/cocomut \
  --build-policy allow-build \
  --force
```

`environment.json` also records `harness_content_sha256` over the runner script,
analyzer script, subject list, and subject-selection protocol. The final result
set combines the full base run with one single-repository refresh.

## Result Summary

- Status: 0 `SUCCESS`, 20 `PARTIAL`, 0 failed/error/timeout.
- Build success: 20 / 20 repositories.
- Bytecode available: 20 / 20 repositories.
- Call graph available: 20 / 20 repositories.
- Source parsing: 5,359 / 6,791 files = 78.91%.
- Focal methods matched to bytecode: 46,659 / 56,512 = 82.56%.
- Method-context JSONL rows: 56,512.
- Malformed JSONL rows: 0.
- Serialized call-edge adjacency entries: 386,048.
- Unique directed call relations: 386,048.
- All-edge source-join rate: 76.22%.
- Recognized-project-target join rate: 97.84%.

## Interpretation

This run supports the selected 20-subject evaluation: every final subject
produced usable bytecode and method-context JSONL under the strict
configuration that uses project bytecode.

All repositories are `PARTIAL`, not `SUCCESS`, because CoCoMUT conservatively
records partiality when source parsing, call-graph matching, or source-bytecode
joining is incomplete. Report the parse and focal-bytecode-match rates alongside
build success and JSONL row counts.

RQ2 remains an automatic frequency study. `source_join_rate` is not accuracy,
recall, or manual correctness; it is the fraction of bytecode targets to which
CoCoMUT attached a deterministic source-level `method_uri`. Edges without a
source URI include intentional non-source targets such as JDK, external,
synthetic/compiler, and invokedynamic targets.
