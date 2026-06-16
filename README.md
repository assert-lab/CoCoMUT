# Context4DocuGen

Context4DocuGen is a static Java analysis tool for extracting method-level context for documentation research and documentation-generation systems.

For each method, it can write JSON containing:

- method URI, name, signature, source code, Javadoc, class Javadoc, and class hierarchy;
- structured parameters, annotations, thrown exceptions, field usage, overload groups, dynamic-feature hints, and documentation metrics;
- optional SootUp caller/callee context when bytecode is available;
- provenance metadata describing how context was extracted.

The tool is intentionally source/static-analysis based. It does not execute the analyzed program.

## Repository Shape

```text
analyzer-core/   Java library and extraction API
context4docugen-cli/ Standalone Picocli command-line application
analyzer-tests/  unit and integration tests with tiny fixtures
examples/        small API usage example
schemas/         machine-readable schema drafts
scripts/         release/field-test helper scripts
todo_list.md     productization roadmap and design notes
```

Large research artifacts, OE-25 project snapshots, generated model outputs, and shell-first research scripts were removed from the minimal product branch.

## Build

```bash
mvn test
```

If Maven cannot write to your global `~/.m2`, use a local repository:

```bash
mvn -Dmaven.repo.local=.m2/repository test
```

The project targets Java 17.

Build the standalone CLI jar:

```bash
scripts/build_release_jar.sh
```

The script runs tests, packages all modules, and writes:

```text
dist/context4docugen-cli.jar
```

## Method Identity

Context4DocuGen identifies methods by URI, not by arbitrary row IDs.

Format:

```text
relative/path/ToFile.java#qualified.DeclaringClass.method(signature)
```

Example:

```text
src/main/java/com/example/Hello.java#com.example.Hello.greet(String name)
```

This is important because overloaded methods need more than a method name. A reliable locator needs:

- the `.java` source path;
- the declaring class and method/constructor name;
- the method signature.

## CLI Usage

The CLI can be run through `bin/c4dg` from the repository root:

```bash
./bin/c4dg extract --project /path/to/java/project --scope entry-points --call-graph none
```

`bin/c4dg` uses `dist/context4docugen-cli.jar` when it exists. If the jar has not been built yet, it builds and runs the shaded development jar from `context4docugen-cli/target/`.

You can also run the standalone jar directly:

```bash
java -jar dist/context4docugen-cli.jar extract \
  --project /path/to/java/project \
  --scope entry-points \
  --call-graph none \
  --output jsonl
```

Available commands:

```text
c4dg extract   Extract method contexts
c4dg validate  Validate project detection, selected CSVs, JSON, or JSONL
c4dg schema    Print or write bundled schemas
```

Run selected-method mode:

```bash
./bin/c4dg extract \
  --project /path/to/java/project \
  --selected /path/to/selected-methods.csv \
  --call-graph none \
  --output jsonl
```

Useful options:

```text
--scope all|entry-points       Method scope for source scanning
  --call-graph none|cha|rta|auto Optional SootUp call graph mode
--resolution noclasspath|classpath|auto
                                Spoon source-resolution mode
--output json|jsonl|both       Output format
--output-dir DIR               Directory for generated artifacts
--max-methods N                Limit methods for smoke tests
--max-source-files N           Limit parsed Java files for low-memory smoke tests
--source-set all|main|test|integration_test|generated|example|unknown
                                Filter methods by source set
--package NAME                 Include package prefix, repeatable/comma-separated
--class NAME                   Include fully qualified or simple class name
--method NAME                  Include method name or method URI substring
--visibility public|protected|package-private|private
                                Include methods with matching visibility
--include-path GLOB            Include source path glob relative to project root
--exclude-path GLOB            Exclude source path glob relative to project root
--compile                      Attempt Maven/Gradle compilation before analysis
```

`--resolution auto` attempts bounded Maven/Gradle compilation when useful,
tries Spoon classpath-aware extraction when compiled classes or dependency jars
are available, and falls back to no-classpath mode if resolution is incomplete
or loses too much method coverage. `--call-graph auto` also uses bounded
compilation and asks SootUp for RTA call graphs when class directories are
available; otherwise it records the call graph as unavailable and continues
source-only.

For documentation datasets, prefer:

```bash
./bin/c4dg extract \
  --project /path/to/java/project \
  --scope entry-points \
  --source-set main \
  --call-graph none \
  --output jsonl
```

`--source-set main` excludes public methods found under test, generated,
example, integration-test, or unknown source roots. Use `--source-set all` or
omit the flag to preserve the default behavior.

Layered selection is available when you do not want the whole repository:

```bash
./bin/c4dg extract \
  --project /path/to/java/project \
  --package org.example.api \
  --class PublicApi \
  --method parse \
  --visibility public \
  --include-path 'src/main/java/**/*.java' \
  --exclude-path '**/generated/**' \
  --output jsonl
```

Repository-wide extraction writes `method_contexts.jsonl`. Package, class, or
method-filtered extraction writes a distinguishable JSONL filename based on the
selected thing, for example `package__org.example.api.jsonl`,
`class__org.example.PublicApi.jsonl`, or `method__parse.jsonl`.

Validation examples:

```bash
./bin/c4dg validate --project /path/to/java/project
./bin/c4dg validate --selected selected-methods.csv
./bin/c4dg validate --json method_context_json/example.json
./bin/c4dg validate --jsonl method_contexts.jsonl
```

Schema examples:

```bash
./bin/c4dg schema method-context
./bin/c4dg schema selected-methods --output selected-methods.schema.json
```

By default, generated artifacts are written outside the analyzed project:

```text
./c4dg_output/<project-name>/
  method_contexts.jsonl
  method_context_json/*.json   when --output json|both
  Output_CallGraph_CHA.txt     when CHA is used
  Output_CallGraph_RTA.txt     when RTA is used
  selected_method_failures.jsonl
  method_context_failures.jsonl
```

Use `--output-dir` to choose an explicit destination:

```bash
./bin/c4dg extract --project /path/to/java/project --output-dir ./results/project-name
```

Failure artifacts are written next to the normal outputs:

```text
selected_method_failures.jsonl when selected CSV rows cannot be matched
method_context_failures.jsonl  when a discovered method cannot be contextualized
```

## Selected-Method CSV

Preferred selected CSV columns:

```csv
method_uri|docstring|test_prefix
src/main/java/com/example/Hello.java#com.example.Hello.greet(String name)|Greets a person.|new Hello().greet("x")
```

Legacy PoC/research CSVs with `focal_method|test_prefix|docstring|id` are still accepted. In that mode, the loader parses source and emits method URIs anyway; the old `id` is not used as the method identity.

## API Usage

Add the dependency after installing the project locally:

```bash
mvn install
```

```xml
<dependency>
  <groupId>org.assertlab.context4docugen</groupId>
  <artifactId>analyzer-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Minimal Java API:

```java
import org.assertlab.context4docugen.AnalysisOptions;
import org.assertlab.context4docugen.CallGraphGenerator;
import org.assertlab.context4docugen.ContextExtractorService;
import org.assertlab.context4docugen.ContextRequest;
import org.assertlab.context4docugen.ExtractionReport;
import org.assertlab.context4docugen.MethodSelection;

import java.nio.file.Path;

class Example {
    public static void main(String[] args) throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("/path/to/java/project"))
                .methodSelection(MethodSelection.entryPoints())
                .callGraphAlgorithm(org.assertlab.context4docugen.CallGraphGenerator.Algorithm.NONE)
                .outputMode(AnalysisOptions.OutputMode.JSONL)
                .maxSourceFiles(500) // optional low-memory smoke-run cap
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        System.out.println(report.status());
        System.out.println(report.jsonlFile());
    }
}
```

A runnable toy API project is in:

```text
examples/api-toy-project/
```

Run it from the repository root:

```bash
mvn install
cd examples/api-toy-project
mvn compile exec:java -Dexec.mainClass=example.RunContext4DocuGen
```

To analyze another project:

```bash
mvn compile exec:java \
  -Dexec.mainClass=example.RunContext4DocuGen \
  -Dexec.args="/path/to/java/project"
```

## Static Analysis Boundaries

Context4DocuGen does not perform dynamic analysis. It does not execute tests, run application code, observe runtime values, or resolve reflection dynamically.

Current static-analysis boundaries:

- source extraction uses Spoon; auto mode keeps no-classpath extraction as the coverage baseline and uses classpath-aware extraction when it is available and coverage-preserving;
- call graph extraction uses optional SootUp `CHA` or `RTA`;
- call graph quality depends on compiled class directories and classpath resolution;
- build-tool compilation is explicit via `--compile` or opportunistic through `--resolution auto` / `--call-graph auto`; otherwise C4DG only reuses existing class files;
- reflection, proxies, generated code, Lombok, service loaders, and dependency injection can reduce precision, but common dynamic-feature hints are labeled in JSON;
- generated methods from Lombok/annotation processors are not visible unless generated source or bytecode is available.

The JSON output schema is summarized in `JSON_SCHEMA.md`. Known limitations and field-test results are recorded in `known_issues.md` and `FIELD_TEST_RESULTS.md`.

## Reproduce Field Study

Run the expanded public-repository study with:

```bash
scripts/run_expanded_auto_field_study.sh
```

The wrapper builds the release jar, selects repositories from
`../cleaned_mined_repos.csv`, runs C4DG with auto source resolution and auto
call graph, extracts `@see` / `{@inheritDoc}` cases, and writes preserved local
artifacts under:

```text
experiments/expanded-public-repos-auto-main/
```

The default run uses `--source-set main`, so test/example/generated methods are
excluded before context generation. Override parameters with environment
variables, for example:

```bash
LIMIT=20 OUTPUT_DIR=experiments/smoke-auto-main scripts/run_expanded_auto_field_study.sh
```

For broad corpus runs, the wrapper is resume-safe and uses bounded retries:
source-file caps for large repositories, `main,unknown` for nonstandard source
layouts, and a final source-only smoke cap for repositories that would otherwise
timeout under auto build/call-graph mode. Exact retry modes are recorded in the
generated `results.tsv` and `summary_counts.txt`.
