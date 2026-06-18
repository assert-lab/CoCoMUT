# Changelog

## 1.0-SNAPSHOT

- Minimized the repository into a reusable Java library and CLI.
- Added Spoon-backed source extraction and method URI identity.
- Added JSONL output, source-set labels, Javadoc metadata, documentation metrics, and provenance fields.
- Added optional SootUp call graph modes: `none`, `cha`, and `rta`.
- Added typed Java API objects and a standalone Picocli CLI distribution.
- Added machine-readable schema drafts and validation/schema CLI commands.
- Added public-repository field-test evidence for 541 filtered Java repositories.
- Moved Java packages to `org.assertlab.cocox`.
- Added Maven wrapper, GitHub Actions CI, contributor docs, and citation metadata.
- Added `--max-source-files` for low-memory smoke runs.
- Added `--max-methods` and bounded retry guidance for very large repositories.
- Deduplicated Spoon-discovered methods by stable method URI.
- Hardened optional Spoon context extraction against recursive generic type-resolution failures.
- Added JSONL failure artifacts for method-context extraction misses.
- Removed `methods.csv` generation from the product extraction path.
- Tightened generated context JSONL validation.
