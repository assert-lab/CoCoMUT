# CoCoMUT Evaluation Summary

## Subjects
- 20 repositories
- 10 Maven
- 10 Gradle

## RQ1: Build-Ecosystem Robustness
- Completion: 0 SUCCESS, 20 PARTIAL, 0 failed/error/timeout.
- Build success: 20 / 20 repositories.
- Bytecode available: 20 / 20 repositories.
- Call graph available: 20 / 20 repositories.
- Source parsing: 5359 / 6791 files (78.91%).
- Focal methods matched to bytecode: 46659 / 56512 (82.56%).
- Methods identified: 56512; contexts extracted: 56512; JSONL rows: 56512.

| build_system | repos_attempted | repos_success | repos_partial | repos_failed | build_success | bytecode_available | call_graph_available | source_files_discovered | source_files_parsed | source_parse_rate | methods_identified | focal_methods_matched_to_bytecode | focal_bytecode_match_rate | contexts_extracted | jsonl_rows | call_edges_serialized | median_duration_ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 10 | 0 | 10 | 0 | 10 | 10 | 10 | 3987 | 3267 | 0.819413 | 43508 | 35059 | 0.805806 | 43508 | 43508 | 321806 | 46956 |
| gradle | 10 | 0 | 10 | 0 | 10 | 10 | 10 | 2804 | 2092 | 0.746077 | 13004 | 11600 | 0.892033 | 13004 | 13004 | 64242 | 41569 |
| total | 20 | 0 | 20 | 0 | 20 | 20 | 20 | 6791 | 5359 | 0.789133 | 56512 | 46659 | 0.825648 | 56512 | 56512 | 386048 | 41569 |

## RQ2: Source-Bytecode Reconciliation and Abstention
- Serialized call-edge adjacency entries: 386048.
- Unique directed relations: 386048.
- `target_uri` coverage: 100.00%.
- All-edge source-join rate: 76.22%.
- Recognized-project-target join rate: 97.84%.
- Edges without source URI: 91806; ambiguous edges: 0; candidate edges: 0.

| build_system | serialized_edges | unique_directed_relations | edges_with_target_uri | target_uri_rate | edges_with_method_uri | source_join_rate | edges_without_source_uri | project_target_edges | project_target_edges_with_method_uri | recognized_project_target_join_rate | ambiguous_edges | candidate_edges | project_method_edges | unresolved_project_method_edges | jdk_method_edges | external_method_edges | synthetic_or_compiler_edges | invokedynamic_edges | median_serialized_edges | iqr_serialized_edges | median_unique_directed_relations | iqr_unique_directed_relations | median_source_join_rate | iqr_source_join_rate | median_recognized_project_target_join_rate | iqr_recognized_project_target_join_rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 321806 | 321806 | 321806 | 1.000000 | 260096 | 0.808239 | 61710 | 264353 | 260096 | 0.983897 | 0 | 0 | 260096 | 4257 | 36103 | 13940 | 4302 | 3108 | 11493 | 42842.500000 | 11493 | 42842.500000 | 0.587614 | 0.374143 | 0.978792 | 0.028165 |
| gradle | 64242 | 64242 | 64242 | 1.000000 | 34146 | 0.531521 | 30096 | 36390 | 34146 | 0.938335 | 0 | 0 | 34146 | 2244 | 12188 | 13063 | 1017 | 1584 | 5882 | 7307.750000 | 5882 | 7307.750000 | 0.417160 | 0.309257 | 0.953885 | 0.056207 |
| total | 386048 | 386048 | 386048 | 1.000000 | 294242 | 0.762190 | 91806 | 300743 | 294242 | 0.978384 | 0 | 0 | 294242 | 6501 | 48291 | 27003 | 5319 | 4692 | 7657 | 10709.250000 | 7657 | 10709.250000 | 0.507810 | 0.308866 | 0.970003 | 0.036191 |

### Total Distributions

Target-kind distribution:
- `project_method`: 294242
- `jdk_method`: 48291
- `external_method`: 27003
- `unresolved_project_method`: 6501
- `synthetic_or_compiler_method`: 5319
- `invokedynamic_method`: 4692

Resolution distribution:
- `resolved`: 293212
- `unresolved`: 86487
- `synthetic_or_compiler_generated`: 5319
- `resolved_return_mismatch_unique`: 1030

Unresolved-reason distribution:
- `jdk_or_platform_method_outside_project_source`: 48291
- `external_or_unmodeled_bytecode_method`: 26315
- `invokedynamic_or_lambda_bytecode_artifact`: 4692
- `project_class_present_method_absent_synthetic_or_compiler_method`: 4556
- `project_class_present_method_absent_bytecode_method_not_selected`: 1990
- `nested_bytecode_class_without_unique_source_method`: 1867
- `anonymous_or_local_class_bytecode`: 1600
- `project_class_present_method_absent_no_matching_bytecode_method`: 1459
- `project_method_name_present_but_signature_not_unique_or_compatible`: 988
- `project_class_present_method_absent_enum_generated_method`: 37
- `project_class_present_method_absent_record_component_accessor`: 11

### Maven Distributions

Target-kind distribution:
- `project_method`: 260096
- `jdk_method`: 36103
- `external_method`: 13940
- `synthetic_or_compiler_method`: 4302
- `unresolved_project_method`: 4257
- `invokedynamic_method`: 3108

Resolution distribution:
- `resolved`: 259219
- `unresolved`: 57408
- `synthetic_or_compiler_generated`: 4302
- `resolved_return_mismatch_unique`: 877

Unresolved-reason distribution:
- `jdk_or_platform_method_outside_project_source`: 36103
- `external_or_unmodeled_bytecode_method`: 13618
- `project_class_present_method_absent_synthetic_or_compiler_method`: 3572
- `invokedynamic_or_lambda_bytecode_artifact`: 3108
- `nested_bytecode_class_without_unique_source_method`: 1390
- `project_class_present_method_absent_bytecode_method_not_selected`: 1312
- `project_class_present_method_absent_no_matching_bytecode_method`: 1231
- `anonymous_or_local_class_bytecode`: 765
- `project_method_name_present_but_signature_not_unique_or_compatible`: 578
- `project_class_present_method_absent_enum_generated_method`: 28
- `project_class_present_method_absent_record_component_accessor`: 5

### Gradle Distributions

Target-kind distribution:
- `project_method`: 34146
- `external_method`: 13063
- `jdk_method`: 12188
- `unresolved_project_method`: 2244
- `invokedynamic_method`: 1584
- `synthetic_or_compiler_method`: 1017

Resolution distribution:
- `resolved`: 33993
- `unresolved`: 29079
- `synthetic_or_compiler_generated`: 1017
- `resolved_return_mismatch_unique`: 153

Unresolved-reason distribution:
- `external_or_unmodeled_bytecode_method`: 12697
- `jdk_or_platform_method_outside_project_source`: 12188
- `invokedynamic_or_lambda_bytecode_artifact`: 1584
- `project_class_present_method_absent_synthetic_or_compiler_method`: 984
- `anonymous_or_local_class_bytecode`: 835
- `project_class_present_method_absent_bytecode_method_not_selected`: 678
- `nested_bytecode_class_without_unique_source_method`: 477
- `project_method_name_present_but_signature_not_unique_or_compatible`: 410
- `project_class_present_method_absent_no_matching_bytecode_method`: 228
- `project_class_present_method_absent_enum_generated_method`: 9
- `project_class_present_method_absent_record_component_accessor`: 6

## Interpretation
- RQ1 measures construction robustness, not semantic correctness.
- A repository can build and emit method-context JSONL while still being `PARTIAL` because source parsing or focal-method bytecode matching is incomplete.
- RQ2 measures deterministic source-join behavior and abstention taxonomy, not manual accuracy.
- `source_join_rate` is not recall or correctness; it is the fraction of bytecode targets with deterministic source-backed `method_uri` joins.
- `edges_without_source_uri` includes intentional non-source targets such as JDK, external-library, synthetic/compiler, and invokedynamic targets.
