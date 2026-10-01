# Javadoc Reference Policy

CoCoMUT parses common Javadoc tags using Spoon's official `spoon-javadoc`
module, which follows the Oracle/JDK documentation-comment syntax and standard
doclet model. The dependency is resolved from normal Maven providers as
`fr.inria.gforge.spoon:spoon-javadoc`; it is not read from a local Spoon
checkout. The implementation should not add repository-specific parsing rules
for a single project such as Apache Commons Lang.

## Supported Tags

CoCoMUT extracts structured metadata for common block tags used in method
documentation:

- `@param`
- `@return`
- `@throws` / `@exception`
- `@since`
- `@deprecated`
- `@apiNote`
- `@implSpec`
- `@implNote`
- `@see`

CoCoMUT also parses common inline tags as part of rendered text, reference
metadata, and documentation metrics. They are not emitted as first-class
`structured_tags` objects unless the schema names them explicitly:

- `{@link ...}`
- `{@linkplain ...}`
- `{@code ...}`
- `{@literal ...}`
- `{@value ...}`
- `{@inheritDoc}`

## Reference Forms

For `@see`, `{@link ...}`, and `{@linkplain ...}`, CoCoMUT follows the standard
program-element reference form:

```text
module/package.Type#member optional-label
```

Common accepted variants include:

```text
Type
Type#member
#member
package.Type#member(parameter.Types)
Type#member(Type, int)
module/package.Type#member(parameter.Types) label text
```

CoCoMUT also recognizes the standard non-program-element `@see` forms:

```text
@see "text"
@see <a href="https://example.org">label</a>
```

Project-local references may be resolved to CoCoMUT URIs. External JDK or library
references are kept as symbol-level metadata only; CoCoMUT does not fetch external
Javadoc text.

Each resolved reference also gets derived taxonomy fields for empirical
analysis:

- `parser`: the parser path used for the reference, normally `spoon-javadoc`;
- `parse_confidence`: `high` for typed Spoon references, `medium` for Spoon
  text fallback, and `low` for CoCoMUT fallback text parsing;
- `spoon_reference`: Spoon's typed reference rendering when available;
- `target`: the source Javadoc spelling, preserved for auditability;
- `canonical_target`: Spoon's normalized target when it differs from `target`;
- `reference_target_kind`: `method`, `field`, `type`, `url`, `text`,
  `method_or_field`, or `unknown`;
- `reference_domain`: `project`, `external_jdk`, `external_library`,
  `external_web`, `text`, or `unresolved`;
- `reference_scope`: `same_type`, `same_package`, `same_module`, `external`,
  `text`, or `unknown`.

These taxonomy fields summarize CoCoMUT's resolution result. They are not
additional Javadoc syntax and should not replace canonical method/type/field
URIs when a project-local target is resolved.

CoCoMUT keeps a fallback text parser only for cases where `spoon-javadoc` cannot
produce reference or structured-tag elements. If Spoon parses some references
but misses a raw reference that CoCoMUT's compatibility scanner can recognize,
the fallback object is still emitted for coverage and auditability, marked with
`parser=cocomut-fallback`, `parse_confidence=low`, and a fallback reason.

Typed Spoon member references supply method/field identity and parameter types.
For type links, CoCoMUT resolves the reliable source spelling in lexical Java
scope, including visible outer types in `Outer.Inner`. Lexical declarations
and inherited member types take precedence over imports. An explicit import
precedes same-package and on-demand imports; ambiguous on-demand outer names
remain unresolved. Binary `$` notation is assigned only after resolving the
outer type. A project-wide simple-name match cannot establish visibility.
These rules apply to typed references and both text fallback paths.

Method, constructor, and visible enclosing-type parameters take precedence over
project type declarations. A reference to one is retained as non-project
evidence, for example:

```json
{
  "tag": "link",
  "raw": "T",
  "target": "T",
  "kind": "type_reference",
  "resolution": "unresolved",
  "unresolved_reason": "lexical_type_parameter",
  "reference_domain": "unresolved",
  "reference_scope": "unknown"
}
```

Such a reference has no `type_uri` or `resolved_type`. It does not claim that
`T` is an unknown project class: `unresolved_reason` records that the current
URI contract does not address lexical type parameters. Type parameters are
also excluded from the globally addressable project type index.

Auxiliary documentation files such as `doc-files/...`, `{@docRoot}/...`,
`@filename ...`, and `{@snippet file="..."}` are recorded under
`file_references`. These are path references, not program-element references.
They use conservative project-root containment checks and include
`parser=cocomut-file-regex`, `parse_confidence=low`, and a `source_form` marker
describing the recognized textual form.

Inherited method documentation has separate declared, candidate, and effective
views. CoCoMUT preserves local tags in `declared_structured_tags` (and the
compatibility alias `structured_tags`), emits ordered structured ancestor
evidence in `inherited_javadoc_candidates`, and computes item-level inherited
descriptions, parameters, return text, and applicable throws text in
`effective_structured_tags`. This applies both to explicit `{@inheritDoc}` and
to items inherited by omission. The effective projection uses the explicit,
host-independent `jdk25-standard-doclet` policy; composed text carries ordered
segment provenance. See
[Inherited Javadoc Resolution](inherited-javadoc-resolution.md) for search
order, provenance, uncertainty, and status invariants.

## Resolution Policy

CoCoMUT resolves project-local references in this order:

1. Direct same-type member references such as `#parse(String)`.
2. Imported or fully qualified type references such as `Parser#parse(String)`.
3. Project type references such as `Parser`.
4. Project field references such as `Constants#DEFAULT_TIMEOUT`.
5. Superclass/interface candidates for inherited documentation and unresolved
   direct members.

When a target omits parameter types and more than one project method could
match, CoCoMUT records an overload ambiguity instead of guessing.

External JDK/library references are classified as external method, external
field, or external type symbols when possible. They are not expanded into
external source code or external Javadoc excerpts.

## Regression Checks

`JavadocLexicalResolutionTest` covers visible outer types, lexical shadowing,
ambiguous and unrelated names, type parameters, and emitted schema validity.
Run it with:

```sh
./mvnw -Dtest=JavadocLexicalResolutionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`JavadocPinnedSubjectsTest` checks the exact audited method URIs from issues
#22 and #23 through source lookup, final context extraction, JSONL emission,
and schema validation. It skips unless the subject paths are supplied. Use
Jedis commit `60b6eaa041aac701f5a5c52a410eafc4a9d81c3c` and Fastjson2 commit
`3697c2d37cd659d2a94543093d0d08cb4baf4d73`, with existing compiled outputs:

```sh
./mvnw -Dtest=JavadocPinnedSubjectsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcocomut.jedisProject=/path/to/pinned/jedis \
  -Dcocomut.fastjsonProject=/path/to/pinned/fastjson2 \
  -Dcocomut.lexicalOutputDir=/path/to/results \
  '-DargLine=-Xmx4g -da:spoon...' test
```

These tests do not rebuild subjects or generate a call graph. The Spoon-specific
assertion setting matches the normal CLI JVM and avoids a separate Spoon
assertion failure tracked in issue #36; JUnit checks remain active. The regular
full suite runs with the default assertion settings. Isolated source mirrors
can supply `.cocomut-pinned-revision` after their inputs have been verified
against the pinned checkout; normal checkouts are checked with `git rev-parse`.

## Official Sources

The reference behavior is based on these JDK documents:

- JDK 17 documentation-comment specification for the standard doclet:
  <https://docs.oracle.com/en/java/javase/17/docs/specs/javadoc/doc-comment-spec.html>
- JDK 25 documentation-comment specification for the standard doclet:
  <https://docs.oracle.com/en/java/javase/25/docs/specs/javadoc/doc-comment-spec.html>
- JDK 8 Javadoc tool reference:
  <https://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html>

These specifications are versioned with the JDK. The core block tags, inline
tags, `@see`, and `{@link ...}` forms are long-standing and stable. Newer JDKs
add or extend features such as module prefixes, inline `{@return ...}`,
`{@snippet ...}`, and Markdown documentation comments; CoCoMUT treats
version-specific features explicitly instead of inferring rules from one
repository.
