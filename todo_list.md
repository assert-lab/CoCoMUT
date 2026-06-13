# Context4DocuGen Productization TODO

This document is a roadmap for turning Context4DocuGen from a research artifact into a reusable Java tool for method-context extraction. The target is not "another OE-25 pipeline", but a public project that other researchers and developers can use like JavaParser, Spoon, SootUp, or WALA.

## Current Branch Status

The current product branch has applied the main productization pass:

- stable public Java namespace: `org.assertlab.context4docugen`;
- Maven wrapper and GitHub Actions CI;
- Maven metadata for license, SCM, developers, issues, source jars, and Javadoc jars;
- standalone shaded CLI jar via `context4docugen-cli` and `scripts/build_release_jar.sh`;
- typed API: `ContextExtractorService`, `ContextRequest`, `MethodSelection`, and `ExtractionReport`;
- Picocli commands: `c4dg extract`, `c4dg validate`, and `c4dg schema`;
- JSON and JSONL output modes;
- source-set labels, documentation metrics, Javadoc metadata, and provenance fields;
- optional SootUp call graph modes: `none`, `cha`, and `rta`;
- low-memory smoke-run controls: `--max-methods` and `--max-source-files`;
- per-method failure artifacts: `selected_method_failures.jsonl` and `method_context_failures.jsonl`;
- stricter generated JSON/JSONL validation against the documented C4DG record contract;
- contributor, security, citation, changelog, schema, README, known-issues, and field-test docs.

Remaining release work is mostly operational: choose the first real version
number, add signing/deployment credentials if Maven Central publication is
desired, and run field tests before tagging a release.

## Current Diagnosis

Context4DocuGen already has a useful core idea:

- given a Java project and a selected method, collect method-level context;
- include source context, class context, call graph context, and documentation text;
- export structured JSON that can be used for documentation-generation or empirical software-engineering studies.

The original problem was repository shape. Before productization, the repo looked like a research bundle rather than a reusable Java library.

Historical baseline before productization:

| Area | Size | Interpretation |
| --- | ---: | --- |
| Original research snapshot | ~530 MB | Too large for a normal public Java tool repository. |
| Bundled datasets | hundreds of MB | OE-25/project data, not core library code. |
| Docstring-generation experiments | tens of MB | Valuable research layer, but not the core extractor. |
| Current product repo | small Maven multi-module project | Core library, CLI, tests, docs, fixtures, and schemas only. |
| `analyzer-core/` | core module | The reusable Java analysis implementation. |
| `analyzer-tests/` | test module | Tests and small fixture projects. |

The important finding is that the product is not intrinsically huge. The reusable tool is probably below 10-20 MB once datasets, generated outputs, and research experiments are separated.

## Comparison With Reusable Java Tools

The local comparison repos are under `/home/ale/repos/reusable_wheels/`: JavaParser, Spoon, SootUp, and WALA.

| Dimension | JavaParser | Spoon | SootUp | WALA | Current C4DG |
| --- | --- | --- | --- | --- | --- |
| Main identity | Java parsing library | Java program analysis/transformation library | Static-analysis framework | Static-analysis framework | Method-context extraction library and CLI |
| Primary usage | Maven/Gradle dependency | Maven/Gradle dependency | Maven modules | Gradle modules | Maven module, Java API, CLI, and executable jar |
| Public API | Clear modules: `javaparser-core`, symbol solver | Clear API around `Launcher`, `CtModel`, processors | Domain modules like `sootup.core`, `sootup.callgraph` | Modular framework packages | Stable `org.assertlab.context4docugen` API namespace, still pre-release |
| CLI | Secondary or absent | Secondary; API is central | Examples/docs, not script-first | Build/tooling scripts exist, but framework-first | Picocli CLI plus compatibility wrappers |
| Data in repo | No large benchmark datasets | No large benchmark datasets | No large benchmark datasets in core repo | No bundled huge target datasets | No bundled target-repository snapshots in the product repo |
| Docs | README, contribution docs, changelog/features | README, citation, security, roadmap, docs | README, docs site, citation, security | README, Gradle guide, release notes | README, schema, changelog, field-test report, known issues, contributor docs |
| Release readiness | Maven Central style | Maven Central/JReleaser style | Maven multi-module style | Gradle release style | Pre-release Maven multi-module project with shaded CLI jar |
| Tests | Library tests and fixtures | Library tests and fixtures | Analysis tests/examples | Framework tests | Unit tests, fixture projects, API/CLI/schema checks, and field-test scripts |

The main difference is not code quality alone. It is product boundary. JavaParser/Spoon/SootUp/WALA separate:

- reusable library code;
- tests and tiny fixtures;
- documentation;
- release metadata;
- examples.

The pre-productization Context4DocuGen snapshot mixed:

- reusable analyzer code;
- OE-25 benchmark snapshots;
- sample-selection scripts;
- generated context outputs;
- LLM docstring-generation experiments;
- historical verification reports;
- local automation/agent instructions.

That is why it feels heavy and difficult to understand.

## Target Product Definition

Define Context4DocuGen as:

> A Java library and optional CLI for extracting structured method-level context from Java projects, especially for documentation generation and empirical studies of code documentation.

This is the product surface:

- **Library first**: users should be able to add it as a Maven/Gradle dependency and call Java APIs.
- **CLI second**: users should be able to run `c4dg extract ...` without writing Java code.
- **Research adapters optional**: OE-25 selection logic, docstring generation, and benchmark scripts should not be part of the minimal core.
- **Stable JSON schema**: output should be documented and versioned.
- **Small fixtures only**: keep tiny test projects, not full benchmark corpora.

## How Tools Like JavaParser Are Usually Used

Most reusable Java tools are not primarily used by cloning the repository and running shell scripts.

Typical JavaParser-style usage:

```xml
<dependency>
  <groupId>com.github.javaparser</groupId>
  <artifactId>javaparser-core</artifactId>
  <version>...</version>
</dependency>
```

Then a user writes Java code:

```java
CompilationUnit cu = StaticJavaParser.parse(sourceFile);
cu.findAll(MethodDeclaration.class).forEach(method -> {
    System.out.println(method.getNameAsString());
});
```

Typical Spoon-style usage:

```xml
<dependency>
  <groupId>fr.inria.gforge.spoon</groupId>
  <artifactId>spoon-core</artifactId>
  <version>...</version>
</dependency>
```

Then a user writes Java code:

```java
Launcher launcher = new Launcher();
launcher.addInputResource("src/main/java");
launcher.buildModel();
CtModel model = launcher.getModel();
```

For Context4DocuGen, the equivalent desired usage should become something like:

```xml
<dependency>
  <groupId>org.assertlab.context4docugen</groupId>
  <artifactId>context4docugen-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
ContextRequest request = ContextRequest.builder()
    .projectRoot(Path.of("/path/to/java/project"))
    .selection(MethodSelection.fromCsv(Path.of("selected-methods.csv")))
    .build();

ContextExtractorService service = ContextExtractorService.createDefault();
List<MethodContext> contexts = service.extract(request);
```

And the CLI should be a thin wrapper around the same API:

```bash
c4dg extract --project /path/to/java/project --methods selected-methods.csv --output context-json/
c4dg extract --project /path/to/java/project --scan public --format jsonl --output contexts.jsonl
```

The shell script can remain during transition, but it should not be the main public endpoint.

## Recommended Repository Shape

Target structure:

```text
Context4DocuGen/
  README.md
  LICENSE
  CONTRIBUTING.md
  SECURITY.md
  CITATION.cff
  pom.xml
  context4docugen-core/
    src/main/java/org/assertlab/context4docugen/...
    src/test/java/...
  context4docugen-cli/
    src/main/java/org/assertlab/context4docugen/cli/...
  context4docugen-examples/
    minimal-maven-project/
    selected-methods-example/
  context4docugen-integration-tests/
    src/test/resources/fixtures/...
  docs/
    architecture.md
    input-output-format.md
    cli.md
    limitations.md
    research-adapters.md
  schemas/
    method-context.schema.json
    selected-methods.schema.json
```

The exact module names can be discussed, but the separation should be clear:

- `core`: reusable extraction library;
- `cli`: command-line interface;
- `examples`: small examples only;
- `integration-tests`: fixture projects and end-to-end tests;
- `docs`: stable user/developer documentation;
- `schemas`: versioned input/output contracts.

## Package Naming

Earlier packages such as `analyzer`, `core`, `csv`, and `extraction` were too generic for a public library.

The current product branch uses a stable namespace:

```text
org.assertlab.context4docugen
org.assertlab.context4docugen.api
org.assertlab.context4docugen.model
org.assertlab.context4docugen.extraction
org.assertlab.context4docugen.callgraph
org.assertlab.context4docugen.cli
```

This matters because public Java users import package names directly. Renaming packages after release is painful.

## What To Remove Or Move Out Of The Core Repository

### Move Out: `Datasets/projects/`

This folder contains complete project snapshots for OE-25. It is useful for reproducing the paper pipeline, but it should not live in the public tool repository.

Action:

- move full project snapshots to a separate artifact location, such as Zenodo, Hugging Face Datasets, GitHub Releases, or a separate `context4docugen-replication-package` repository;
- keep only one or two tiny fixture projects under tests;
- document how to download the full benchmark if someone wants to reproduce the research experiment.

### Move Out Or Split: `docstring_generation/`

This is an LLM experiment layer, not the core method-context extractor.

Action:

- move it to a separate repository or separate module named something like `context4docugen-docstring-generation`;
- keep only a short example showing how C4DG JSON can feed a documentation-generation model;
- do not make the core tool depend on Poetry, Python experiment configs, or LLM-specific output directories.

### Archive Or Delete Duplicate Docs

The original root had many historical docs and verification summaries:

- `CLEANUP_SUMMARY.md`
- `COMBINED_VERIFICATION_TABLE.md`
- generated field-test summaries such as `GENERATED_*_SUMMARY.md`
- `METHOD_CONTEXT_AUTOMATION_SKILL.md`
- `PIPELINE_OUTPUTS_GUIDE.md`
- `TESTING_AND_ORGANIZATION_RESULTS.md`
- `UNIFIED_VERIFIER_SUMMARY.md`
- `VERIFICATION_ARCHITECTURE.md`
- `USER_GUIDE.md`
- `QUICKSTART.md`
- `FRAMEWORK_README.md`

Productization action:

- keep one `README.md`;
- keep one `docs/architecture.md`;
- keep one `docs/cli.md`;
- keep one `docs/input-output-format.md`;
- move old research reports into `docs/archive/` or into a replication package;
- remove local agent/automation docs from the public product unless they are intentionally part of contributor workflow.

### Remove Public Reliance On Shell Scripts

Current root scripts:

- `method-context.sh`
- `run_all_projects.sh`
- `setup.sh`

Action:

- replace the main user endpoint with a Java CLI module;
- keep shell scripts only as developer convenience wrappers;
- ensure the README teaches `mvn test`, `mvn package`, and `c4dg extract`, not "run this repository-specific shell script first".

### Ignore Generated Outputs

Add or verify `.gitignore` entries for:

```gitignore
**/target/
**/method_context_json/
**/methods.csv
**/Output_CallGraph_CHA.txt
Datasets/projects/
Datasets/OE-25/
docstring_generation/outputs/
```

Do not track generated context JSON, call-graph output, selected CSVs, or benchmark target repositories in the core product repo.

## Core Technical Improvements

### 1. Replace Regex Method Matching With AST Matching

Current selected-method loading is fragile for constructors, nested classes, annotations, generics, compact constructors, overloads, and formatting differences.

Action:

- introduce a `SourceModelProvider` abstraction;
- implement method identity using AST nodes, not regex over source text;
- match by package, class, nested-class path, method/constructor name, parameter arity, parameter types when available, and source position;
- expose matching confidence and failure reason.

Example failure class:

```java
class Box<T extends Comparable<T>> {
    Box(final T value) {}

    <U extends Comparable<U>> U max(final U a, final U b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
```

A regex matcher can miss or misidentify this because the declaration is not a simple `public int name(...)` shape.

### 2. Choose Source Parser Strategy

Main options:

| Option | Strength | Weakness | Recommendation |
| --- | --- | --- | --- |
| JavaParser | Lightweight, easy AST/Javadoc parsing, familiar dependency | Symbol resolution can be fragile without classpath; less complete source model than Spoon | Good if the immediate goal is robust method/Javadoc extraction. |
| Spoon | Rich Java source model, good for analysis/transformation, strong abstraction over declarations | Heavier dependency and learning curve | Best fit if C4DG wants robust source-level context beyond simple parsing. |
| Eclipse JDT | Mature compiler-grade parser | More complex setup; less pleasant as a public API dependency | Use only if compiler compatibility becomes the main requirement. |

Preferred product choice: use Spoon or JavaParser behind an internal interface. For C4DG, I would prefer **Spoon for the source model** and keep **SootUp for call graphs**. Spoon gives richer source-level entities, while SootUp remains appropriate for bytecode/call-graph analysis.

### 3. Keep SootUp For Call Graphs, But Make It Optional/Configurable

Call graph extraction is valuable, but it is also expensive and classpath-sensitive.

Action:

- support context extraction with call graph disabled;
- support configurable algorithms, for example `CHA` first and later `RTA`/more precise options if supported;
- record call-graph algorithm and classpath status in output metadata;
- never silently pretend call graph context is complete if classpath resolution failed.

### 4. Version The JSON Output

Every output record should include:

```json
{
  "schemaVersion": "0.1.0",
  "tool": {
    "name": "Context4DocuGen",
    "version": "0.1.0"
  }
}
```

Action:

- create `schemas/method-context.schema.json`;
- document required and optional fields;
- define how missing context is represented;
- include provenance for each context block.

### 5. Add Provenance And Confidence

For empirical research, it is not enough to output context. The tool should say how the context was obtained.

Example:

```json
{
  "methodBody": {
    "value": "...",
    "source": "AST",
    "confidence": "exact"
  },
  "callers": {
    "value": [],
    "source": "SootUp-CHA",
    "confidence": "conservative",
    "warnings": ["classpath-incomplete"]
  }
}
```

This helps downstream researchers filter noisy cases instead of treating all generated JSON as equally reliable.

### 6. Treat Tests And Examples Separately

Tests can be useful usage context, but they should not be mixed with production API documentation by default.

Action:

- add source-set classification: `main`, `test`, `generated`, `example`, `unknown`;
- default extraction should focus on production source;
- allow an explicit flag like `--include-tests` or `ContextOptions.includeTests(true)`;
- record source-set in every method context.

### 7. Improve Method Selection Inputs

Define a stable selected-method input format.

Example CSV:

```csv
id,projectRoot,sourcePath,packageName,typeName,methodName,parameterTypes,line
1,/repo,src/main/java/a/B.java,a,B,max,"java.lang.String,java.lang.String",42
```

Action:

- document required fields;
- allow partial matching, but expose confidence;
- validate inputs before extraction;
- report unmatched rows with specific reasons.

## Build And Release TODO

### Maven Structure

The current project already uses Maven, so keep Maven unless there is a strong reason to switch. JavaParser, Spoon, and SootUp are also Maven-based, so Maven is a natural fit.

Action:

- keep a root `pom.xml`;
- add/keep Maven wrapper files `mvnw` and `mvnw.cmd`;
- split modules into core, cli, examples/tests;
- configure source and Javadoc jars;
- configure license metadata, SCM metadata, developer metadata, and reproducible builds.

### Java Version

Pick a conservative runtime baseline.

Action:

- prefer Java 17 as the library runtime baseline unless a dependency forces newer Java;
- test on Java 17 and Java 21 in CI;
- parsing newer Java source can be supported separately from requiring a newer runtime.

### CLI

Use a standard Java CLI library such as Picocli.

Target commands:

```bash
c4dg extract --project /path/to/project --scan public --output out/
c4dg extract --project /path/to/project --methods selected-methods.csv --output out/
c4dg validate --methods selected-methods.csv
c4dg schema --print method-context
```

The CLI should call the same API used by library consumers.

### Continuous Integration

Add GitHub Actions:

- build and test on pull requests;
- run unit tests;
- run integration tests on tiny fixtures;
- run formatting/checkstyle if chosen;
- optionally run a slower call-graph integration job separately.

### Release Metadata

Add:

- `LICENSE`;
- `CONTRIBUTING.md`;
- `SECURITY.md`;
- `CITATION.cff`;
- `CHANGELOG.md`;
- Maven Central metadata;
- release notes template.

For academic software, `CITATION.cff` is especially important.

## Documentation TODO

The public docs should be much smaller and clearer.

### README.md

The README should answer, in this order:

1. What is Context4DocuGen?
2. What problem does it solve?
3. How do I install it as a dependency?
4. How do I run the CLI?
5. What does the output JSON look like?
6. What Java projects are supported?
7. What are the known limitations?
8. How do I cite it?

### docs/architecture.md

Explain:

- source parser layer;
- call graph layer;
- context extraction layer;
- serialization layer;
- CLI layer.

### docs/input-output-format.md

Explain:

- selected-method CSV schema;
- method-context JSON schema;
- provenance/confidence fields;
- examples of successful and failed extraction.

### docs/limitations.md

Be explicit about:

- classpath sensitivity;
- reflection and dynamic dispatch;
- generated code;
- Lombok;
- incomplete Maven/Gradle builds;
- conservative call graphs;
- inherited Javadocs.

Good tools document boundaries clearly.

## Minimization Plan

### Phase 1: Make A Clean Product Branch

Create a branch such as:

```bash
git switch -c productization/minimal-core
```

Do not start by deleting everything on `main` unless the maintainers agree.

### Phase 2: Move Research Artifacts Out

Move out:

- `Datasets/projects/`
- large OE-25 generated outputs;
- `docstring_generation/outputs/`;
- historical generated reports;
- local automation files that are not public contributor docs.

Keep:

- Java core code;
- tests;
- tiny fixture projects;
- a short OE-25 reproduction guide pointing to external artifacts.

Expected result:

- repository should stay small enough for a normal source checkout;
- generated outputs, downloaded repositories, and field-test workspaces should remain ignored or external.

### Phase 3: Collapse Docs

Replace many root Markdown files with:

```text
README.md
CONTRIBUTING.md
SECURITY.md
CITATION.cff
docs/architecture.md
docs/cli.md
docs/input-output-format.md
docs/limitations.md
docs/research-replication.md
```

Historical docs can go into a separate replication package.

### Phase 4: Define API Before More Features

Before adding many context types, define the stable API:

- `ContextRequest`;
- `ContextOptions`;
- `MethodSelection`;
- `ContextExtractorService`;
- `MethodContext`;
- `ExtractionReport`.

This prevents the CLI, tests, and research adapters from each inventing their own shape.

### Phase 5: Replace Fragile Internals

Order of technical fixes:

1. AST-based method matching.
2. AST-attached Javadoc extraction.
3. Structured parameter and exception extraction.
4. Nested class and constructor support.
5. Provenance/confidence reporting.
6. Stable JSON schema.
7. Configurable call graph extraction.

This order matters because bad method identity corrupts every later context block.

## Concrete TODO Checklist

### Implemented On Product Branch

- [x] Minimized the product branch by removing bundled research datasets and generated outputs.
- [x] Added a minimal public API entry point through `AnalyzerFacade` and `AnalysisOptions`.
- [x] Added typed API objects: `ContextExtractorService`, `ContextRequest`, `MethodSelection`, and `ExtractionReport`.
- [x] Added lightweight `bin/c4dg` wrappers for CLI usage.
- [x] Added a dedicated `context4docugen-cli` module with Picocli.
- [x] Added `c4dg extract`, `c4dg validate`, and `c4dg schema`.
- [x] Added a standalone shaded CLI distribution and `scripts/build_release_jar.sh`.
- [x] Added JSONL output for large-scale mining.
- [x] Added source-set labels for `main`, `test`, `integration_test`, `generated`, `example`, and `unknown`.
- [x] Added Javadoc metadata extraction for `@since`, `@see`, inline links, deprecation, and `{@inheritDoc}` use.
- [x] Added report-level failure taxonomy through `FailureCode`.
- [x] Configured normal, sources, and Javadocs jars for Maven packaging.
- [x] Added a reproducible expanded field-test runner.
- [x] Ran source-only field testing on 541 English, non-tutorial Java repositories, including Android repositories.
- [x] Ran bounded CHA/RTA call-graph field tests on compiled Maven repositories.

Remaining high-priority gaps from that work:

- [x] Add per-method failure artifacts for selected-method misses and context-extraction misses.
- [x] Reduce memory pressure for very large repositories with bounded source-file/method controls and capped source context.
- [x] Rename public packages from `analyzer` to `org.assertlab.context4docugen`.
- [ ] Add a faster preflight size model so very large repositories can start directly in bounded mode.
- [ ] Preserve duplicate-method discovery statistics in reports even though duplicate URIs are now deduplicated before output.

### Repository Hygiene

- [ ] Decide whether `main` is the product repo or whether to create a fresh product repo.
- [x] Move full OE-25 project snapshots out of `Datasets/projects/`.
- [x] Move LLM/docstring-generation experiments out of the core repo or into a separate optional repo.
- [x] Keep only tiny fixture projects under tests.
- [x] Add `.gitignore` rules for generated outputs.
- [x] Remove or archive duplicated root-level Markdown reports.
- [ ] Remove local agent-specific files from the public product surface unless intentionally documented.

### Product API

- [x] Rename generic Java packages from `analyzer` to `org.assertlab.context4docugen`.
- [x] Introduce `ContextExtractorService`.
- [x] Introduce `ContextRequest`.
- [x] Introduce `MethodSelection`.
- [x] Introduce `AnalysisOptions` as the current options object.
- [x] Introduce `ExtractionReport`.
- [x] Make CLI call the public API instead of duplicating pipeline logic.

### CLI

- [x] Add `context4docugen-cli` module.
- [x] Use Picocli or equivalent.
- [x] Implement `c4dg extract`.
- [x] Implement `c4dg validate`.
- [x] Implement `c4dg schema`.
- [x] Keep shell scripts only as compatibility wrappers during transition.

### Analysis Quality

- [x] Replace regex method matching with AST matching.
- [x] Replace regex Javadoc extraction with AST-attached comments/Javadoc parsing.
- [x] Extract structured parameters from AST nodes.
- [x] Extract thrown exceptions and declared exceptions.
- [x] Support constructors, compact constructors, nested classes, overloads, annotations, generics, and package-private methods.
- [x] Add source-set classification for main/test/generated/example code.
- [ ] Report unmatched selected methods with failure reasons.

### Output Quality

- [x] Version the JSON schema.
- [x] Add provenance for each context block.
- [x] Add confidence/warnings for fragile contexts.
- [x] Make call graph algorithm explicit in output.
- [x] Define stable IDs that are safe as filenames and JSON identifiers.
- [x] Add JSONL output for large-scale mining.

### Build And Release

- [ ] Add Maven wrapper.
- [x] Choose Java 17 or Java 21 baseline.
- [x] Configure source and Javadoc jars.
- [ ] Add GitHub Actions CI.
- [ ] Add Maven Central-ready metadata.
- [ ] Add `CHANGELOG.md`.
- [ ] Add `CITATION.cff`.
- [ ] Add `SECURITY.md`.

### Documentation

- [x] Rewrite README as product documentation, not research-run documentation.
- [x] Add minimal dependency example.
- [x] Add minimal Java API example.
- [x] Add CLI example.
- [ ] Add one tiny output JSON example.
- [x] Add limitations page.
- [ ] Add research-replication page linking to OE-25 artifacts.
