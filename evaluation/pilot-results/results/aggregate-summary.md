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
- Methods identified: 21797; contexts extracted: 21797; JSONL rows: 21797.

| build_system | repos_attempted | repos_success | repos_partial | repos_failed | build_success | bytecode_available | call_graph_available | methods_identified | contexts_extracted | jsonl_rows | call_edges_serialized | median_duration_ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 10 | 0 | 10 | 0 | 10 | 10 | 10 | 3075 | 3075 | 3075 | 9310 | 10023 |
| gradle | 10 | 0 | 10 | 0 | 10 | 10 | 10 | 18722 | 18722 | 18722 | 107886 | 79066 |
| total | 20 | 0 | 20 | 0 | 20 | 20 | 20 | 21797 | 21797 | 21797 | 117196 | 16317 |

## RQ2: Source-Bytecode Reconciliation and Abstention
- Serialized call edges: 117196.
- `target_uri` coverage: 100.00%.
- Source-join rate: 45.74%.
- Unresolved edges: 63587; ambiguous edges: 0; candidate edges: 0.

| build_system | total_edges | edges_with_target_uri | target_uri_rate | edges_with_method_uri | source_join_rate | unresolved_edges | ambiguous_edges | candidate_edges | project_method_edges | unresolved_project_method_edges | jdk_method_edges | external_method_edges | synthetic_or_compiler_edges | invokedynamic_edges |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| maven | 9310 | 9310 | 1.000000 | 4772 | 0.512567 | 4538 | 0 | 0 | 4772 | 168 | 3512 | 593 | 130 | 135 |
| gradle | 107886 | 107886 | 1.000000 | 48837 | 0.452672 | 59049 | 0 | 0 | 48837 | 4701 | 19750 | 30179 | 2488 | 1931 |
| total | 117196 | 117196 | 1.000000 | 53609 | 0.457430 | 63587 | 0 | 0 | 53609 | 4869 | 23262 | 30772 | 2618 | 2066 |

### Total Distributions

Target-kind distribution:
- `project_method`: 53609
- `external_method`: 30772
- `jdk_method`: 23262
- `unresolved_project_method`: 4869
- `synthetic_or_compiler_method`: 2618
- `invokedynamic_method`: 2066

Resolution distribution:
- `unresolved`: 60969
- `resolved`: 53307
- `synthetic_or_compiler_generated`: 2618
- `resolved_return_mismatch_unique`: 302

Unresolved-reason distribution:
- `external_or_unmodeled_bytecode_method`: 28811
- `jdk_or_platform_method_outside_project_source`: 23262
- `anonymous_or_local_class_bytecode`: 4268
- `project_class_present_method_absent_synthetic_or_compiler_method`: 2325
- `invokedynamic_or_lambda_bytecode_artifact`: 2066
- `nested_bytecode_class_without_unique_source_method`: 1278
- `project_class_present_method_absent_bytecode_method_not_selected`: 682
- `project_method_name_present_but_signature_not_unique_or_compatible`: 480
- `project_class_present_method_absent_no_matching_bytecode_method`: 391
- `project_class_present_method_absent_enum_generated_method`: 18
- `project_class_present_method_absent_record_component_accessor`: 6

### Maven Distributions

Target-kind distribution:
- `project_method`: 4772
- `jdk_method`: 3512
- `external_method`: 593
- `unresolved_project_method`: 168
- `invokedynamic_method`: 135
- `synthetic_or_compiler_method`: 130

Resolution distribution:
- `resolved`: 4744
- `unresolved`: 4408
- `synthetic_or_compiler_generated`: 130
- `resolved_return_mismatch_unique`: 28

Unresolved-reason distribution:
- `jdk_or_platform_method_outside_project_source`: 3512
- `external_or_unmodeled_bytecode_method`: 592
- `invokedynamic_or_lambda_bytecode_artifact`: 135
- `project_class_present_method_absent_synthetic_or_compiler_method`: 97
- `nested_bytecode_class_without_unique_source_method`: 73
- `project_class_present_method_absent_no_matching_bytecode_method`: 73
- `project_class_present_method_absent_bytecode_method_not_selected`: 24
- `project_method_name_present_but_signature_not_unique_or_compatible`: 18
- `anonymous_or_local_class_bytecode`: 11
- `project_class_present_method_absent_enum_generated_method`: 3

### Gradle Distributions

Target-kind distribution:
- `project_method`: 48837
- `external_method`: 30179
- `jdk_method`: 19750
- `unresolved_project_method`: 4701
- `synthetic_or_compiler_method`: 2488
- `invokedynamic_method`: 1931

Resolution distribution:
- `unresolved`: 56561
- `resolved`: 48563
- `synthetic_or_compiler_generated`: 2488
- `resolved_return_mismatch_unique`: 274

Unresolved-reason distribution:
- `external_or_unmodeled_bytecode_method`: 28219
- `jdk_or_platform_method_outside_project_source`: 19750
- `anonymous_or_local_class_bytecode`: 4257
- `project_class_present_method_absent_synthetic_or_compiler_method`: 2228
- `invokedynamic_or_lambda_bytecode_artifact`: 1931
- `nested_bytecode_class_without_unique_source_method`: 1205
- `project_class_present_method_absent_bytecode_method_not_selected`: 658
- `project_method_name_present_but_signature_not_unique_or_compatible`: 462
- `project_class_present_method_absent_no_matching_bytecode_method`: 318
- `project_class_present_method_absent_enum_generated_method`: 15
- `project_class_present_method_absent_record_component_accessor`: 6

## Interpretation
- RQ1 measures construction robustness, not semantic correctness.
- RQ2 measures deterministic source-join behavior and abstention taxonomy, not manual accuracy.
- `source_join_rate` is not recall or correctness; it is the fraction of bytecode targets with deterministic source-level `method_uri` joins.
