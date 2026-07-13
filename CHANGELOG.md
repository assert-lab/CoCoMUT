# Changelog

## 0.1.0

- Minimized the repository into a reusable Java library and CLI.
- Added Spoon-backed source extraction and method URI identity.
- Added JSONL output, source-set labels, Javadoc metadata, documentation metrics, and provenance fields.
- Added SootUp call graph modes: `cha` and `rta`.
- Updated the bytecode parser used by SootUp so Java 25 and 26 project classes
  remain available to call-graph analysis.
- Added second-level build-failure reasons, bounded network retries, isolated
  Maven toolchain selection, opt-in deterministic Android SDK preparation, same-reactor
  Maven lifecycle fallback, nested build-root detection, generated-source
  discovery, and explicit class-file version diagnostics.
- Added typed Java API objects and a standalone Picocli CLI distribution.
- Added a versioned JSONL schema for method-context records. The schema is
  intentionally extensible through `additionalProperties` while the top-level
  record shape remains documented.
- Bumped the extraction-manifest schema to `0.4.0` for structured build actions
  and retained the previous `0.3.0` schema for archived manifests.
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
