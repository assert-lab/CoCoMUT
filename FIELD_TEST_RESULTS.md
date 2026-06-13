# Field Test Results

Field tests use repositories selected from `../cleaned_mined_repos.csv`.

The current expanded runner is:

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

It writes local evidence under ignored paths:

```text
target/field-tests/expanded-public-repos/repos.tsv
target/field-tests/expanded-public-repos/results.tsv
target/field-tests/expanded-public-repos/expanded-summary.md
target/field-tests/expanded-public-repos/logs/
target/field-tests/expanded-public-repos/checkouts/
```

## Selection Policy

The expanded sweep selected Java repositories that are:

- English-description repositories;
- non-fork and non-archived according to the CSV;
- not tutorial, interview-prep, sample, demo, bootcamp, template, or awesome-list repositories;
- normal libraries, tools, frameworks, clients, servers, plugins, SDKs, parsers, apps, or infrastructure projects;
- within the configured repository-size cap.

Android repositories are included. The test intentionally validates source-only extraction on mixed Java, Android, Gradle, Maven, and plain source layouts without requiring every repository to compile.

## Expanded Source Sweep

Command shape per repository:

```bash
./bin/c4dg extract \
  --project target/field-tests/expanded-public-repos/checkouts/<owner>__<repo> \
  --scope entry-points \
  --call-graph none \
  --output jsonl
```

If a repository times out or exhausts heap, the runner retries with bounded source modeling:

```bash
--max-source-files 1500
--max-source-files 1500 --max-methods 5000
```

`--call-graph none` is intentional for this sweep. The goal is to validate Spoon source extraction, source-set labels, JSONL output, failure taxonomy, and source-only fallback without requiring public repositories to compile.

Final post-fix summary:

```text
repositories tested: 541
clean successes: 541
degraded successes: 0
failed/timeouts/skipped: 0
capped retry runs: 10
identified methods in successful runs: 2686556
generated JSONL rows in successful runs: 2686556
method-to-context coverage: 100.00%
```

Status breakdown:

| Status | Count | Meaning |
| --- | ---: | --- |
| Clean success | 541 | C4DG completed and emitted one JSONL row per selected method. |
| Degraded success | 0 | No post-fix method/context row gaps remained. |
| Failed/timeout/skipped | 0 | No selected repository failed in the final run. |

## Bounded Retry Cases

Ten repositories needed capped extraction to stay resource-safe on this laptop:

| Retry mode | Count | Meaning |
| --- | ---: | --- |
| `max_source_files=1500` | 7 | Full coverage after limiting parsed source files. |
| `max_source_files=1500;max_methods=5000` | 3 | Full coverage for a bounded 5,000-method smoke run. |

Important bounded examples:

- `lakesoul-io/LakeSoul`: previously timed out; now succeeds with `max_source_files=1500;max_methods=5000`.
- `kubernetes-client/java`: succeeds with `max_source_files=1500;max_methods=5000`.
- `apache/incubator-seata`: succeeds with `max_source_files=1500;max_methods=5000`.
- `besu-eth/besu`: succeeds with `max_source_files=1500`.
- `LWJGL/lwjgl3`: succeeds with `max_source_files=1500`.
- `spring-projects/spring-boot`: succeeds with `max_source_files=1500`.
- `ben-manes/caffeine`: succeeds with `max_source_files=1500` after adding defensive Spoon `StackOverflowError` guards.

## Fixes Triggered By Field Testing

- Deduplicated Spoon-discovered methods by method URI. Overlapping source roots and fallback parsing previously inflated `methods.csv` while JSONL correctly emitted one row per unique URI.
- JSONL generation now records per-method generation results so `methods.csv` enrichment marks JSONL rows as `SUCCESS` instead of only recording a global `__jsonl__` output.
- Optional Spoon context extraction now guards against `StackOverflowError` in no-classpath generic type resolution. Complex generic repositories such as Caffeine should lose optional context rather than aborting extraction.
- Large class/sibling context extraction is capped to avoid runaway memory use on very large classes.
- Source-only bounded controls are now validated on real repositories with `--max-source-files` and `--max-methods`.

The pre-fix 541-repository result is preserved locally as:

```text
target/field-tests/expanded-public-repos/results.before-dedup-jsonl-fix.tsv
target/field-tests/expanded-public-repos/expanded-summary.before-dedup-jsonl-fix.md
```

That earlier run had 43 degraded successes and 1 timeout. The final run confirms those were fixable tool issues rather than unavoidable repository limitations.

## Call-Graph Field Test

Call-graph testing used three compiled Maven repositories from the checkout area, with `--max-methods 100` to keep the bytecode test bounded:

```bash
mvn -q -DskipTests -Dmaven.test.skip=true -f <repo>/pom.xml compile
./bin/c4dg extract --project <repo> --scope entry-points --call-graph cha --max-methods 100
./bin/c4dg extract --project <repo> --scope entry-points --call-graph rta --max-methods 100
```

Results:

| Repository | Algorithm | Compile exit | Methods | Call graphs | Contexts | JSON files | Status |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `git-commit-id/git-commit-id-maven-plugin` | CHA | 0 | 72 | 72 | 72 | 72 | SUCCESS |
| `git-commit-id/git-commit-id-maven-plugin` | RTA | 0 | 72 | 72 | 72 | 72 | SUCCESS |
| `jhy/jsoup` | CHA | 0 | 100 | 94 | 100 | 100 | SUCCESS |
| `jhy/jsoup` | RTA | 0 | 100 | 94 | 100 | 100 | SUCCESS |
| `googleapis/google-http-java-client` | CHA | 0 | 100 | 82 | 100 | 100 | SUCCESS |
| `googleapis/google-http-java-client` | RTA | 0 | 100 | 82 | 100 | 100 | SUCCESS |

This confirms that optional SootUp `CHA` and `RTA` modes still work when compiled class directories are available. It does not prove call-graph precision; it validates availability and pipeline integration.

## Remaining Follow-Up

- Add a faster preflight size model so very large repositories can start directly in bounded mode.
- Preserve explicit duplicate-method discovery statistics in reports, even though duplicate URIs are now deduplicated before output.
- Continue adding small regression fixtures for generics, overlapping roots, JSONL enrichment, `@see`, and `{@inheritDoc}` cases.
