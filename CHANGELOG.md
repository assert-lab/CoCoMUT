# Changelog

## 1.0-SNAPSHOT

- Minimized the repository into a reusable Java library and CLI.
- Added Spoon-backed source extraction and method URI identity.
- Added JSONL output, source-set labels, Javadoc metadata, documentation metrics, and provenance fields.
- Added optional SootUp call graph modes: `none`, `cha`, and `rta`.
- Added typed Java API objects and a standalone Picocli CLI distribution.
- Added machine-readable schema drafts and validation/schema CLI commands.
- Added public-repository field-test evidence for 100 Java repositories.
- Moved Java packages to `org.assertlab.context4docugen`.
- Added Maven wrapper, GitHub Actions CI, contributor docs, security policy, and citation metadata.
- Added `--max-source-files` for low-memory smoke runs.
- Added JSONL failure artifacts for selected-method and context-extraction misses.
- Tightened generated context JSON/JSONL validation.
