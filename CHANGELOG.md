# Changelog

## 0.1.0

- Minimized the repository into a reusable Java library and CLI.
- Added Spoon-backed source extraction and method URI identity.
- Added JSONL output, source-set labels, Javadoc metadata, documentation metrics, and provenance fields.
- Added optional SootUp call graph modes: `none`, `cha`, and `rta`.
- Added typed Java API objects and a standalone Picocli CLI distribution.
- Added a versioned JSONL schema for method-context records. The schema is
  intentionally extensible through `additionalProperties` while the top-level
  record shape remains documented.
- Added public-repository field-test evidence for 541 filtered Java repositories.
- Moved Java packages to `org.assertlab.cocomut`.
- Added Maven wrapper, GitHub Actions CI, contributor docs, and citation metadata.
- Added `--max-source-files` for low-memory smoke runs.
- Added `--max-methods` and bounded retry guidance for very large repositories.
- Deduplicated Spoon-discovered methods by stable method URI.
- Hardened optional Spoon context extraction against recursive generic type-resolution failures.
- Added JSONL failure artifacts for method-context extraction misses.
- Removed `methods.csv` generation from the product extraction path.
- Removed validation and schema subcommands; `cocomut` now runs extraction directly.
