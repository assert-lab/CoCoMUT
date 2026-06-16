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

## Applied Product Decisions

- Removed the unused `SPARK` call-graph enum value.
- Removed the older hand-written `Context4DocuGenCli`; Picocli `c4dg` is the
  only public CLI entry point.
- Changed the Picocli default to `--call-graph auto`.
- Made extraction JSONL-only, including package/class/method-filtered runs.
- Kept the terminal key-value report and also write `extraction_report.json`.
- Kept Javadoc and method source separate: `MUT.javadoc` contains documentation,
  while `MUT.code` contains annotations plus method declaration/body without the
  leading Javadoc block.
