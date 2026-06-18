# Schemas

This directory contains the machine-readable contracts for CoCoX inputs and
outputs. The same schema files are bundled in the CLI jar and can be printed
with:

```bash
./bin/cocox schema method-context
./bin/cocox schema selected-methods
```

## Files

```text
method-context.schema.json     JSON Schema for one method-context JSONL row
selected-methods.schema.json   JSON Schema for preferred selected-method input rows
```

## Method Context Rows

CoCoX writes JSONL. Each line in a generated `*.jsonl` file is one complete
method-context object that follows `method-context.schema.json`.

The schema is intentionally permissive with `additionalProperties: true` so CoCoX
can add research fields without breaking older consumers. Consumers should rely
on the documented stable fields and ignore unknown fields by default.

Stable top-level sections:

```text
MUT                       Focal method/source method
callers                   Optional caller context from SootUp call graph
callees                   Optional callee context from SootUp call graph
metadata                  Schema, backend, method identity, and call graph metadata
provenance                Extraction source and confidence information
documentation_metrics     Computed Javadoc quality flags
javadoc_metadata          Parsed @see, @since, inline links, deprecation, inheritDoc hints
dynamic_features          Static hints for reflection, proxies, service loaders, DI, native code
original_docstring        Selected-CSV metadata, only when supplied
test_prefix               Selected-CSV metadata, only when supplied
```

Important `MUT` fields:

```text
method_uri                Stable method identity: path#qualified.Class.method(erasedParamTypes):erasedReturnType
method_name               Simple method or constructor name
source_set                main|test|integration_test|generated|example|unknown
signature                 Human-readable source signature
return_type               Source return type
erased_return_type        Erased return type used in method_uri
qualified_name            Qualified class plus method name
parameters                Parameter objects with name, source type, erased_type, modifiers, annotations
annotations               Method annotations
throws                    Declared thrown exception types
code                      Method/constructor source without leading Javadoc; annotations are kept
javadoc                   Method Javadoc, stored separately from code
class_javadoc             Declaring class Javadoc when available
class_hierarchy           Source hierarchy and resolution confidence
source_context            Field reads/writes, overload group, sibling methods
```

Important `javadoc_metadata` fields:

```text
see                       Raw @see targets
inline_links              Raw inline {@link ...}/{@linkplain ...} targets
javadoc_references        Resolved reference objects for @see/link/linkplain targets
file_references           Referenced doc-files/images/html/text/sample-source paths when present
structured_tags           Parsed param/return/throws/since/apiNote/implSpec/implNote/deprecated text
inheritdoc_resolution     not_used|resolved_candidate|unresolved
inherited_javadoc_candidates
                          Candidate inherited Javadoc snippets for {@inheritDoc}
```

Call graph arrays are normalized edge objects, not raw strings:

```text
kind                      project_method|unresolved_method|synthetic_or_compiler_method
method_uri                CoCoX method URI when the edge resolves to a project method
raw_signature             SootUp bytecode signature retained as provenance
declaring_class           Declaring class reported by SootUp
method_name               Method name reported by SootUp
resolution                resolved|unresolved|synthetic_or_compiler_generated
context                   Optional method node when method_uri resolves to an extracted context
```

## Selected Methods

`selected-methods.schema.json` documents the preferred selected-method input
shape. CoCoX accepts pipe-delimited CSVs with at least:

```text
method_uri
```

Optional selected-mode metadata:

```text
docstring
test_prefix
```

Preferred example:

```csv
method_uri|docstring|test_prefix
src/main/java/com/example/Hello.java#com.example.Hello.greet(java.lang.String):java.lang.String|Greets a person.|new Hello().greet("x")
```

Legacy PoC CSVs with `focal_method|test_prefix|docstring|id` are still accepted
for compatibility, but `method_uri` is the stable input identity.

## Validation

Validate generated JSONL:

```bash
./bin/cocox validate --jsonl path/to/method_contexts.jsonl
```

Validate selected-method input shape:

```bash
./bin/cocox validate --selected selected-methods.csv
```

## Versioning

Generated method-context rows include:

```text
metadata.schema_version
```

Schema changes should preserve backward-compatible fields where possible. If a
field is renamed, removed, or changes meaning, update this README, the schema
file, and the CLI-bundled copy under
`cocox-cli/src/main/resources/schemas/`.
