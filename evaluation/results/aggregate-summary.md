# CoCoMUT Evaluation Summary

## Subjects
- 20 repositories
- 10 Maven
- 10 Gradle

## RQ1: Build-Ecosystem Robustness
- Completion: 0 SUCCESS, 9 PARTIAL, 11 failed/error/timeout.
- Build success: 12 / 20 repositories.
- Bytecode available: 14 / 20 repositories.
- Call graph available: 11 / 20 repositories.
- Source parsing: 4161 / 5466 files (76.13%).
- Focal methods matched to bytecode: 38635 / 47551 (81.25%).
- Methods identified: 47551; contexts extracted: 42021; JSONL rows: 42021.

| build_system | repos_attempted | repos_success | repos_partial | repos_failed | build_success | bytecode_available | call_graph_available | source_files_discovered | source_files_parsed | source_parse_rate | methods_identified | focal_methods_matched_to_bytecode | focal_bytecode_match_rate | contexts_extracted | jsonl_rows | call_edges_serialized | median_duration_ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 10 | 0 | 5 | 5 | 7 | 8 | 7 | 3879 | 3160 | 0.814643 | 42183 | 33769 | 0.800536 | 36653 | 36653 | 293645 | 136836 |
| gradle | 10 | 0 | 4 | 6 | 5 | 6 | 4 | 1587 | 1001 | 0.630750 | 5368 | 4866 | 0.906483 | 5368 | 5368 | 27684 | 47029 |
| total | 20 | 0 | 9 | 11 | 12 | 14 | 11 | 5466 | 4161 | 0.761251 | 47551 | 38635 | 0.812496 | 42021 | 42021 | 321329 | 61142 |

## RQ2: Source-Bytecode Reconciliation and Abstention
- Serialized call-edge adjacency entries: 321329.
- Unique directed relations: 321329.
- `target_uri` coverage: 100.00%.
- All-edge source-join rate: 81.75%.
- Recognized-project-target join rate: 98.18%.
- Edges without source URI: 58643; ambiguous edges: 0; candidate edges: 0.

| build_system | serialized_edges | unique_directed_relations | edges_with_target_uri | target_uri_rate | edges_with_method_uri | source_join_rate | edges_without_source_uri | project_target_edges | project_target_edges_with_method_uri | recognized_project_target_join_rate | ambiguous_edges | candidate_edges | project_method_edges | unresolved_project_method_edges | jdk_method_edges | external_method_edges | synthetic_or_compiler_edges | invokedynamic_edges | median_serialized_edges | iqr_serialized_edges | median_unique_directed_relations | iqr_unique_directed_relations | median_source_join_rate | iqr_source_join_rate | median_recognized_project_target_join_rate | iqr_recognized_project_target_join_rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 293645 | 293645 | 293645 | 1.000000 | 247234 | 0.841949 | 46411 | 251176 | 247234 | 0.984306 | 0 | 0 | 247234 | 3942 | 28938 | 7706 | 3656 | 2169 | 2091 | 45864.000000 | 2091 | 45864.000000 | 0.815277 | 0.235149 | 0.972715 | 0.029721 |
| gradle | 27684 | 27684 | 27684 | 1.000000 | 15452 | 0.558156 | 12232 | 16384 | 15452 | 0.943115 | 0 | 0 | 15452 | 932 | 5042 | 5317 | 304 | 637 | 0 | 987.500000 | 0 | 987.500000 | 0.370586 | 0.295271 | 0.942599 | 0.097520 |
| total | 321329 | 321329 | 321329 | 1.000000 | 262686 | 0.817499 | 58643 | 267560 | 262686 | 0.981784 | 0 | 0 | 262686 | 4874 | 33980 | 13023 | 3960 | 2806 | 0 | 12214.500000 | 0 | 12214.500000 | 0.661317 | 0.454770 | 0.970401 | 0.033415 |

### Total Distributions

Target-kind distribution:
- `project_method`: 262686
- `jdk_method`: 33980
- `external_method`: 13023
- `unresolved_project_method`: 4874
- `synthetic_or_compiler_method`: 3960
- `invokedynamic_method`: 2806

Resolution distribution:
- `resolved`: 261814
- `unresolved`: 54683
- `synthetic_or_compiler_generated`: 3960
- `resolved_return_mismatch_unique`: 872

Unresolved-reason distribution:
- `jdk_or_platform_method_outside_project_source`: 33980
- `external_or_unmodeled_bytecode_method`: 12905
- `project_class_present_method_absent_synthetic_or_compiler_method`: 3283
- `invokedynamic_or_lambda_bytecode_artifact`: 2806
- `nested_bytecode_class_without_unique_source_method`: 1642
- `project_class_present_method_absent_bytecode_method_not_selected`: 1555
- `project_class_present_method_absent_no_matching_bytecode_method`: 1216
- `project_method_name_present_but_signature_not_unique_or_compatible`: 728
- `anonymous_or_local_class_bytecode`: 516
- `project_class_present_method_absent_enum_generated_method`: 12

### Maven Distributions

Target-kind distribution:
- `project_method`: 247234
- `jdk_method`: 28938
- `external_method`: 7706
- `unresolved_project_method`: 3942
- `synthetic_or_compiler_method`: 3656
- `invokedynamic_method`: 2169

Resolution distribution:
- `resolved`: 246419
- `unresolved`: 42755
- `synthetic_or_compiler_generated`: 3656
- `resolved_return_mismatch_unique`: 815

Unresolved-reason distribution:
- `jdk_or_platform_method_outside_project_source`: 28938
- `external_or_unmodeled_bytecode_method`: 7594
- `project_class_present_method_absent_synthetic_or_compiler_method`: 2993
- `invokedynamic_or_lambda_bytecode_artifact`: 2169
- `nested_bytecode_class_without_unique_source_method`: 1257
- `project_class_present_method_absent_bytecode_method_not_selected`: 1256
- `project_class_present_method_absent_no_matching_bytecode_method`: 1198
- `project_method_name_present_but_signature_not_unique_or_compatible`: 541
- `anonymous_or_local_class_bytecode`: 454
- `project_class_present_method_absent_enum_generated_method`: 11

### Gradle Distributions

Target-kind distribution:
- `project_method`: 15452
- `external_method`: 5317
- `jdk_method`: 5042
- `unresolved_project_method`: 932
- `invokedynamic_method`: 637
- `synthetic_or_compiler_method`: 304

Resolution distribution:
- `resolved`: 15395
- `unresolved`: 11928
- `synthetic_or_compiler_generated`: 304
- `resolved_return_mismatch_unique`: 57

Unresolved-reason distribution:
- `external_or_unmodeled_bytecode_method`: 5311
- `jdk_or_platform_method_outside_project_source`: 5042
- `invokedynamic_or_lambda_bytecode_artifact`: 637
- `nested_bytecode_class_without_unique_source_method`: 385
- `project_class_present_method_absent_bytecode_method_not_selected`: 299
- `project_class_present_method_absent_synthetic_or_compiler_method`: 290
- `project_method_name_present_but_signature_not_unique_or_compatible`: 187
- `anonymous_or_local_class_bytecode`: 62
- `project_class_present_method_absent_no_matching_bytecode_method`: 18
- `project_class_present_method_absent_enum_generated_method`: 1

## Interpretation
- RQ1 measures construction robustness, not semantic correctness.
- A repository can build and emit method-context JSONL while still being `PARTIAL` because source parsing or focal-method bytecode matching is incomplete.
- RQ2 measures deterministic source-join behavior and abstention taxonomy, not manual accuracy.
- `source_join_rate` is not recall or correctness; it is the fraction of bytecode targets with deterministic source-backed `method_uri` joins.
- `edges_without_source_uri` includes intentional non-source targets such as JDK, external-library, synthetic/compiler, and invokedynamic targets.
