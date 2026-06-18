# Schemas

This directory contains the machine-readable contracts for CoCoX outputs.
Schemas are repository files, not a separate CLI command.

## Files

```text
method-context.schema.json     JSON Schema for one method-context JSONL row
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
selection                 Project/method/type/package target provenance
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

CoCoX uses the same `path#symbol` convention for method, type, and package
selection. See
[../docs/symbol-model.md](../docs/symbol-model.md).

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

`javadoc_references` entries are best-effort source-level resolutions:

```text
tag                       see|link|linkplain
raw                       Raw Javadoc reference text
target                    Parsed reference target
label                     Optional rendered-label text
kind                      type_reference|member_reference|field_reference|external_url|text_reference
resolution                resolved_type|resolved_method|resolved_field|
                          resolved_inherited_method|resolved_inherited_field|
                          overload_ambiguous|ambiguous_field|external_symbol|
                          external|text|unresolved
method_uri                Canonical CoCoX URI for resolved project methods
field_uri                 Canonical CoCoX URI for resolved project fields
type_uri                  Canonical CoCoX URI for resolved project types
referenced_method         Compact method context for resolved project methods:
                          URI, signature, source, Javadoc, params, return, throws
field_javadoc             Full field Javadoc for resolved project fields
class_javadoc             Full class/type Javadoc for resolved project types
candidate_method_uris     Present when omitted or explicit parameters are ambiguous
ambiguity_reason          Why an overload could not be uniquely selected
external_class/member     Symbol-only external reference when source/Javadoc is unavailable
external_resolution       qualified_symbol|explicit_import|implicit_java_lang|
                          wildcard_import_symbol|common_jdk_probe|unresolved
external_member_kind      method|field|unknown for external members
```

CoCoX recognizes the standard doclet `@see` forms: quoted text entries,
HTML anchor links, and program-element references such as
`module/package.Type#member label`. Module prefixes are normalized before
symbol lookup.

External references are intentionally symbol-level only in the current schema.
CoCoX does not fetch JDK/dependency source jars or generated Javadoc pages for
external `@see` / `{@link ...}` targets, because that behavior depends heavily
on local build artifacts and would make dataset provenance noisier.

When a target omits parameters, for example `@see #parse`, CoCoX resolves it
only if there is a single project method named `parse` in the target class. If
multiple overloads exist, it reports `overload_ambiguous` and emits candidate
method URIs instead of guessing. When a target includes parameters, for example
`@see #parse(String, int)`, CoCoX matches those parameter types against source
and erased parameter types.

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

## Versioning

Generated method-context rows include:

```text
metadata.schema_version
```

Schema changes should preserve backward-compatible fields where possible. If a
field is renamed, removed, or changes meaning, update this README and the
schema file.
