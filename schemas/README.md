# Schemas

This directory contains the machine-readable contracts for CoCoMUT outputs.
Schemas are repository files, not a separate CLI command.

## Files

```text
method-context.schema.json     JSON Schema for one method-context JSONL row
```

## Method Context Rows

CoCoMUT writes JSONL. Each line in a generated `*.jsonl` file is one complete
method-context object that follows `method-context.schema.json`.

The schema is intentionally permissive with `additionalProperties: true` so CoCoMUT
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

`lines_of_code` and `cyclomatic_complexity` are lexical estimates derived from
the emitted method source, not AST control-flow measurements. Use them for
coarse filtering, not as canonical structural metrics.

CoCoMUT uses the same `path#symbol` convention for method, type, and package
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
reference_target_kind     method|field|type|url|text|method_or_field|unknown
reference_domain          project|external_jdk|external_library|external_web|text|unresolved
reference_scope           same_type|same_package|same_module|external|text|unknown
method_uri                Canonical CoCoMUT URI for resolved project methods
field_uri                 Canonical CoCoMUT URI for resolved project fields
type_uri                  Canonical CoCoMUT URI for resolved project types
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

`reference_target_kind`, `reference_domain`, and `reference_scope` are derived
taxonomy fields for empirical analysis. They summarize the resolved reference
without replacing the lower-level `kind`, `resolution`, URI, and context fields.
`same_type` means the target is in the declaring type of the documented method;
`same_package` means another project type in the same package; `same_module`
means project-local but in a different package.

CoCoMUT recognizes the standard doclet `@see` forms: quoted text entries,
HTML anchor links, and program-element references such as
`module/package.Type#member label`. Module prefixes are normalized before
symbol lookup.

External references are intentionally symbol-level only in the current schema.
CoCoMUT does not fetch JDK/dependency source jars or generated Javadoc pages for
external `@see` / `{@link ...}` targets, because that behavior depends heavily
on local build artifacts and would make dataset provenance noisier.

## Extraction Manifest

Every extraction also writes `extraction_manifest.json` beside the JSONL file.
This is run-level metadata, not method-level context. The manifest records:

```text
schema_version                  Manifest schema version
generated_at                    Timestamp for the extraction run
tool / tool_version             CoCoMUT release identity
request_hash                    Hash of selection and extraction options
selection                       Same selection provenance stored in JSONL rows
project.name/path/build_system  Analyzed project identity
project.git.remote_url          Git remote when the checkout exposes one
project.git.commit              Git commit when available
project.git.dirty               Boolean when known, null when git state is unknown
build.attempted                 Whether CoCoMUT executed Maven/Gradle
build.exit_code                 Build-process exit code, or -1 when not attempted
build.succeeded                 Whether the attempted build command succeeded
build.timed_out                 Whether the attempted build timed out
build.skipped                   Whether build execution was denied
build.sandboxed                 Whether caller claims external sandboxing
build.policy                    DENY_BUILD, ALLOW_UNSANDBOXED_BUILD, or
                                EXTERNALLY_SANDBOXED_BUILD
build.bytecode_available        Whether project bytecode was found
build.bytecode_origin           generated_this_run, preexisting, explicit, or none
build.analysis_can_proceed      Whether extraction has project bytecode to analyze
artifacts.*                     Source roots, class outputs, jars, explicit inputs
hashes.algorithm/format         Hash algorithm and deterministic digest format
hashes.main_bytecode            Hash over main outputs and project JARs
hashes.test_bytecode            Hash over test outputs
hashes.combined_project_bytecode
                                Hash over main, test, and project JAR bytecode
hashes.dependency_classpath     Hash over dependency JARs/directories
hashes.emitted_jsonl            Hash over generated JSONL when present
```

The manifest is intentionally separate from the JSONL rows. Dataset rows remain
method-centric, while repository revision, build policy, and artifact hashes are
auditable at extraction-run granularity.

Each hash entry has `{role, sha256, status, errors}`. `sha256` is a 64-character
hex digest when `status` is `ok`; it is `null` for `empty`, `missing`, or
`error`. Artifact hashes do not include host-specific absolute paths, so two
checkouts with identical artifact content can be compared across machines.

When a target omits parameters, for example `@see #parse`, CoCoMUT resolves it
only if there is a single project method named `parse` in the target class. If
multiple overloads exist, it reports `overload_ambiguous` and emits candidate
method URIs instead of guessing. When a target includes parameters, for example
`@see #parse(String, int)`, CoCoMUT matches those parameter types against source
and erased parameter types.

Call graph arrays are normalized edge objects, not raw strings. CoCoMUT keeps
source-backed method identity separate from bytecode-level edge identity:

```text
kind                      project_method|ambiguous_project_method|
                          unresolved_project_method|jdk_method|external_method|
                          bytecode_method|invokedynamic_method|
                          synthetic_or_compiler_method
method_uri                CoCoMUT source method URI only when the edge resolves
                          to a unique extracted project method
target_uri                Universal bytecode-level URI derived from the SootUp
                          signature, present even when method_uri is empty
target_kind               project_method|unresolved_project_method|jdk_method|
                          external_method|bytecode_method|invokedynamic_method|
                          synthetic_or_compiler_method
raw_signature             SootUp bytecode signature retained as provenance
declaring_class           Declaring class reported by SootUp
method_name               Method name reported by SootUp
resolution                resolved|resolved_normalized_exact|
                          resolved_return_mismatch_unique|
                          resolved_parameter_normalized_unique|
                          ambiguous|unresolved|
                          synthetic_or_compiler_generated
context_in_output         true only when the resolved method's full context is
                          included in the current JSONL selection
candidate_method_uris     Candidate source method URIs when a project edge is
                          plausible but not uniquely resolvable
unresolved_reason         Deterministic reason why method_uri is empty
context                   Optional method node when method_uri resolves to an extracted context
```

`target_uri` lets downstream tools identify every SootUp edge. `method_uri`
remains stricter: it is populated only when the bytecode edge maps to one unique
source method in the CoCoMUT/Spoon model. This avoids treating JDK, dependency,
synthetic, ambiguous, or bytecode-only targets as source-backed methods.

The source join universe is the full project source model. Focal-method filters
such as `--scope`, `--source-set`, `--package`, `--class`, `--method`,
`--visibility`, path filters, and `--max-methods` control which methods receive
top-level JSONL rows; they do not remove methods from bytecode-to-source
identity resolution. When an edge resolves to a project method outside the
current output selection, `method_uri` is still populated and
`context_in_output` is `false`.

### Call Graph Target Taxonomy

`target_kind` classifies the bytecode target reported by SootUp. It is not the
same as `reference_target_kind`, which is used for Javadoc references.

```text
target_kind                   Meaning
----------------------------  ------------------------------------------------
project_method                SootUp target resolved to one unique CoCoMUT/Spoon
                              project source method. method_uri is present.
unresolved_project_method     Target appears to belong to a project class, but
                              CoCoMUT could not identify one unique source method.
jdk_method                    Target belongs to JDK/platform classes such as
                              java.*, javax.*, jdk.*, sun.*, com.sun.*,
                              org.w3c.dom.*, or org.xml.sax.*.
external_method               Target belongs to a dependency or otherwise
                              unmodeled external class.
bytecode_method               Generic bytecode target when CoCoMUT cannot classify
                              the edge more specifically.
invokedynamic_method          Lambda, method-handle, or invokedynamic bytecode
                              artifact rather than a normal source declaration.
synthetic_or_compiler_method  Compiler-generated helper such as access$...,
                              lambda$..., or similar synthetic bytecode method.
```

Common unresolved reasons:

```text
unresolved_reason                                      Meaning
-----------------------------------------------------  -----------------------
jdk_or_platform_method_outside_project_source          JDK/platform method.
external_or_unmodeled_bytecode_method                  Dependency or unmodeled method.
invokedynamic_or_lambda_bytecode_artifact              Lambda/invokedynamic artifact.
anonymous_or_local_class_bytecode                      Anonymous/local class bytecode.
nested_bytecode_class_without_unique_source_method     Nested bytecode class could not be
                                                       joined to one source method.
project_method_name_present_but_signature_not_unique_or_compatible
                                                       Project class and method name exist,
                                                       but signature matching is not unique.
multiple_source_methods_match_normalized_parameters    Normalization found multiple source
                                                       candidates; see candidate_method_uris.
project_class_present_method_absent_synthetic_or_compiler_method
                                                       Project class exists; bytecode target is
                                                       a compiler helper such as access$...
project_class_present_method_absent_enum_generated_method
                                                       Project enum exists; bytecode target is a
                                                       generated enum method such as values().
project_class_present_method_absent_record_component_accessor
                                                       Project record exists; bytecode target is
                                                       a generated component accessor.
project_class_present_method_absent_bytecode_method_not_selected
                                                       SootUp sees the method in bytecode but it
                                                       is not in the selected source-method set.
project_class_present_method_absent_no_matching_bytecode_method
                                                       Project class exists, but CoCoMUT cannot
                                                       find a matching source or bytecode method.
```

For every call edge:

```text
target_uri exists  => SootUp reported a bytecode-level target
method_uri exists  => that target was joined to one unique project source method
```

Therefore `method_uri` implies `target_uri`, but `target_uri` does not imply
`method_uri`.

Common non-mapping examples:

```json
{
  "method_uri": "",
  "target_uri": "bytecode://java.util.Objects.requireNonNull(java.lang.Object):java.lang.Object",
  "target_kind": "jdk_method",
  "resolution": "unresolved",
  "unresolved_reason": "jdk_or_platform_method_outside_project_source"
}
```

```json
{
  "method_uri": "",
  "target_uri": "bytecode://com.example.Foo.f(java.lang.Object):void",
  "target_kind": "project_method",
  "resolution": "ambiguous",
  "candidate_method_uris": [
    "src/main/java/com/example/Foo.java#com.example.Foo.f(java.lang.String):void",
    "src/main/java/com/example/Foo.java#com.example.Foo.f(java.lang.Object):void"
  ],
  "unresolved_reason": "multiple_source_methods_match_normalized_parameters"
}
```

## Versioning

Generated method-context rows include:

```text
metadata.schema_version
```

Schema changes should preserve backward-compatible fields where possible. If a
field is renamed, removed, or changes meaning, update this README and the
schema file.
