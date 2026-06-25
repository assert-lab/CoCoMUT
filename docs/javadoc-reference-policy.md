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

When `spoon-javadoc` produces a typed `CtReference`, CoCoMUT resolves semantics
from that typed Spoon reference rather than reparsing `target`. The raw
`target` field remains the source spelling for audit and compatibility; it is
not the authoritative semantic identity when `spoon_reference` is present.

Auxiliary documentation files such as `doc-files/...`, `{@docRoot}/...`,
`@filename ...`, and `{@snippet file="..."}` are recorded under
`file_references`. These are path references, not program-element references.
They use conservative project-root containment checks and include
`parser=cocomut-file-regex`, `parse_confidence=low`, and a `source_form` marker
describing the recognized textual form.

`{@inheritDoc}` is represented as an inheritance candidate relation. CoCoMUT
reports whether an inherited candidate exists and includes bounded inherited
Javadoc snippets, but it does not silently expand inherited `@param`, `@return`,
or `@throws` text into the child method's `structured_tags`. Rows that use
inheritDoc therefore carry `inheritdoc_policy=candidate_only`.

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
