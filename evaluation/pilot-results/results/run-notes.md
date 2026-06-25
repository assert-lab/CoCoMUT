# Reduced 20-Repository Evaluation Run Notes

## Execution

- Worker: `ssh worker`
- Worker run directory: `~/agent-runs/cocomut-reduced-20-evaluation/repo`
- Local branch: `task/reduced-20-evaluation`
- CoCoMUT command:

```bash
./bin/cocomut \
  --project <checkout> \
  --scope all \
  --source-set main \
  --call-graph rta \
  --externally-sandboxed-build \
  --output-dir <output>
```

- Runner command:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
COCOMUT_REPO_COMMIT=1bff9f41292d90034715a1508cec3ad657279d2c \
python3 evaluation/scripts/run_cocomut_eval.py \
  --subjects evaluation/subjects.csv \
  --output-root evaluation/outputs \
  --results-dir evaluation/results \
  --timeout 1200 \
  --compile-timeout 300 \
  --heap-gb 4 \
  --cocomut-command ./bin/cocomut \
  --resume
```

The run used Java 17 because the worker default Java 26 caused older Gradle
subjects to fail before usable bytecode was produced.

## Subject Replacement

An initial Gradle pass showed that `Netflix/concurrency-limits`,
`Netflix/ribbon`, `Netflix/zuul`, and `JFormDesigner/FlatLaf` did not satisfy
the intended "builds successfully and emits method contexts" subject criterion
on the worker. They were replaced with four Gradle projects from the prior
successful sweep:

- `allure-framework/allure2`
- `dreamhead/moco`
- `spring-projects/spring-authorization-server`
- `embulk/embulk`

The final `subjects.csv` contains exactly 10 Gradle and 10 Maven repositories,
all pinned to specific commit SHAs.

## Interpretation Notes

All 20 final subjects completed with build success, bytecode availability, call
graph availability, parseable method-context JSONL, and zero malformed JSONL
rows. CoCoMUT nevertheless reported `PARTIAL` for every subject because its
strict failure-code policy marks a run partial when any selected method lacks a
matched bytecode call-graph result or when any source file fails parsing.

For the paper, report both facts separately:

- build-backed construction completed for 20/20 repositories;
- CoCoMUT's strict run status was 20 `PARTIAL`, 0 `SUCCESS`, 0 failed/error.

Do not describe the RQ2 source-join rate as accuracy or recall; it is the
frequency of deterministic source identity joins among serialized bytecode call
edges with `target_uri`.
