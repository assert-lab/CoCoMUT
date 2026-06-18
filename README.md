# CoCoX

CoCoX (Code Context Extractor) is a tool for extracting method-level context from Java systems.

For each method, it writes one JSONL record containing:

- method URI, name, signature, source code, Javadoc, class Javadoc, and class hierarchy;
- structured parameters, annotations, thrown exceptions, field usage, overload groups, dynamic-feature hints, and documentation metrics;
- optional SootUp caller/callee context when bytecode is available;
- provenance metadata describing how context was extracted.

The tool is intentionally source/static-analysis based. It does not execute the analyzed program.

## Javadoc Standards Basis

CoCoX parses common Javadoc tags using the official Oracle/JDK Javadoc syntax
and standard doclet model, not repository-specific conventions. This applies to
the common documentation tags and inline tags that matter for method-context
mining, including `@param`, `@return`, `@throws` / `@exception`, `@since`,
`@deprecated`, `@see`, `{@link ...}`, `{@linkplain ...}`, `{@code ...}`,
`{@literal ...}`, `{@value ...}`, and `{@inheritDoc}`.

For references, `@see`, `{@link ...}`, and `{@linkplain ...}` are interpreted
using the standard program-element reference form:

```text
module/package.Type#member optional-label
```

CoCoX also recognizes the standard `@see "text"` and
`@see <a href="...">label</a>` forms. Project-local references may be resolved
to CoCoX URIs; external JDK/library references are kept as symbol-level
metadata only, without fetching external Javadoc text.

Official sources used:

- JDK 17 documentation-comment specification for the standard doclet:
  <https://docs.oracle.com/en/java/javase/17/docs/specs/javadoc/doc-comment-spec.html>
- JDK 25 documentation-comment specification for the standard doclet:
  <https://docs.oracle.com/en/java/javase/25/docs/specs/javadoc/doc-comment-spec.html>
- JDK 8 Javadoc tool reference:
  <https://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html>

These specifications are versioned with the JDK. The core block tags, inline
tags, `@see`, and `{@link ...}` forms are long-standing and stable. Newer JDKs
add or extend features such as module prefixes, inline `{@return ...}`,
`{@snippet ...}`, and Markdown documentation comments; CoCoX treats
version-specific features explicitly instead of inferring rules from a single
project such as Apache Commons Lang.

## Repository Shape

```text
analyzer-core/   Java library and extraction API
cocox-cli/ Standalone Picocli command-line application
analyzer-tests/  unit and integration tests with tiny fixtures
examples/        small API usage example
schemas/         machine-readable schema drafts and schema documentation
scripts/         release/field-test helper scripts
```


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
dist/cocox-cli.jar
```

## Method Identity

CoCoX identifies methods by URI.

Format:

```text
relative/path/ToFile.java#qualified.DeclaringClass.method(erasedParamTypes):erasedReturnType
```

Example:

```text
src/main/java/com/example/Hello.java#com.example.Hello.greet(java.lang.String):java.lang.String
```

This is important because overloaded methods need more than a method name. A reliable locator needs:

- the `.java` source path;
- the declaring class and method/constructor name;
- the erased parameter types;
- the erased return type.

For the broader symbol model, including `type_uri` and `package_uri`
selection, see [docs/symbol-model.md](docs/symbol-model.md).

## CLI Usage

The CLI can be run through `bin/cocox` from the repository root:

```bash
./bin/cocox extract --project /path/to/java/project --scope entry-points --call-graph none
```

`bin/cocox` uses `dist/cocox-cli.jar` when it exists. If the jar has not been built yet, it builds and runs the shaded development jar from `cocox-cli/target/`.

You can also run the standalone jar directly:

```bash
java -jar dist/cocox-cli.jar extract \
  --project /path/to/java/project \
  --scope entry-points \
  --call-graph none
```

Available commands:

```text
cocox extract   Extract method contexts
cocox validate  Validate project detection or generated JSONL
cocox schema    Print or write bundled schemas
```

Run exact method-URI selection:

```bash
./bin/cocox extract \
  --project /path/to/java/project \
  --method-uri 'src/main/java/com/example/Hello.java#com.example.Hello.greet(java.lang.String):java.lang.String' \
  --call-graph none
```

Useful options:

```text
--scope all|entry-points       Method scope for source scanning
--call-graph none|cha|rta|auto Optional SootUp call graph mode
--resolution noclasspath|classpath|auto
                                Spoon source-resolution mode
--output-dir DIR               Directory for generated artifacts
--max-methods N                Limit methods for smoke tests
--max-source-files N           Limit parsed Java files for low-memory smoke tests
--source-set all|main|test|integration_test|generated|example|unknown
                                Filter methods by source set
--package NAME                 Include package prefix, repeatable/comma-separated
--class NAME                   Include fully qualified or simple class name
--method NAME                  Include method name or method URI substring
--target-uri KIND:URI          Exact target URI, where KIND is method, type,
                                package, or project
--method-uri URI               Exact method URI target
--type-uri URI                 Exact type URI target: path#qualified.Type
--class-uri URI                Alias for --type-uri
--package-uri URI              Exact package URI target
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
./bin/cocox extract \
  --project /path/to/java/project \
  --scope entry-points \
  --source-set main \
  --call-graph none
```

`--source-set main` excludes public methods found under test, generated,
example, integration-test, or unknown source roots. Use `--source-set all` or
omit the flag to preserve the default behavior.

Layered selection is available when you do not want the whole repository:

```bash
./bin/cocox extract \
  --project /path/to/java/project \
  --package org.example.api \
  --class PublicApi \
  --method parse \
  --visibility public \
  --include-path 'src/main/java/**/*.java' \
  --exclude-path '**/generated/**'
```

Repository-wide extraction writes `method_contexts.jsonl`. Package, class, or
method-filtered extraction writes a distinguishable JSONL filename based on the
selected target, for example `package__org.example.api.jsonl`,
`class__org.example.PublicApi.jsonl`, or `method__parse.jsonl`.

CoCoX supports both filter-based package/class/method selection and exact URI
targets through `--target-uri`, `--method-uri`, `--type-uri` / `--class-uri`,
and `--package-uri`; see [docs/symbol-model.md](docs/symbol-model.md).

Examples:

```bash
./bin/cocox extract \
  --project /path/to/java/project \
  --type-uri 'src/main/java/org/example/Foo.java#org.example.Foo'

./bin/cocox extract \
  --project /path/to/java/project \
  --package-uri 'src/main/java/org/example/package-info.java#org.example'
```

Validation examples:

```bash
./bin/cocox validate --project /path/to/java/project
./bin/cocox validate --jsonl method_contexts.jsonl
```

Schema examples:

```bash
./bin/cocox schema method-context
```

By default, generated artifacts are written outside the analyzed project:

```text
./cocox_output/<project-name>/
  method_contexts.jsonl
  extraction_report.json
  Output_CallGraph_CHA.txt     when CHA is effectively used
  Output_CallGraph_RTA.txt     when RTA is effectively used
  method_context_failures.jsonl
```

Use `--output-dir` to choose an explicit destination:

```bash
./bin/cocox extract --project /path/to/java/project --output-dir ./results/project-name
```

Failure artifacts are written next to the normal outputs:

```text
method_context_failures.jsonl  when a discovered method cannot be contextualized
```

## API Usage

Add the dependency after installing the project locally:

```bash
mvn install
```

```xml
<dependency>
  <groupId>org.assertlab.cocox</groupId>
  <artifactId>analyzer-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Minimal Java API:

```java
import org.assertlab.cocox.CallGraphGenerator;
import org.assertlab.cocox.ContextExtractorService;
import org.assertlab.cocox.ContextRequest;
import org.assertlab.cocox.ExtractionReport;

import java.nio.file.Path;

class Example {
    public static void main(String[] args) throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("/path/to/java/project"))
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .callGraphAlgorithm(org.assertlab.cocox.CallGraphGenerator.Algorithm.NONE)
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
mvn compile exec:java -Dexec.mainClass=example.RunCoCoX
```

To analyze another project:

```bash
mvn compile exec:java \
  -Dexec.mainClass=example.RunCoCoX \
  -Dexec.args="/path/to/java/project"
```

## Static Analysis Boundaries

CoCoX does not perform dynamic analysis. It does not execute tests, run application code, observe runtime values, or resolve reflection dynamically.

Current static-analysis boundaries:

- source extraction uses Spoon; auto mode keeps no-classpath extraction as the coverage baseline and uses classpath-aware extraction when it is available and coverage-preserving;
- call graph extraction uses optional SootUp `CHA` or `RTA`;
- call graph quality depends on compiled class directories and classpath resolution;
- build-tool compilation is explicit via `--compile` or opportunistic through `--resolution auto` / `--call-graph auto`; otherwise CoCoX only reuses existing class files;
- reflection, proxies, generated code, Lombok, service loaders, and dependency injection can reduce precision, but common dynamic-feature hints are labeled in JSON;
- generated methods from Lombok/annotation processors are not visible unless generated source or bytecode is available.

The JSONL output schema is summarized in `schemas/README.md`. Field-test
results and current static-analysis limitations are recorded in
`docs/research/FIELD_TEST_RESULTS.md`. Documentation-context retrieval notes are recorded in
`docs/research/DOC_CONTEXT_RETRIEVAL_NOTES.md`.
