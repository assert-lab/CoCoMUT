# Call-Edge Resolution Report

This note documents the bytecode-to-source call-edge matching work introduced
for schema `0.4.0`.

CoCoMUT uses Spoon to build source-backed method contexts and SootUp to build
optional bytecode call graphs. Those two worlds do not use the same identity:

- Spoon identifies source declarations with CoCoMUT method URIs.
- SootUp reports bytecode signatures such as
  `<java.lang.Math: int max(int,int)>`.

The join between those two representations must be deterministic. CoCoMUT should
map a SootUp edge to a source `method_uri` only when there is one unique source
method candidate. If not, the edge is still useful context, but it must stay
bytecode-level.

## Output Model

Every call edge now has two identity layers:

```json
{
  "method_uri": "src/main/java/com/acme/Foo.java#com.acme.Foo.f(java.lang.String):void",
  "target_uri": "bytecode://com.acme.Foo.f(java.lang.String):void",
  "target_kind": "project_method",
  "raw_signature": "<com.acme.Foo: void f(java.lang.String)>",
  "resolution": "resolved"
}
```

`method_uri` is strict. It is populated only when the target maps to one
extracted project source method.

`target_uri` is broader. It is always derived from the SootUp signature and is
available even for JDK, dependency, synthetic, ambiguous, or bytecode-only
targets.

For example:

```json
{
  "method_uri": "",
  "target_uri": "bytecode://java.lang.Math.max(int,int):int",
  "target_kind": "jdk_method",
  "raw_signature": "<java.lang.Math: int max(int,int)>",
  "resolution": "unresolved",
  "unresolved_reason": "jdk_or_platform_method_outside_project_source"
}
```

This keeps downstream tools from confusing an external bytecode edge with a
source-backed project method.

## What Was Fixed

### Priority 1: Return-Type Mismatch

Problem:

SootUp may report a return type that does not textually match a source
declaration. CoCoMUT already stores both source return type and erased return
type, and the canonical `method_uri` uses the erased return type. This fix is
about joining a SootUp bytecode edge to the correct Spoon/CoCoMUT source method
when the bytecode return and the stored source identity still differ textually.
Common causes are generic erasure, bridge methods, covariant returns, and
source/bytecode normalization differences.

Example shape:

```java
class Box<T> {
    T get() { ... }
}
```

Spoon can expose the source return as `T`, while CoCoMUT stores the source method
with an erased-return URI such as:

```text
src/main/java/example/Box.java#example.Box.get():java.lang.Object
```

SootUp reports the bytecode target separately, for example:

```text
bytecode://example.Box.get():java.lang.Object
```

In normal cases these erased forms match directly. The `resolved_return_mismatch_unique`
path covers the remaining cases where the declaring class, method name, and
parameters identify one source method but the return string still differs due to
bridge/covariant/source-bytecode representation differences.

Fix:

If declaring class, method name, and normalized parameter list match exactly and
there is one source candidate, CoCoMUT resolves the edge even when the return type
differs:

```text
resolution = resolved_return_mismatch_unique
```

Why this is safe:

Java overload selection does not use the return type. If class + name +
parameters identify exactly one source method, the return mismatch is treated as
a representation mismatch rather than a separate overload.

Boundary:

If multiple source candidates remain, CoCoMUT does not guess. It emits
`resolution = ambiguous` and `candidate_method_uris`.

### Priority 2: Parameter Normalization Mismatch

Problem:

Source and bytecode can format equivalent parameters differently:

- `String...` versus `String[]`;
- generic parameters versus erased parameters;
- nested class names with `$` versus `.`;
- source signatures with annotations, `final`, and parameter names;
- simple names versus fully-qualified names.

Example:

```java
void log(final String... messages) { ... }
```

The source side may look like `log(final String... messages)`, while bytecode
looks like `log(java.lang.String[])`.

Fix:

CoCoMUT now normalizes parameters before matching:

- strips parameter names and common modifiers;
- strips source annotations from parameter declarations;
- converts varargs to arrays;
- removes generic type arguments;
- normalizes `$` and `.` for nested names;
- compares both fully-qualified and simple names when needed.

If exactly one compatible source candidate remains:

```text
resolution = resolved_parameter_normalized_unique
```

Boundary:

If more than one compatible overload remains, CoCoMUT records:

```json
{
  "resolution": "ambiguous",
  "candidate_method_uris": [
    "src/main/java/com/acme/Foo.java#com.acme.Foo.f(java.lang.String):void",
    "src/main/java/com/acme/Foo.java#com.acme.Foo.f(java.lang.Object):void"
  ],
  "unresolved_reason": "multiple_source_methods_match_normalized_parameters"
}
```

### Priority 3: Exact Source Exists But Join Failed

Problem:

Some edges had a source method that should have matched, but the call-graph join
used a different normalization path from the source-method index.

Fix:

CoCoMUT now builds a normalized source-method index and uses the same key family
for lookup. This adds a second exact normalized path before looser matching:

```text
resolution = resolved_normalized_exact
```

This is a correctness fix rather than a heuristic.

### Priority 4: Declaring Class Present, Method Absent

Problem:

This was the largest unresolved bucket in the OE25 plus representative run, but
it is mixed. It can contain:

- synthetic bytecode methods;
- bridge methods;
- lambda/invokedynamic targets;
- enum/record generated methods;
- methods filtered out by source-set or bounded extraction;
- Lombok or annotation-processor generated methods;
- real CoCoMUT indexing misses.

Fix:

CoCoMUT does not blindly resolve this bucket. It now emits deterministic
diagnostic reasons:

```text
project_class_present_method_absent
project_method_name_present_but_signature_not_unique_or_compatible
external_or_unmodeled_bytecode_method
```

This makes the bucket measurable and debuggable without inventing source URIs.

Boundary:

This is mostly classification, not full recovery. The next engineering step is
to subdivide this bucket into synthetic, generated, filtered, and true-miss
cases using concrete examples from field runs.

### Priority 5: Nested Bytecode Classes

Problem:

SootUp sees bytecode names such as `Outer$Inner`, `Outer$1`, and compiler-made
members. Spoon sees source declarations and may model nested, anonymous, or
local classes differently.

Fix:

CoCoMUT now distinguishes:

```text
nested_bytecode_class_without_unique_source_method
anonymous_or_local_class_bytecode
invokedynamic_or_lambda_bytecode_artifact
synthetic_or_compiler_generated
```

When an outer source class exists but the nested bytecode target cannot be
mapped uniquely, CoCoMUT keeps the bytecode `target_uri` and leaves `method_uri`
empty.

Boundary:

CoCoMUT does not yet map anonymous/local class methods into source-level
synthetic URIs. That would require a separate model for bytecode-only source
locations and would risk creating unstable identifiers.

## Verified Commons Lang Impact

The implementation was compared against `origin/main` on the same Apache
Commons Lang checkout with:

```bash
--compile --resolution auto --call-graph auto --source-set main --scope all
```

Strict A/B result:

```text
Metric                     origin/main   branch
-------------------------  -----------   ------
JSONL rows                 4489          4489
call edges                 12278         12278
resolved source edges      7981          8148
unresolved edges           4297          4130
resolution rate            65.00%        66.36%
```

Newly recovered source edges:

```text
resolved_return_mismatch_unique       41
resolved_parameter_normalized_unique  40
resolved_normalized_exact             86
```

The branch also labels all unresolved edges with `target_kind` and, where
possible, `unresolved_reason`.

Commons Lang branch breakdown:

```text
target_kind
------------------------------  -----
project_method                   8153
jdk_method                       3592
invokedynamic_method              257
synthetic_or_compiler_method      150
unresolved_project_method         126

unresolved_reason
-----------------------------------------------  -----
jdk_or_platform_method_outside_project_source     3592
invokedynamic_or_lambda_bytecode_artifact           257
project_class_present_method_absent                 154
project_method_name_present_but_signature_not_unique_or_compatible  103
nested_bytecode_class_without_unique_source_method   19
multiple_source_methods_match_normalized_parameters   5
```

## Limitations

- The strict before/after measurement above is for Commons Lang. The broader
  OE25 plus representative suite should be rerun before claiming aggregate
  recovery rates.
- `target_uri` is bytecode identity, not source identity. It is stable for a
  SootUp signature, but it should not be used as a replacement for CoCoMUT
  `method_uri`.
- External and JDK targets remain symbol-level only. CoCoMUT does not fetch
  external source or Javadoc text for call graph edges.
- Synthetic, bridge, enum-generated, record-generated, anonymous, and local
  class methods are classified conservatively unless they map to one unique
  source method.
- Parameter normalization is intentionally deterministic. It does not use fuzzy
  scoring or probabilistic matching.
- Bounded extraction can still leave legitimate source methods outside the
  extracted model. In that case a call edge may remain unresolved even if the
  method exists in the full repository.

## Practical Interpretation

For documentation mining, callers and callees now have clearer provenance:

- use `method_uri` when you need actual source/Javadoc context from the same
  CoCoMUT output;
- use `target_uri` when you need to count or inspect every bytecode edge;
- use `target_kind`, `resolution`, and `unresolved_reason` to filter noisy call
  graph context before feeding it to downstream models.
