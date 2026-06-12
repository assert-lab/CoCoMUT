# Field Test Results

Field tests use repositories sampled from `../cleaned_mined_repos.csv`.

The reproducible runner is:

```bash
scripts/field_test_public_repos.py --limit 100 --timeout 600
```

It writes local evidence under ignored paths:

```text
target/field-tests/public-repos/repos.tsv
target/field-tests/public-repos/results.tsv
target/field-tests/public-repos/logs/
target/field-tests/public-repos/checkouts/
```

## Selection Policy

The 100-repository sweep selected Java repositories that are:

- English-description repositories;
- non-fork and non-archived according to the CSV;
- not tutorial, interview-prep, sample, demo, bootcamp, template, or "awesome-list" repositories;
- normal libraries, tools, frameworks, clients, servers, plugins, SDKs, parsers, or infrastructure projects;
- small-to-medium enough for shallow-clone field testing where possible.

Android-heavy and tutorial-style repositories are filtered out because they add SDK/setup noise and are less representative of reusable Java library/tool documentation.

## 100-Repository Source Sweep

Command shape:

```bash
./bin/c4dg extract \
  --project target/field-tests/public-repos/checkouts/<owner>__<repo> \
  --scope entry-points \
  --call-graph none \
  --output jsonl
```

`--call-graph none` was intentional. This sweep validates Spoon source extraction, source-set labels, JSONL output, failure taxonomy, and source-only fallback without requiring every public repository to compile.

Summary:

```text
repositories tested: 100
clean successes: 87
degraded successes: 11
failed repositories: 2
identified methods in successful/degraded runs: 506382
generated JSONL rows: 475792
overall method-to-context coverage: 93.96%
```

Status breakdown:

| Status | Count | Meaning |
| --- | ---: | --- |
| Clean success | 87 | C4DG completed and emitted one JSONL row per selected method. |
| Degraded success | 11 | C4DG completed, but some method contexts were dropped and `CONTEXT_EXTRACTION_FAILED` was recorded. |
| Failed | 2 | C4DG exited before a structured report due to JVM heap exhaustion. |

The two failed repositories were `kubernetes-client/java` and `besu-eth/besu`. Their logs ended with `Java heap space` from the Maven exec JVM.

## Attention Cases

| Repository | Methods | JSONL rows | Coverage | Note |
| --- | ---: | ---: | ---: | --- |
| `github/copilot-sdk` | 3085 | 1957 | 63.4% | partial context extraction |
| `apple/pkl` | 9972 | 5177 | 51.9% | partial context extraction |
| `junit-team/junit-framework` | 8531 | 4929 | 57.8% | partial context extraction |
| `mockito/mockito` | 8106 | 6036 | 74.5% | partial context extraction |
| `apache/maven` | 9375 | 9373 | 100.0% | 2 contexts dropped |
| `ben-manes/caffeine` | 6580 | 4258 | 64.7% | partial context extraction |
| `micrometer-metrics/micrometer` | 9629 | 5754 | 59.8% | partial context extraction |
| `apache/fory` | 16028 | 9638 | 60.1% | partial context extraction |
| `TNG/ArchUnit` | 10948 | 7559 | 69.0% | partial context extraction |
| `spockframework/spock` | 6200 | 3184 | 51.4% | partial context extraction |
| `apache/cassandra-java-driver` | 7296 | 7295 | 100.0% | 1 context dropped |
| `kubernetes-client/java` | - | - | - | heap exhaustion |
| `besu-eth/besu` | - | - | - | heap exhaustion |

The partial-context cases are the next important technical target. They mostly appear after Spoon falls back from whole-project parsing to smaller source-root or file-level models. Method discovery can succeed while context lookup later fails for a subset of generated method URIs.

## Call-Graph Field Test

Call-graph testing used three compiled Maven repositories from the same checkout area, with `--max-methods 100` to keep the bytecode test bounded:

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

This confirms that optional SootUp `CHA` and `RTA` modes still work when compiled class directories are available. It does not prove precision; it only validates availability and pipeline integration.

## Fixes Triggered By Field Testing

- Spoon parsing now degrades from whole-project parsing to per-root and per-file parsing, skipping only files Spoon still cannot parse.
- Phase 1 now uses discovered `ProjectModel` source roots for source-only fallback instead of only the legacy single `sourceRoot`.
- Incomplete shallow clones are not treated as tool failures; `mock-server/mockserver-monorepo` succeeded after deleting an interrupted checkout and cloning again.
- Javadoc release packaging now passes under Java doclint after fixing invalid public Javadoc HTML.

## Remaining Follow-Up

- Add per-method failure records to JSONL or a sidecar failures file so dropped contexts can be inspected without reading logs.
- Reduce memory pressure for very large repositories, especially when Spoon no-classpath parsing emits huge intermediate models.
- Investigate URI consistency between method discovery and context extraction during per-file fallback.
- Add a packaged distribution beyond the lightweight `bin/c4dg` Maven wrapper.
