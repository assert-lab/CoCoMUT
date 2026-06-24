# OE25 Plus Representative Call-Edge Validation

> Historical note: this validation run used the older evaluation runner that
> could retry without bytecode-backed call context. The aggregate measurements
> remain useful as historical evidence, but current CoCoMUT runs require
> compilation or supplied bytecode artifacts.

This report validates CoCoMUT call-edge matching after the Spoon/SootUp glue
changes that introduced bytecode `target_uri`, source `method_uri`, and the
call-edge target taxonomy.

Run date: 2026-06-20

Output directory:

```text
experiments/oe25-plus-representative-call-edge-validation-2026-06-20/
```

Primary table:

```text
experiments/oe25-plus-representative-call-edge-validation-2026-06-20/results.tsv
```

The experiment directory is intentionally generated output. It is not meant to
be committed as product source.

## Run Policy

The sweep used the OE25 repositories plus the representative public-repository
checkouts already available on disk. Existing checkouts were reused to avoid
unnecessary network activity.

Command shape:

```bash
python3 scripts/run_oe25_plus_representative_study.py \
  --output-dir experiments/oe25-plus-representative-call-edge-validation-2026-06-20 \
  --oe25-checkouts-dir /home/ale/repos/repo_mining_trials/Code-Context-Extractor/experiments/oe25-plus-representative-auto-main/checkouts \
  --representative-checkouts-dir /home/ale/repos/repo_mining_trials/Code-Context-Extractor/experiments/expanded-public-repos-auto-main-representative/checkouts \
  --timeout 300 \
  --compile-timeout 120 \
  --heap-gb 2 \
  --retry-max-source-files 1500 \
  --retry-max-methods 5000 \
  --min-available-gb 3.0 \
  --max-load 6.0 \
  --resource-check-interval 30
```

Execution policy:

- legacy primary attempt: optional compile/source-resolution/call-graph modes
  from the pre-mandatory-bytecode CLI;
- legacy fallback: no-call-graph or bounded source-only extraction when needed;
- sequential execution only, with a memory/load guard before each target;
- Baloo file indexing was temporarily suspended during the run because it was
  consuming several GiB while indexing generated experiment output.

## Aggregate Results

Repositories:

```text
repositories attempted: 55
successful JSONL extractions: 55
compile result counts: true=37, false=18
call graph availability counts: true=36, false=19
repositories with non-empty serialized call edges: 35
```

Extraction volume:

```text
methods identified: 185,945
JSONL rows emitted: 185,945
@see references observed: 8,809
methods using {@inheritDoc}: 3,098
```

Call-edge volume:

```text
call edges observed: 349,406
edges with target_uri: 349,406 / 349,406 = 100.00%
edges joined to source method_uri: 209,847 / 349,406 = 60.06%
edges classified as project/JDK/external method targets: 272,546 / 349,406 = 78.00%
ambiguous call edges: 336
```

Interpretation:

- `target_uri` is bytecode identity. It exists for every serialized SootUp edge.
- `method_uri` is source identity. It exists only when CoCoMUT can join the
  bytecode target to one unique Spoon project method.
- Edges without `method_uri` are not all failures. Many are JDK methods,
  external library methods, invokedynamic/lambda artifacts, compiler-generated
  methods, or truly unresolved project-local bytecode targets.

## Target-Kind Taxonomy

Measured target-kind counts:

```text
project_method: 210,183
unresolved_project_method: 66,023
jdk_method: 49,692
external_method: 12,671
synthetic_or_compiler_method: 6,112
invokedynamic_method: 4,725
```

Useful derived views:

```text
project_method / all edges: 60.15%
unresolved_project_method / all edges: 18.90%
jdk_method / all edges: 14.22%
external_method / all edges: 3.63%
synthetic_or_compiler_method / all edges: 1.75%
invokedynamic_method / all edges: 1.35%
```

The main product claim is not that every bytecode target maps to source. The
stronger and more defensible claim is:

> CoCoMUT gives every serialized call edge a stable bytecode `target_uri`, and
> upgrades the edge to a source `method_uri` when it can deterministically join
> that bytecode target to exactly one project method.

## Resolution Counts

```text
resolved: 206,227
resolved_return_mismatch_unique: 2,515
resolved_parameter_normalized_unique: 1,101
resolved_normalized_exact: 4
ambiguous: 336
synthetic_or_compiler_generated: 6,112
unresolved: 133,111
```

The important validation signal is that the recent normalization glue is being
used in the field:

- 2,515 edges were recovered by unique return-mismatch handling;
- 1,101 edges were recovered by parameter normalization;
- 336 edges were explicitly marked ambiguous instead of guessed.

This is the intended behavior. CoCoMUT should prefer deterministic joins and
explicit ambiguity over probabilistic matching.

## Unresolved Reasons

```text
project_class_present_method_absent: 61,742
jdk_or_platform_method_outside_project_source: 49,692
external_or_unmodeled_bytecode_method: 12,740
nested_bytecode_class_without_unique_source_method: 6,253
invokedynamic_or_lambda_bytecode_artifact: 4,725
project_method_name_present_but_signature_not_unique_or_compatible: 2,585
anonymous_or_local_class_bytecode: 1,486
multiple_source_methods_match_normalized_parameters: 336
```

### Main Remaining Internal Bucket

`project_class_present_method_absent` was the largest internal-ish unresolved
bucket in the original run.

It means:

- the declaring class is known in the project/source model;
- the bytecode method name/signature could not be matched to an emitted source
  method.

This bucket is high volume, but it is mixed. It can contain:

- synthetic methods not represented as source methods;
- compiler helper methods;
- methods filtered out by source-set/scope;
- generated code from records/enums/lambdas;
- methods from source roots that were not selected;
- source/bytecode model drift in large multi-module projects.

This is not safe to fix with a broad fuzzy rule. CoCoMUT now subdivides it into
deterministic subreasons when possible:

```text
project_class_present_method_absent_synthetic_or_compiler_method
project_class_present_method_absent_enum_generated_method
project_class_present_method_absent_record_component_accessor
project_class_present_method_absent_bytecode_method_not_selected
project_class_present_method_absent_no_matching_bytecode_method
```

These labels improve diagnosis but do not create source `method_uri` values.
The deterministic policy remains: resolve only if unique, emit candidates if
ambiguous, and preserve bytecode-only `target_uri` otherwise.

### Ambiguity Bucket

`multiple_source_methods_match_normalized_parameters` is intentionally not
resolved to one `method_uri`.

Example pattern:

```text
bytecode://org.apache.commons.collections4.FluentIterable.of(java.lang.Object):org.apache.commons.collections4.FluentIterable
```

Candidate source methods included:

```text
FluentIterable.of(java.lang.Iterable)
FluentIterable.of(java.lang.Object)
FluentIterable.of(java.lang.Object[])
```

Because multiple source candidates remain after normalization, CoCoMUT reports
candidate URIs rather than selecting one arbitrarily.

### Nested And Anonymous Bytecode

Nested bytecode names use `$` and may refer to inner, anonymous, local, or
compiler-generated structures. Examples:

```text
bytecode://org.apache.commons.weaver.Finder$AnnotationInflater.inflate():java.lang.annotation.Annotation
bytecode://org.apache.commons.weaver.model.ScanResult$2.childrenOf(...):java.lang.Iterable
```

Some of these can eventually be mapped to a source nested type. Others are
bytecode-only implementation artifacts. The current taxonomy exposes the
distinction instead of hiding it.

## Repository-Level Notes

Largest call-edge contributors:

```text
redis/jedis: 53,359 edges, 78.54% joined to source method_uri
FasterXML/jackson-databind: 48,092 edges, 68.54% joined
apache/pdfbox: 35,387 edges, 56.62% joined
redis/lettuce: 30,359 edges, 51.65% joined
graphhopper/graphhopper: 23,647 edges, 52.77% joined
jtablesaw/tablesaw: 21,807 edges, 69.67% joined
apache/rocketmq: 17,225 edges, 56.31% joined
apache/commons-bcel: 14,156 edges, 69.26% joined
apache/commons-collections: 13,244 edges, 58.58% joined
apache/commons-lang: 9,136 edges, 53.79% joined
```

Lowest source-join rates among repositories with more than 1,000 edges:

```text
apache/commons-jexl: 24.85%
apache/commons-validator: 35.82%
zxing/zxing: 40.27%
apache/commons-jcs: 42.35%
apache/commons-configuration: 43.46%
remkop/picocli: 44.14%
```

These are not necessarily tool regressions. They often have many JDK/external
targets or unresolved project-local bytecode targets.

## Empty Graph Artifact Case

`apache/tomcat` reported:

```text
phase_3_available=true
phase_3_effective_algorithm=RTA
phase_3_call_graphs_generated=976
```

but its call-graph artifact was:

```text
GraphBasedCallGraph(0) is empty
```

and no JSONL row had callers/callees.

This run exposed a reporting nuance in the underlying extraction pipeline:
phase 3 created an RTA artifact, but the artifact had no usable edges. For this
historical run, the reliable metric is serialized edge count, not only
`phase_3_available`.

Current CoCoMUT reporting distinguishes this case explicitly:

```text
phase_3_available=false
phase_3_call_graphs_generated=<per-method result count>
phase_3_non_empty_call_graphs=0
phase_3_call_edges_generated=0
failure_codes=[CALL_GRAPH_EMPTY]
```

## Fallback And Scalability Cases

These repositories completed with bounded source-only fallback and therefore
do not contribute SootUp edge-matching evidence in this run:

```text
ReactiveX/RxJava
TNG/ArchUnit
apache/pulsar
apple/pkl
ben-manes/caffeine
google/gson
grpc/grpc-java
junit-team/junit-framework
micrometer-metrics/micrometer
mockito/mockito
spockframework/spock
spring-projects/spring-boot
spring-projects/spring-security
testcontainers/testcontainers-java
```

Other source-only/no-call-graph successes:

```text
kevinsawicki/http-request
JodaOrg/joda-time
springside/springside4
apache/cassandra-java-driver
github/copilot-sdk
```

This is expected product behavior for a resource-bounded run. It means source
context and Javadoc context remain available, while call-edge context is absent.

## Fix Decision

No matcher code was changed during this validation run.

Reason:

- `target_uri` coverage is already 100% for serialized edges;
- the new deterministic normalization paths are active and measurable;
- remaining high-volume unresolved buckets are mixed and risky to fix with a
  broad rule;
- ambiguous cases are correctly surfaced instead of guessed;
- several no-call-graph cases are scalability/build/fallback issues, not
  Spoon/SootUp join bugs.

The only code change made for this validation was to improve the reusable field
study runner:

- reuse existing OE25/representative checkouts;
- add resource guards for memory and load;
- compute call-edge matching metrics directly from generated JSONL;
- summarize target-kind, resolution, and unresolved-reason counters.

## Recommended Next Work

1. Continue subdividing `project_class_present_method_absent*` after inspecting
   concrete low source-join repositories. Do not fix it with fuzzy matching.

2. Keep the report-level distinction between:
   - call graph artifact exists;
   - call graph artifact has at least one edge;
   - JSONL contains serialized edges.

3. Investigate low source-join repositories individually, starting with:
   - `apache/commons-jexl`;
   - `apache/commons-validator`;
   - `zxing/zxing`;
   - `remkop/picocli`.

4. Keep deterministic matching policy:
   - resolve if unique;
   - emit candidate URIs if ambiguous;
   - keep bytecode-only `target_uri` when source identity is unavailable.
