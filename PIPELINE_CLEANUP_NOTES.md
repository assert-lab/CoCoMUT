# Pipeline Cleanup Notes

This note tracks cleanup decisions made while moving C4DG toward a JSONL-first
product pipeline.

## Applied In This Pass

- Generated artifacts now default to `./c4dg_output/<project-name>/` instead of
  the analyzed repository.
- `--output-dir` lets callers choose an explicit output destination.
- Repository-wide extraction writes `method_contexts.jsonl`.
- Package/class/method-filtered extraction writes a distinguishable JSONL file
  name derived from the selected filter.
- Human-readable call graph dumps now use the effective algorithm name:
  `Output_CallGraph_CHA.txt` or `Output_CallGraph_RTA.txt`.
- `methods.csv` generation and `CsvEnricher` were removed from the default
  pipeline.
- Legacy method ID strategies were removed. Method identity is URI-based.
- Method URI identity now uses Spoon's erased/semantic executable signature so
  generic overloads such as `<T>` vs `<T extends Throwable>` do not collapse.
- The JSON parameter objects now include both source type and `erased_type`.
- Added package, class, method, visibility, include-path, and exclude-path
  filters.

## Remaining Product Questions

- `CallGraphGenerator.Algorithm.SPARK` exists in the enum but is not wired to a
  real implementation. It should either be implemented or removed before a
  stable release.
- The older `Context4DocuGenCli` class overlaps with the Picocli CLI module.
  Keep one public CLI entry point and document the other, if retained, as a
  compatibility entry point.
- The default `--call-graph` is still `cha` in the Picocli CLI. For large public
  repository mining, `none` or `auto` may be a safer default. This is a product
  policy decision.
- Per-method JSON files remain useful for manual inspection but should stay
  secondary to JSONL for dataset-scale extraction.
- The extraction report is currently printed as key-value lines. A structured
  `extraction_report.json` in the output directory would be useful for
  repeatable field studies.
- The source-position slice still includes leading Javadoc in `MUT.code` for
  some Spoon positions. For doc-generation datasets, consider splitting
  `code_with_javadoc` from `code_without_javadoc` to avoid target leakage.
