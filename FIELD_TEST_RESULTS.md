# Field Test Results

Field tests use repositories selected from `../cleaned_mined_repos.csv`.

Use the reproducible wrapper for new runs:

```bash
scripts/run_expanded_auto_field_study.sh
```

By default it writes preserved local artifacts outside Maven `target/`:

```text
experiments/expanded-public-repos-auto-main/
```

That matters because `mvn clean` deletes `target/`. The earlier 541-repository
auto sweep wrote under `target/field-tests/expanded-public-repos-auto`, so its
large local CSV/checkouts/logs were disposable. The numeric summary below is
preserved in this tracked report; rerun the wrapper to regenerate full CSVs.

The completed auto-resolution and auto-call-graph sweep used this command shape:

```bash
scripts/field_test_public_repos.py \
  --limit 0 \
  --timeout 420 \
  --include-android \
  --max-size-kb 300000 \
  --resolution auto \
  --call-graph auto \
  --compile-timeout 60 \
  --retry-max-source-files 1500 \
  --retry-max-methods 5000 \
  --java-home /usr/lib/jvm/java-17-openjdk \
  --output-dir target/field-tests/expanded-public-repos-auto
```

This run attempts Maven/Gradle compilation when useful, uses Spoon classpath-aware extraction only when classpath evidence is usable, falls back to Spoon no-classpath extraction when build/classpath resolution is incomplete, and asks SootUp for RTA call graphs when compiled class directories exist.

The new wrapper uses the same workflow plus `--source-set main` by default and writes local evidence under:

```text
experiments/expanded-public-repos-auto-main/repos.tsv
experiments/expanded-public-repos-auto-main/results.tsv
experiments/expanded-public-repos-auto-main/field_test_results_auto.csv
experiments/expanded-public-repos-auto-main/summary.md
experiments/expanded-public-repos-auto-main/summary_counts.txt
experiments/expanded-public-repos-auto-main/javadoc_tag_cases.csv
experiments/expanded-public-repos-auto-main/javadoc_tag_cases_sample.csv
experiments/expanded-public-repos-auto-main/run_manifest.txt
experiments/expanded-public-repos-auto-main/logs/
experiments/expanded-public-repos-auto-main/checkouts/
```

## Selection Policy

The expanded sweep selected Java repositories that are:

- English-description repositories;
- non-fork and non-archived according to the CSV;
- not tutorial, interview-prep, sample, demo, bootcamp, template, or awesome-list repositories;
- normal libraries, tools, frameworks, clients, servers, plugins, SDKs, parsers, apps, or infrastructure projects;
- within the configured repository-size cap.

Android repositories are included. The test intentionally validates source-only extraction on mixed Java, Android, Gradle, Maven, and plain source layouts without requiring every repository to compile.

## Auto Sweep Summary

Final summary:

```text
repositories selected: 541
successes: 534
clone-timeout skips: 5
analysis timeouts: 2
identified methods in successful runs: 2373883
generated JSONL rows in successful runs: 2373883
method-to-context coverage in successful runs: 100.00%
compile-success repositories: 137
call-graph-available repositories: 209
capped retry runs: 69
```

Status breakdown:

| Status | Count | Meaning |
| --- | ---: | --- |
| Success | 534 | C4DG completed and emitted one JSONL row per selected method. |
| Skipped | 5 | Git clone timed out before C4DG analysis started. |
| Timeout | 2 | C4DG analysis exceeded the configured per-repository timeout. |

Non-success repositories:

| Repository | Status | Note |
| --- | --- | --- |
| `kubernetes-client/java` | `TIMEOUT` | analysis timeout |
| `eclipse-milo/milo` | `TIMEOUT` | analysis timeout |
| `tlaplus/tlaplus` | `SKIPPED` | clone timeout |
| `airbnb/epoxy` | `SKIPPED` | clone timeout |
| `javamelody/javamelody` | `SKIPPED` | clone timeout |
| `apache/zeppelin` | `SKIPPED` | clone timeout |
| `tronprotocol/java-tron` | `SKIPPED` | clone timeout |

## Auto Behavior

The sweep did not require repositories to compile. Compilation is opportunistic:

| Signal | Count |
| --- | ---: |
| Compile succeeded | 137 repositories |
| Compile failed or was unavailable | 397 repositories |
| Call graph available | 209 repositories |
| Call graph unavailable | 325 repositories |

Source backend mode distribution over generated method contexts:

| Source backend mode | Method contexts |
| --- | ---: |
| `noclasspath_fallback` | 1526361 |
| `noclasspath_limited` | 627414 |
| `noclasspath` | 174046 |
| `classpath` | 46062 |

Interpretation:

- `classpath` means Spoon classpath-aware extraction was retained.
- `noclasspath_fallback` means auto mode tried or considered stronger resolution but used no-classpath as the coverage-preserving baseline.
- `noclasspath_limited` means bounded source-file or method caps were active.
- `noclasspath` means normal source-only extraction.

## Bounded Retry Cases

Retry distribution:

| Retry mode | Count | Meaning |
| --- | ---: | --- |
| `none` | 467 | Completed without field-test caps. |
| `max_source_files=1500` | 65 | Completed after limiting parsed source files. |
| `max_source_files=1500;max_methods=5000` | 4 | Completed as a bounded 5,000-method run. |

Large successful examples include:

- `turms-im/turms`: 35724 method contexts.
- `infinispan/infinispan`: 35412 method contexts.
- `google/j2cl`: 30089 method contexts.
- `AOL-archive/cyclops`: 28404 method contexts.
- `apache/tomcat`: 27963 method contexts.
- `microsoft/typespec`: 26597 method contexts.
- `aeron-io/aeron`: 19724 method contexts, clean auto result after capped source parsing.
- `apache/pdfbox`: 7874 method contexts, clean auto result.
- `apache/commons-lang`: 3764 method contexts, clean auto result.

## Javadoc Tag Cases

The field-test runner counts Javadoc tag patterns from generated JSONL. The post-processing script:

```bash
python scripts/extract_javadoc_tag_cases.py \
  --output-dir target/field-tests/expanded-public-repos-auto
```

produced:

```text
javadoc_tag_cases.csv rows: 45468
methods with @see tags: 30826
methods with {@inheritDoc}: 14856
methods with inherited-doc candidates: 5386
```

`{@inheritDoc}` resolution in the extracted case CSV:

| Resolution | Count |
| --- | ---: |
| `resolved_candidate` | 8637 |
| `unresolved` | 6219 |

Repositories with many `@see` methods:

| Repository | `@see` methods | `{@inheritDoc}` methods | Candidates |
| --- | ---: | ---: | ---: |
| `jfree/jfreechart` | 2404 | 0 | 0 |
| `ReactiveX/RxJava` | 1137 | 8 | 0 |
| `apache/groovy` | 991 | 1754 | 68 |
| `redis/lettuce` | 973 | 0 | 0 |
| `spring-projects/spring-framework` | 851 | 19 | 0 |
| `discord-jda/JDA` | 846 | 7 | 7 |
| `aeron-io/aeron` | 843 | 505 | 0 |
| `helidon-io/helidon` | 816 | 14 | 14 |
| `docker-java/docker-java` | 813 | 3 | 3 |
| `apache/commons-lang` | 440 | 142 | 92 |

Repositories with many `{@inheritDoc}` methods:

| Repository | `{@inheritDoc}` methods | Candidates |
| --- | ---: | ---: |
| `microsoft/typespec` | 2407 | 92 |
| `apache/groovy` | 1754 | 68 |
| `ff4j/ff4j` | 1142 | 937 |
| `apache/jmeter` | 795 | 469 |
| `aeron-io/agrona` | 726 | 293 |
| `jtablesaw/tablesaw` | 626 | 515 |
| `Netflix/metacat` | 525 | 374 |
| `aeron-io/aeron` | 505 | 0 |
| `openrewrite/rewrite` | 450 | 0 |
| `apache/pdfbox` | 191 | 112 |

## Source Set Attention

The completed auto sweep was run before source-set filtering was added, so it
kept public entry points across discovered source roots. C4DG now supports
`--source-set main` for dataset runs that should exclude tests, examples,
generated code, integration tests, and unknown source roots.

Whole-corpus source-set distribution:

| Source set | Method contexts |
| --- | ---: |
| `main` | 1887224 |
| `unknown` | 200905 |
| `test` | 151131 |
| `generated` | 104067 |
| `example` | 30114 |
| `integration_test` | 442 |

For documentation-dataset construction, rerun extraction with:

```bash
--scope entry-points --source-set main
```

unless the study intentionally includes tests, examples, or generated sources as separate strata.

## What To Inspect Next

High-value manual inspection targets:

- `apache/pdfbox`, `apache/commons-lang`, `apache/tomcat`, `apache/groovy`: mature Apache documentation conventions.
- `jfree/jfreechart`, `ReactiveX/RxJava`, `spring-projects/spring-framework`: dense `@see` usage.
- `ff4j/ff4j`, `apache/jmeter`, `jtablesaw/tablesaw`: many resolvable `{@inheritDoc}` candidates.
- `aeron-io/aeron`, `aeron-io/agrona`, `classgraph/classgraph`: clean auto-mode library/tool cases.
- `kubernetes-client/java`, `eclipse-milo/milo`: remaining analysis-timeout cases.

## Historical Source-Only Sweep

The previous source-only JSONL sweep used:

```bash
scripts/field_test_public_repos.py \
  --limit 0 \
  --timeout 420 \
  --include-android \
  --max-size-kb 300000 \
  --retry-max-source-files 1500 \
  --retry-max-methods 5000 \
  --output-dir target/field-tests/expanded-public-repos
```

It completed 541/541 repositories in source-only mode with 2686556 generated JSONL rows. The current auto sweep is stricter and more informative because it also records opportunistic build/classpath and call-graph behavior.

## Remaining Follow-Up

- Rerun the expanded auto sweep with `--source-set main` when a main-code-only dataset baseline is needed.
- Add a faster preflight size model so very large repositories can start directly in bounded mode.
- Investigate the two analysis timeouts: `kubernetes-client/java` and `eclipse-milo/milo`.
- Inspect `javadoc_tag_cases.csv` for `@see` target quality and `{@inheritDoc}` candidate correctness.
- Continue adding small regression fixtures for `@see`, `{@inheritDoc}`, source-set filtering, classpath-aware fallback, and capped extraction.
