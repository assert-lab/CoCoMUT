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

That matters because `mvn clean` deletes `target/`. The current 541-repository
auto sweep is preserved under `experiments/expanded-public-repos-auto-main/`.
The directory is ignored by Git because it contains large checkouts, logs, and
generated JSONL files.

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
  --retry-smoke-source-files 100 \
  --retry-smoke-methods 250 \
  --java-home /usr/lib/jvm/java-17-openjdk \
  --output-dir experiments/expanded-public-repos-auto-main
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
successes: 541
clone-timeout skips: 0
analysis timeouts: 0
identified methods in successful runs: 1913981
generated JSONL rows in successful runs: 1913981
method-to-context coverage in successful runs: 100.00%
compile-success repositories: 169
call-graph-available repositories: 231
capped retry runs: 106
```

Status breakdown:

| Status | Count | Meaning |
| --- | ---: | --- |
| Success | 541 | C4DG completed and emitted one JSONL row per selected method. |
| Skipped | 0 | Git clone timed out before C4DG analysis started. |
| Timeout | 0 | C4DG analysis exceeded the configured per-repository timeout. |

Non-success repositories:

| Repository | Status | Note |
| --- | --- | --- |
| - | - | - |

## Auto Behavior

The sweep did not require repositories to compile. Compilation is opportunistic:

| Signal | Count |
| --- | ---: |
| Compile succeeded | 169 repositories |
| Compile failed or was unavailable | 372 repositories |
| Call graph available | 231 repositories |
| Call graph unavailable | 310 repositories |

Source backend mode distribution over generated method contexts:

| Source backend mode | Method contexts |
| --- | ---: |
| `noclasspath_fallback` | 1186464 |
| `noclasspath_limited` | 595000 |
| `noclasspath` | 90063 |
| `classpath` | 42454 |

Interpretation:

- `classpath` means Spoon classpath-aware extraction was retained.
- `noclasspath_fallback` means auto mode tried or considered stronger resolution but used no-classpath as the coverage-preserving baseline.
- `noclasspath_limited` means bounded source-file or method caps were active.
- `noclasspath` means normal source-only extraction.

## Bounded Retry Cases

Retry distribution:

| Retry mode | Count | Meaning |
| --- | ---: | --- |
| `none` | 435 | Completed without field-test caps. |
| `max_source_files=1500` | 85 | Completed after limiting parsed source files. |
| `max_source_files=1500;max_methods=5000;source_set=main,unknown` | 17 | Completed as a bounded 5,000-method run after allowing unknown source roots for nonstandard layouts. |
| `max_source_files=1500;smoke_resolution=noclasspath;smoke_call_graph=none;smoke_max_source_files=100;smoke_max_methods=250` | 4 | Completed as last-resort source-only smoke runs for very large/slow repositories. |

Large successful examples include:

- `turms-im/turms`: 35724 method contexts.
- `infinispan/infinispan`: 35412 method contexts.
- `orientechnologies/orientdb`: 26231 method contexts.
- `microsoft/typespec`: 25736 method contexts.
- `TGX-Android/Telegram-X`: 22778 method contexts, capped source parsing.
- `apache/paimon`: 20799 method contexts.
- `theonedev/onedev`: 20515 method contexts after stale partial checkout cleanup.
- `netty/netty`: 20086 method contexts.
- `apache/pdfbox`: 7874 method contexts.
- `apache/commons-lang`: 3764 method contexts, clean auto result.

## Javadoc Tag Cases

The field-test runner counts Javadoc tag patterns from generated JSONL. The post-processing script:

```bash
python scripts/extract_javadoc_tag_cases.py \
  --output-dir experiments/expanded-public-repos-auto-main
```

produced:

```text
javadoc_tag_cases.csv rows: 43006
methods with @see tags: 28903
methods with {@inheritDoc}: 14317
methods with inherited-doc candidates: 4193
```

`{@inheritDoc}` resolution in the extracted case CSV:

| Resolution | Count |
| --- | ---: |
| `resolved_candidate` | 7375 |
| `unresolved` | 6942 |

Repositories with many `@see` methods:

| Repository | `@see` methods | `{@inheritDoc}` methods | Candidates |
| --- | ---: | ---: | ---: |
| `jfree/jfreechart` | 2404 | 0 | 0 |
| `ReactiveX/RxJava` | 1137 | 8 | 0 |
| `apache/groovy` | 991 | 1754 | 68 |
| `redis/lettuce` | 973 | 0 | 0 |
| `spring-projects/spring-framework` | 851 | 19 | 0 |
| `discord-jda/JDA` | 847 | 7 | 7 |
| `aeron-io/aeron` | 843 | 505 | 0 |
| `docker-java/docker-java` | 813 | 3 | 3 |
| `helidon-io/helidon` | 808 | 14 | 14 |
| `Jaspersoft/jasperreports` | 804 | 0 | 0 |

Repositories with many `{@inheritDoc}` methods:

| Repository | `{@inheritDoc}` methods | Candidates |
| --- | ---: | ---: |
| `microsoft/typespec` | 2406 | 92 |
| `apache/groovy` | 1754 | 68 |
| `ff4j/ff4j` | 1142 | 937 |
| `apache/jmeter` | 794 | 0 |
| `aeron-io/agrona` | 726 | 293 |
| `jtablesaw/tablesaw` | 626 | 515 |
| `Netflix/metacat` | 525 | 0 |
| `aeron-io/aeron` | 505 | 0 |
| `openrewrite/rewrite` | 450 | 0 |
| `Netflix/genie` | 403 | 275 |

## Source Set Attention

The completed auto sweep used `--source-set main` by default. That excludes
test, example, generated, and integration-test source roots for normal layouts.
Some repositories use nonstandard layouts where valid production code is
classified as `unknown`; the field-test runner retries those cases with
`main,unknown` and records that retry mode explicitly.

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
- `kubernetes-client/java`, `lakesoul-io/LakeSoul`, `apache/incubator-seata`, `eclipse-milo/milo`: last-resort source-only smoke cases.

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

- Add a faster preflight size model so very large repositories can start directly in bounded mode.
- Add per-method `source_set` to JSONL metadata so source-set distributions can be audited without re-running extraction.
- Investigate whether the four smoke-fallback repositories can be handled with a better preflight size model.
- Inspect `javadoc_tag_cases.csv` for `@see` target quality and `{@inheritDoc}` candidate correctness.
- Continue adding small regression fixtures for `@see`, `{@inheritDoc}`, source-set filtering, classpath-aware fallback, and capped extraction.
