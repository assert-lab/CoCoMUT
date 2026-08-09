# Inherited Javadoc Resolution

CoCoMUT reports both the Javadoc written on a method and the documentation that
the standard Javadoc inheritance rules make effective for that method. These
are different views and are never merged destructively.

This document describes the schema introduced in output version `0.5.0` and
the resolver implemented by
[`InheritedJavadocResolver`](../analyzer-core/src/main/java/org/assertlab/cocomut/source/InheritedJavadocResolver.java).

## Why a dedicated resolver is necessary

Spoon's `CtMethod.getTopDefinitions()` returns the roots of an override chain.
That API is useful for source analysis, but it does not answer Javadoc's
question: which overridden declaration should be searched first for a specific
documentation item?

For this hierarchy:

```text
Object.clone()
  -> BasicBuilderParameters.clone()
    -> FileBasedBuilderParametersImpl.clone()
```

the top definition can be `Object.clone()`, while the nearest relevant
documentation is on `BasicBuilderParameters.clone()`. CoCoMUT therefore walks
the hierarchy directly and uses Spoon's override machinery only to decide
whether method declarations correspond.

## Search order

The resolver follows the standard doclet's automatic supertype search:

1. Search the direct superclass, excluding `java.lang.Object` from this phase.
2. Recursively search that superclass's superclass and interfaces.
3. Search direct interfaces in declaration order, recursively.
4. Consider `java.lang.Object` in a final phase.

For the example hierarchy in the JDK specification, this produces
`F, D, B, A, C, E, Object`. The final Object phase matters for methods such as
`equals`, `hashCode`, and `toString`, but it must not hide more specific
interface documentation.

The search selects a supertype because it declares an overridden method, not
because that declaration happens to contain the requested tag. If the selected
method omits an item, the resolver restarts the same search from that method.
It does not fall through to a sibling interface of the original focal method.
This branch-preserving recursion is important when multiple interfaces declare
the same method but document different items.

The implementation bounds traversal to 256 distinct types. If the bound is
reached, `inheritdoc_candidates_truncated` is `true` and unresolved effective
items are marked indeterminate.

## Declared and effective views

The local source remains authoritative for what the developer wrote:

- `MUT.javadoc` contains the focal method's local comment.
- `javadoc_metadata.structured_tags` remains a compatibility alias for local,
  parser-derived tags.
- `javadoc_metadata.declared_structured_tags` explicitly names that same local
  view.

`javadoc_metadata.effective_structured_tags` is a separate projection of the
documentation a user would see after inheritance. It covers only items to
which the standard method-documentation inheritance rules apply:

- main description;
- method type parameters, matched by position;
- formal parameters, matched by position;
- return documentation for non-void methods;
- documentation for exceptions declared by the overriding method.

Tags such as `@see`, `@since`, `@apiNote`, `@implSpec`, `@implNote`, and
`@deprecated` are not automatically copied into the effective view.

Each effective item records:

```json
{
  "name": "candidate",
  "text": "value to test",
  "source": "inherited",
  "inheritance_mode": "implicit_missing_item",
  "inherited_from": "example.Contract",
  "inherited_method_uri": "src/main/java/example/Contract.java#example.Contract.accepts(java.lang.Object):boolean",
  "hierarchy_distance": 1,
  "resolution": "resolved"
}
```

`inheritance_mode` distinguishes documentation written locally, explicit
`{@inheritDoc}`, and inheritance caused by an omitted item. In particular,
`uses_inheritdoc=false` does not imply that no documentation was inherited.
The JDK 22+ explicit-supertype form, such as `{@inheritDoc SomeInterface}`, is
also honored. Because Spoon 11 recognizes that inline tag but normalizes away
its optional target, CoCoMUT preserves and parses the raw source spelling for
that form.

`effective_structured_tags.resolution=complete` means that no item is uncertain;
it does not mean that every possible item has text. A genuinely missing item can
have `resolution=missing` while the overall resolution remains complete.

## Structured ancestor evidence

`inherited_javadoc_candidates` is an ordered array of objects rather than raw
comment strings. Every entry identifies the declaration or unresolved ancestor
evidence and records:

- declaring type and whether it is a class or interface;
- superclass/superinterface relationship;
- method URI and signature when source identity is available;
- hierarchy distance and search order;
- raw and structured Javadoc when available;
- parser and confidence provenance;
- Javadoc availability and any diagnostic.

The array deliberately includes observable declarations with no Javadoc and
method-level evidence whose source is unavailable. This preserves the
difference between "inspected and absent" and "not available to inspect."
Use `inheritdoc_documented_candidate_count` when only documented ancestors are
needed.

## Availability and uncertainty

The candidate vocabulary distinguishes:

| Value | Meaning |
| --- | --- |
| `present` | Source Javadoc was obtained. |
| `absent` | The source declaration was inspected and has no Javadoc. |
| `source_unavailable` | The override is known from a shadow/executable reference, but source Javadoc is unavailable. |
| `parse_failed` | Source comment extraction failed without a usable fallback. |
| `partial_resolution` | The ancestor or override relation could not be inspected completely. |
| `not_analyzed` | The relevant source was outside an explicitly bounded analysis. |

The current Spoon backend emits `present`, `absent`, `source_unavailable`,
`partial_resolution`, or `not_analyzed`. The `parse_failed` value is reserved
for an extraction path that can prove that source was available but neither the
typed parser nor the raw-comment fallback produced usable documentation.

An unresolved declaration earlier in search order blocks a later declaration
from being asserted as the effective source. The later declaration remains in
the candidate array for audit, while the affected effective item is marked
`source=indeterminate`, `resolution=indeterminate`.

## Resolution fields and invariants

`inheritdoc_policy` is:

- `item_level` when an override relation is inspected or the focal comment uses
  `inheritDoc`;
- `not_applicable` otherwise.

`inheritdoc_resolution` summarizes candidate availability:

| Value | Meaning |
| --- | --- |
| `resolved_candidate` | At least one candidate has `javadoc_availability=present`. |
| `no_documentation` | All observed relevant declarations are source-backed and undocumented. |
| `indeterminate` | No documented candidate was found and some relevant evidence is unavailable or partial. |
| `unresolved` | Explicit `inheritDoc` was present but no overridden declaration could be identified. |
| `not_applicable` | No inheritance relation or explicit request was found. |

The resolver maintains this invariant:

```text
inheritdoc_resolution == resolved_candidate
  => inheritdoc_documented_candidate_count > 0
  => inherited_javadoc_candidates is not empty
```

Candidate availability and effective-item certainty are intentionally separate.
For example, a later interface can provide a documented candidate while an
earlier classpath-only superclass makes the effective item indeterminate.

## Source availability boundary

Javadoc comments are source artifacts; bytecode does not preserve them. The
standard doclet likewise requires the inherited method's source file on its
source path. CoCoMUT therefore never converts a shadow declaration's empty
comment into `absent`. It records `source_unavailable` instead.

When source is available but Spoon does not expose a typed comment, CoCoMUT
falls back to the raw source comment and labels the parser confidence. It only
reports `absent` after both views establish that the source declaration has no
Javadoc.

The normative behavior is based on the JDK 25
[Documentation Comment Specification for the Standard Doclet](https://docs.oracle.com/en/java/javase/25/docs/specs/javadoc/doc-comment-spec.html),
especially "Method Documentation" and "Automatic Supertype Search."
