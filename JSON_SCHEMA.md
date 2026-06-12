# JSON Output Schema

Current schema version: `0.3.0`.

The human-readable contract is documented here. Machine-readable schema drafts are kept in:

```text
schemas/method-context.schema.json
schemas/selected-methods.schema.json
```

They are also bundled in the standalone CLI jar:

```bash
./bin/c4dg schema method-context
./bin/c4dg schema selected-methods
```

## Top-Level Fields

```text
MUT                       Method under test/source method
callers                   Caller context from optional call graph
callees                   Callee context from optional call graph
metadata                  Tool/schema/algorithm metadata
provenance                Extraction confidence and source provenance
documentation_metrics     Computed Javadoc quality flags
dynamic_features          Static hints for reflection, DI, service loaders, proxies, etc.
javadoc_metadata          Parsed @since, @see, inline links, deprecation, inheritDoc hints
original_docstring        Present only in selected CSV mode
test_prefix               Present only in selected CSV mode
```

The CLI validator requires the core method identity, source-context, metadata,
Javadoc metadata, documentation metrics, and dynamic-feature fields. This is
stricter than a pure "is it JSON" check and is intended to catch broken or
partial C4DG records early.

## MUT

```text
method_uri                Stable method URI: path#qualified.Class.method(signature)
method_name               Simple method or constructor name
source_set                main|test|integration_test|generated|example|unknown
signature                 Qualified method signature
qualified_name            qualified class + method name
line_number               Source line
parameters                Objects with name, type, modifiers, annotations
annotations               Method annotations
throws                    Declared thrown exception types
lines_of_code             Source LOC heuristic
cyclomatic_complexity     Source complexity heuristic
code                      Method/constructor source
javadoc                   Method Javadoc from Spoon
class_javadoc             Class Javadoc from Spoon
class_hierarchy           Source hierarchy plus resolution confidence
source_context            Field reads/writes, siblings, overload group
```

## Metadata And Provenance

```text
metadata.schema_version           0.3.0
metadata.source_backend           spoon
metadata.source_backend_mode      noclasspath
metadata.method_identity          uri
metadata.type_resolution          resolved|partial|missing
metadata.call_graph.available     true|false
metadata.call_graph.tool          SootUp|N/A
metadata.call_graph.algorithm     CHA|RTA|N/A
metadata.call_graph.confidence    conservative|instantiation_filtered|missing
failure_codes                    Report-level degradation/failure codes

provenance.method_source          source_scan|selected_csv
provenance.method_matching        spoon
provenance.javadoc_extraction     spoon
provenance.compiled_project       true|false
provenance.hierarchy_resolution   resolved|partial|missing
```

## Documentation Metrics

```text
has_summary
has_param_tags
missing_param_tags
has_return_tag
has_throws_tag
mentions_null
mentions_examples
uses_inheritdoc
has_since_tag
has_see_tag
inline_link_count
```

These are computed fields for empirical filtering. They do not replace the raw Javadoc text.

## Javadoc Metadata

```text
since
see
inline_links
uses_inheritdoc
inheritdoc_resolution     not_used|resolved_candidate|unresolved
inherited_javadoc_candidates
deprecated
deprecation_text
```

`inheritdoc_resolution` and `inherited_javadoc_candidates` are static hints.
C4DG does not merge inherited documentation into the emitted Javadoc text.

## Failure Artifacts

When extraction degrades but can continue, C4DG writes JSONL failure artifacts
next to the normal outputs:

```text
selected_method_failures.jsonl  selected CSV rows that did not resolve to a method
method_context_failures.jsonl   discovered methods whose context could not be built
```

These files are intentionally separate from `method_contexts.jsonl`, so dataset
consumers can filter failures without mixing them with valid method-context rows.
