# Call-Edge Low Source-Join Investigation

Date: 2026-06-22

Branch: `task/call-edge-nextwork`

Experiment folder, local only and git-ignored:

```text
experiments/call-edge-nextwork-low-join-2026-06-22/
```

This focused run investigated the low source-join repositories called out by
the earlier OE25 plus representative study:

- `apache/commons-jexl`
- `apache/commons-validator`
- `zxing/zxing`
- `remkop/picocli`

The run was executed on the SSH worker to avoid loading the laptop. The command
used CoCoMUT's normal product path:

```bash
./scripts/run_oe25_plus_representative_study.py \
  --output-dir experiments/call-edge-nextwork-low-join-2026-06-22 \
  --timeout 1200 \
  --compile-timeout 240 \
  --heap-gb 3 \
  --min-available-gb 4 \
  --max-load 10 \
  --extra-repo zxing/zxing \
  --extra-repo remkop/picocli \
  --repo apache/commons-jexl \
  --repo apache/commons-validator \
  --repo zxing/zxing \
  --repo remkop/picocli
```

All four repositories compiled and ran with classpath-aware extraction and RTA.

## Aggregate Result

```text
repositories attempted:              4
successful repositories:             4
compiled repositories:               4
call graph available:                4
methods identified:                  4,364
JSONL rows emitted:                  4,364
call edges observed:                 16,020
edges with target_uri:               16,020 / 16,020 = 100.00%
edges joined to source method_uri:    5,690 / 16,020 = 35.52%
ambiguous edges:                         0
```

The source-join rate is intentionally strict: `method_uri` is present only when
the SootUp bytecode target maps to one unique source method in the CoCoMUT/Spoon
model.

## Per-Repository Result

```text
repo                         edges   method_uri   join rate   dominant unresolved project bucket
---------------------------  ------  -----------  ----------  -----------------------------------------
apache/commons-jexl           6,029        1,498      24.85%  bytecode_method_not_selected
apache/commons-validator      1,129          410      36.32%  bytecode_method_not_selected
zxing/zxing                   3,341        1,345      40.26%  bytecode_method_not_selected
remkop/picocli                5,521        2,437      44.14%  nested_bytecode_class_without_unique_source_method
```

## New Subdivision

The previous broad bucket:

```text
project_class_present_method_absent
```

is now subdivided into deterministic subreasons:

```text
project_class_present_method_absent_bytecode_method_not_selected: 3,976
project_class_present_method_absent_synthetic_or_compiler_method:    87
project_class_present_method_absent_no_matching_bytecode_method:     87
project_class_present_method_absent_enum_generated_method:            1
```

This is a diagnostic improvement, not a fuzzy recovery rule. CoCoMUT still keeps
`method_uri` empty unless one source method is uniquely identified.

## Interpretation

The focused run suggests that the low source-join problem is not mainly an
overload ambiguity problem. In these four repositories, ambiguity was zero.

The dominant issue is that SootUp reports bytecode methods belonging to project
classes, but those methods are not in the selected source-method set. The main
causes appear to be:

- bytecode methods not emitted by source extraction or excluded by source
  selection;
- nested bytecode classes that do not map cleanly to one source method;
- compiler-generated helpers and generated enum/record methods;
- external/JDK calls, which correctly remain symbol-level only.

`remkop/picocli` is different from the two Commons projects: its largest
internal-ish bucket is nested bytecode class mapping, not selected-source
absence.

## Current Product Behavior

For every serialized call edge:

- `target_uri` records the bytecode target reported by SootUp;
- `method_uri` is present only for deterministic project-source joins;
- `candidate_method_uris` is used only when candidates are known but ambiguous;
- `unresolved_reason` explains why the edge stayed bytecode-only.

Report-level call graph fields now distinguish:

```text
phase_3_call_graph_artifact_exists   SootUp produced a graph/result artifact
phase_3_non_empty_call_graphs        per-method graph results with >= 1 edge
phase_3_call_edges_generated         total generated caller + callee edges
phase_5_call_edges_serialized        total caller + callee edges emitted in JSONL
phase_3_available                    true only when generated edge count > 0
```

## Remaining Work

The next useful investigation is `project_class_present_method_absent_bytecode_method_not_selected`.
That bucket is still mixed. It should be split further only with deterministic
evidence, for example:

- method exists in bytecode but is non-public and excluded by `--scope entry-points`;
- method belongs to a generated source or annotation-processor output;
- method belongs to a source root not parsed by Spoon;
- method exists in source text but not in the Spoon model;
- method is a bridge method or synthetic method not detected by name alone.

Do not recover these cases with fuzzy matching. The product rule remains:
resolve if unique, emit candidates if ambiguous, otherwise keep bytecode-only
`target_uri`.
