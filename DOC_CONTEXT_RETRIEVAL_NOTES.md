# Documentation Context Retrieval Notes

This note records the documentation-context gaps inspected during the URI and
call-edge cleanup.

## Scan Basis

The inspection targeted:

- Oracle's Javadoc guidance on doc comments, especially `@see`, inline links,
  inherited comments, and referenced auxiliary files.
- The expanded public-repository checkouts under
  `experiments/expanded-public-repos-auto-main/checkouts/`.

Approximate `rg` counts over Java files in those checkouts, excluding common
build output directories:

```text
Java files                         572195
@see                               96959
inline {@link}/{@linkplain}        666333
{@inheritDoc}/@inheritDoc          19550
@throws/@exception                 137481
@since                             91020
@deprecated                        16773
@apiNote/@implSpec/@implNote       2091
doc-files/images/html references   39268
{@value}/{@literal}/{@code}/{@snippet} 513561
```

## Implemented

- Canonical method URI now includes erased return type:
  `relative/path/File.java#qualified.Class.method(erasedParamTypes):erasedReturnType`.
- `MUT.return_type` and `MUT.erased_return_type` are emitted explicitly.
- Call graph arrays now contain normalized edge objects instead of mixed raw
  strings and method objects. Resolved project edges use `method_uri`; SootUp
  bytecode signatures are retained only as provenance in `raw_signature`.
- Javadoc metadata now includes `javadoc_references` for `@see`,
  `{@link ...}`, and `{@linkplain ...}`:
  - external URLs;
  - project type references with `type_uri`, source path, class Javadoc excerpt,
    and hierarchy metadata;
  - project method references resolved to CoCoX method URIs when possible;
  - project field references resolved to `field_uri`, type, modifiers, and
    Javadoc excerpt;
  - superclass/interface method and field references when the target is a
    project-local inherited member;
  - precise overload ambiguity reports when omitted or explicit parameters do
    not identify exactly one method;
  - symbol-only external class/member references when the source/Javadoc is not
    part of the parsed project;
  - short referenced Javadoc excerpts.
- Javadoc metadata now includes `structured_tags` for:
  - `@param`;
  - `@return`;
  - `@throws` / `@exception`;
  - `@since`;
  - `@deprecated`;
  - `@apiNote`;
  - `@implSpec`;
  - `@implNote`.
- Javadoc metadata now includes bounded `file_references` for common auxiliary
  documentation paths such as `doc-files/...`, images, HTML, text files, and
  sample Java files.

## Commons Lang Check

Fresh no-call-graph extraction against the Apache Commons Lang checkout:

```text
rows                  4491
resolved_refs          442
external_refs           61
structured_tag_rows   3116
file_ref_rows           37
```

## Still Missing

- Deep semantic resolution for external JDK/library references such as
  `java.util.List#add(Object)`. CoCoX now records these as symbol-only external
  references; it does not yet load source or Javadoc jars for excerpts.
- Full Javadoc doclet rendering semantics. CoCoX parses useful source-level
  context; it does not attempt to exactly reproduce generated Javadoc HTML.
- Rich resolution for all label variants in handwritten `@see` text. The
  resolver handles common `target label` patterns, but unusual prose-heavy
  references remain best-effort.
- Full auxiliary-file ingestion. CoCoX records referenced file paths and small
  text excerpts, but it does not embed binary images or large HTML files.
- Full `{@value}` constant resolution and rendered `{@code}`/`{@literal}`
  text normalization. These are counted as common but currently remain raw
  Javadoc text plus structured metadata.
