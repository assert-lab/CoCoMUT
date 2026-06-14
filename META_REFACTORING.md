# Meta Refactoring Summary

This branch turns Context4DocuGen from a research-package snapshot into a smaller static-analysis library/CLI.

## Applied Changes

- Removed bundled research datasets, generated docstring outputs, duplicated reports, and shell-first automation that made the repository large and hard to reuse.
- Kept the reusable core: Maven modules, static project adapters, method identification, selected-method loading, SootUp CHA call graph extraction, context extraction, JSON generation, and tests.
- Changed the Java baseline from 25 to 17 so the tool is easier to build in normal Java research/tooling environments.
- Removed dynamic/decompiler-oriented behavior. The tool now works from source and compiled project artifacts; it does not execute analyzed code.
- Replaced arbitrary method row IDs with method URIs:

```text
relative/path/ToFile.java#qualified.DeclaringClass.method(signature)
```

- Added structured JSON provenance so downstream users can see how a method was matched, how Javadoc was extracted, which call-graph algorithm was used, and whether the project compiled.
- Replaced the old custom source parser with a Spoon-backed source model behind an internal API boundary.
- Added source-level annotations, thrown exceptions, field reads/writes, overload groups, dynamic-feature hints, and documentation metrics to JSON output.
- Added configurable call graph modes: `none`, `cha`, and `rta`.
- Added API-level `AnalysisOptions`, JSONL output, source-set labels, Javadoc metadata, failure codes, and lightweight `bin/c4dg` wrappers.
- Added release packaging for normal, sources, and Javadocs jars.
- Added typed API objects: `ContextExtractorService`, `ContextRequest`, `MethodSelection`, and `ExtractionReport`.
- Added a dedicated `context4docugen-cli` module with Picocli commands: `extract`, `validate`, and `schema`.
- Added a standalone shaded CLI jar and `scripts/build_release_jar.sh`.
- Added machine-readable schema drafts under `schemas/`.
- Added a reproducible public-repository field-test runner under `scripts/`.
- Added a toy API project under `examples/api-toy-project`.
- Moved the public Java namespace to `org.assertlab.context4docugen`.
- Added Maven wrapper, GitHub Actions CI, changelog, citation, contributing, and security docs.
- Added low-memory smoke-run control with `--max-source-files`.
- Added per-method JSONL failure artifacts for selected-method misses and context-extraction misses.
- Tightened generated JSON/JSONL validation around the documented C4DG record contract.
- Rewrote `README.md` around product usage: build, CLI, selected CSV, API, method URI identity, and static-analysis boundaries.
- Added `known_issues.md` for limitations that should not block the minimal product branch.

## Commit Groups

- `4a2006b4 refactor: minimize product repository`
  - Removes research garbage and deprecated code paths.
  - Simplifies Maven dependencies and ignores generated outputs.

- `7e91f90e refactor: use method URIs for method identity`
  - Adds URI-oriented method identity and selected-method loading.
  - Updates JSON output and tests.

The current uncommitted group adds CLI/API product documentation and the toy API example.

- `56b6d067 feat: add minimal CLI and API guide`
  - Adds the Maven-exec CLI entry point.
  - Rewrites `README.md` for product usage.
  - Adds the toy API project, known issues file, and this summary.

The next group adds source-only fallbacks discovered during external field testing.

- `791a5d2a fix: continue with source-only context when bytecode is unavailable`
  - Keeps source extraction successful when bytecode/call graph is missing.
  - Records source-only provenance.

- `043e0f22 feat: use Spoon source model for context extraction`
  - Adds the internal source backend API and Spoon implementation.
  - Removes the regex method parser.
  - Updates JSON output to schema `0.3.0`.
  - Adds CLI controls for scope, call graph mode, and max methods.

- `1998dbef fix: make project compilation opt-in`
  - Prevents repository-mining runs from building external projects unless `--compile` is set.
  - Keeps class-file reuse for call graph extraction when compiled output already exists.

The field-test follow-up hardens Spoon parsing and records the 20-repository source-only sweep.

- `d98bb4fe feat: add API options JSONL and release wrappers`
  - Adds `AnalysisOptions`, output mode controls, JSONL output, source-set labels, Javadoc metadata, failure codes, and `bin/c4dg` wrappers.

- `b15e9699 docs: document JSONL API and release wrapper`
  - Documents the API options, JSONL output, schema additions, and wrapper usage.

- `21d834b4 fix: degrade Spoon parsing to file-level fallback`
  - Falls back from whole-project Spoon parsing to source-root and file-level parsing.
  - Avoids dropping an entire repository because of a small number of unparseable source files.

- `0eeb09ed test: add public repository field test runner`
  - Adds a resume-safe runner for filtered English, non-tutorial public Java repository sweeps.

- `2ff55537 fix: use discovered source roots for source-only fallback`
  - Uses `ProjectModel` source-root discovery when deciding whether source-only mode can continue.

- `9bfb5f9b fix: make public Javadocs pass release packaging`
  - Fixes public Javadoc HTML so `mvn -DskipTests package` can attach Javadocs.

- `464f7eee feat: add typed extraction API`
  - Adds service/request/selection/report API objects.
  - Updates the toy API project to use the typed service API.

- `b9b23b36 feat: add standalone Picocli distribution`
  - Adds the `context4docugen-cli` module.
  - Adds `extract`, `validate`, and `schema` commands.
  - Builds `context4docugen-cli-1.0-SNAPSHOT-all.jar` and a release copy under `dist/`.

- `4825a6a2 refactor: move Java API to assertlab namespace`
  - Renames production and test packages from `analyzer` to `org.assertlab.context4docugen`.

- `5956a80c chore: add release scaffold and CI`
  - Adds Maven wrapper, GitHub Actions CI, Maven project metadata, changelog, citation, contributing, and security docs.

The current group adds scoped source-model caching, low-memory source-file limits, stricter validation, selected/context failure artifacts, and focused regression tests.

- `5dd63b7 fix: cap source context for large field tests`
  - Caps class/sibling source context to avoid runaway memory use.
  - Adds a resume-safe summarizer for field-test TSV output.

The current group hardens the expanded field-test baseline and adds auto-mode
build/classpath behavior.

- Deduplicates Spoon-discovered methods by URI before writing `methods.csv`.
- Records per-method JSONL generation results so CSV enrichment marks JSONL rows as `SUCCESS`.
- Guards optional Spoon context extraction against no-classpath generic `StackOverflowError` cases.
- Adds regression tests for overlapping source roots and JSONL per-method result tracking.
- Adds `--resolution auto` and `--call-graph auto`.
- Keeps Spoon no-classpath extraction as the coverage baseline, retaining classpath-aware extraction only when it preserves enough discovered methods.
- Uses bounded Maven/Gradle compilation attempts when auto resolution or auto call graph needs build evidence.
- Records `@see` and `{@inheritDoc}` field-test counts and exports `javadoc_tag_cases.csv`.

## Verification

Current local verification:

```bash
./mvnw -Dmaven.repo.local=.m2/repository test
./mvnw -Dmaven.repo.local=.m2/repository -DskipTests package
scripts/build_release_jar.sh
./bin/c4dg --help
./bin/c4dg validate --project analyzer-tests/src/test/resources/fixtures/minimal-maven-project
mvn -Dmaven.repo.local=/home/ale/repos/repo_mining_trials/Context4DocuGen/.m2/repository \
  compile exec:java -Dexec.mainClass=example.RunContext4DocuGen
```

The toy API command is run from `examples/api-toy-project`.

External smoke tests are recorded in `FIELD_TEST_RESULTS.md`.

Latest external baseline:

- 541 filtered public Java repositories in auto-resolution and auto-call-graph JSONL mode;
- 534 repositories completed successfully, 5 clone-timeout skips, and 2 analysis timeouts;
- 2373883 methods identified and 2373883 JSONL rows generated in successful runs;
- 137 repositories compiled successfully during opportunistic build attempts;
- 209 repositories reported call-graph availability;
- 30826 methods with `@see`, 14856 methods with `{@inheritDoc}`, and 5386 methods with inherited-doc candidates;
- 69 repositories required bounded retry controls.
