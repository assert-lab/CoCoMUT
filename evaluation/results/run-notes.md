# Publication 20-Repository Evaluation Run Notes

## Execution

- Worker: `ssh worker`
- Worker run directory: `~/agent-runs/cocomut-publication-eval/repo`
- Local branch: `task/evaluation-publication-sound`
- Subject set: frozen before the publication rerun in `evaluation/subjects.csv`
- Subject replacement after outcomes: none
- Java: 17
- Build policy: `allow-build`

The run used `allow-build` because it executed directly on a shared SSH worker.
It should not be described as externally sandboxed unless a separate VM,
container, filesystem, network, and credential isolation boundary is added and
documented.

## Runner Command

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
COCOMUT_TOOL_COMMIT=f369623123babea75d7614e652968cca1670a4b4 \
COCOMUT_HARNESS_COMMIT=f369623123babea75d7614e652968cca1670a4b4 \
python3 evaluation/scripts/run_cocomut_eval.py \
  --subjects evaluation/subjects.csv \
  --output-root evaluation/outputs \
  --results-dir evaluation/results \
  --timeout 1200 \
  --compile-timeout 300 \
  --heap-gb 4 \
  --cocomut-command ./bin/cocomut \
  --build-policy allow-build
```

The worker copy did not provide a reliable final Git commit for the modified
evaluation harness, so `environment.json` also records
`harness_content_sha256` over the runner script, analyzer script, subject list,
and subject-selection protocol.

## Result Summary

- Status: 0 `SUCCESS`, 9 `PARTIAL`, 11 failed/error/timeout.
- Build success: 12 / 20 repositories.
- Bytecode available: 14 / 20 repositories.
- Call graph available: 11 / 20 repositories.
- Source parsing: 4,161 / 5,466 files = 76.13%.
- Focal methods matched to bytecode: 38,635 / 47,551 = 81.25%.
- Method-context JSONL rows: 42,021.
- Malformed JSONL rows: 0.
- Serialized call-edge adjacency entries: 321,329.
- Unique directed call relations: 321,329.
- All-edge source-join rate: 81.75%.
- Recognized-project-target join rate: 98.18%.

## Interpretation

This run is a stricter publication rerun, not the earlier successful-subject
pilot. Failed repositories remain in the denominator. Maven/Gradle comparisons
should be presented as descriptive observations over this frozen cohort, not as
causal claims about build systems.

RQ2 remains an automatic frequency study. `source_join_rate` is not accuracy,
recall, or manual correctness; it is the fraction of bytecode targets to which
CoCoMUT attached a deterministic source-backed `method_uri`. Edges without a
source URI include intentional non-source targets such as JDK, external,
synthetic/compiler, and invokedynamic targets.
