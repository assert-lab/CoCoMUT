# Javadoc Reference Policy

CoCoX parses common Javadoc tags using the official Oracle/JDK doc-comment
syntax and standard doclet model. The implementation should not add
repository-specific parsing rules for a single project such as Apache Commons
Lang.

## Supported Tags

CoCoX extracts structured metadata for common block and inline tags used in
method documentation:

- `@param`
- `@return`
- `@throws` / `@exception`
- `@since`
- `@deprecated`
- `@apiNote`
- `@implSpec`
- `@implNote`
- `@see`
- `{@link ...}`
- `{@linkplain ...}`
- `{@code ...}`
- `{@literal ...}`
- `{@value ...}`
- `{@inheritDoc}`

## Reference Forms

For `@see`, `{@link ...}`, and `{@linkplain ...}`, CoCoX follows the standard
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
module/package.Type#member(parameter.Types) label text
```

CoCoX also recognizes the standard non-program-element `@see` forms:

```text
@see "text"
@see <a href="https://example.org">label</a>
```

Project-local references may be resolved to CoCoX URIs. External JDK or library
references are kept as symbol-level metadata only; CoCoX does not fetch external
Javadoc text.

Each resolved reference also gets derived taxonomy fields for empirical
analysis:

- `reference_target_kind`: `method`, `field`, `type`, `url`, `text`,
  `method_or_field`, or `unknown`;
- `reference_domain`: `project`, `external_jdk`, `external_library`,
  `external_web`, `text`, or `unresolved`;
- `reference_scope`: `same_type`, `same_package`, `same_module`, `external`,
  `text`, or `unknown`.

These taxonomy fields summarize CoCoX's resolution result. They are not
additional Javadoc syntax and should not replace canonical method/type/field
URIs when a project-local target is resolved.

## Resolution Policy

CoCoX resolves project-local references in this order:

1. Direct same-type member references such as `#parse(String)`.
2. Imported or fully qualified type references such as `Parser#parse(String)`.
3. Project type references such as `Parser`.
4. Project field references such as `Constants#DEFAULT_TIMEOUT`.
5. Superclass/interface candidates for inherited documentation and unresolved
   direct members.

When a target omits parameter types and more than one project method could
match, CoCoX records an overload ambiguity instead of guessing.

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
`{@snippet ...}`, and Markdown documentation comments; CoCoX treats
version-specific features explicitly instead of inferring rules from one
repository.
