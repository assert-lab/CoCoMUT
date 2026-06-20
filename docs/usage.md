# CoCoX Usage

This page contains the longer operational notes that are intentionally kept out
of the README landing page.

## Build

Run the test suite:

```bash
./mvnw test
```

If Maven cannot write to your global `~/.m2`, use a local repository:

```bash
./mvnw -Dmaven.repo.local=.m2/repository test
```

Run only the test module and the required reactor modules:

```bash
./mvnw -pl analyzer-tests -am test
```

CoCoX requires Java 17 or newer at runtime and uses Maven compiler target
Java 17.

Build the standalone CLI jar:

```bash
scripts/build_release_jar.sh
```

The script runs tests, packages all modules, and writes:

```text
dist/cocox-cli.jar
```

## CLI

The CLI can be run through `bin/cocox` from the repository root:

```bash
./bin/cocox --project /path/to/java/project --scope entry-points --call-graph none
```

`bin/cocox` uses `dist/cocox-cli.jar` when it exists. If the jar has not been
built yet, it builds and runs the shaded development jar from
`cocox-cli/target/`.

You can also run the standalone jar directly:

```bash
java -jar dist/cocox-cli.jar \
  --project /path/to/java/project \
  --scope entry-points \
  --call-graph none
```

`cocox` has one public operation: extraction. Running `cocox` with the options
below is the extraction command; there is no extra `extract`, `validate`, or
`schema` command layer.

Run exact method-URI selection:

```bash
./bin/cocox \
  --project /path/to/java/project \
  --method-uri 'src/main/java/com/example/Hello.java#com.example.Hello.greet(java.lang.String):java.lang.String' \
  --call-graph none
```

Useful options:

```text
--scope all|entry-points       Method scope for source scanning: all extracts
                                every discovered method; entry-points keeps
                                public/protected API-like methods
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

`--resolution auto` attempts bounded build/classpath discovery when useful,
tries Spoon classpath-aware extraction only when the discovered classpath is
usable and coverage-preserving, and falls back to no-classpath source
extraction when compilation is unavailable, incomplete, too expensive, or loses
method coverage.

`--call-graph auto` asks SootUp for an RTA call graph when compiled class
directories are available. If bytecode is unavailable or unusable, CoCoX records
the call graph as unavailable and still emits source/Javadoc contexts.

For documentation datasets, prefer:

```bash
./bin/cocox \
  --project /path/to/java/project \
  --scope entry-points \
  --source-set main \
  --call-graph none
```

`--call-graph none` is the safest default for large documentation-mining runs:
it avoids build/classpath failures and focuses the output on source and Javadoc
context. Use `--call-graph auto`, `rta`, or `cha` when caller/callee context is
part of the study design.

`--source-set main` excludes public methods found under test, generated,
example, integration-test, or unknown source roots. Use `--source-set all` or
omit the flag to preserve the default behavior.

## Layered Selection

Layered selection is available when you do not want the whole repository:

```bash
./bin/cocox \
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
and `--package-uri`; see [symbol-model.md](symbol-model.md).

Examples:

```bash
./bin/cocox \
  --project /path/to/java/project \
  --type-uri 'src/main/java/org/example/Foo.java#org.example.Foo'

./bin/cocox \
  --project /path/to/java/project \
  --package-uri 'src/main/java/org/example/package-info.java#org.example'
```

## Output Directory

By default, generated artifacts are written outside the analyzed project under
`./cocox_output/<project-name>/`, relative to the directory where you run
`cocox`:

```text
./cocox_output/<project-name>/
  method_contexts.jsonl
  extraction_report.json
  Output_CallGraph_CHA.txt     when CHA is effectively used
  Output_CallGraph_RTA.txt     when RTA is effectively used
  method_context_failures.jsonl only when some methods fail context extraction
```

Use `--output-dir` to choose an explicit destination:

```bash
./bin/cocox --project /path/to/java/project --output-dir ./results/project-name
```

## JSONL Viewer

For manual inspection of generated method contexts, CoCoX ships a
dependency-free research viewer:

```bash
python3 scripts/method_contexts_viewer.py /path/to/method_contexts.jsonl
```

You can also pass an output directory; the viewer recursively finds `*.jsonl`
files:

```bash
python3 scripts/method_contexts_viewer.py /path/to/cocox_output
```

The viewer indexes JSONL by byte offset, so large outputs can be browsed
without loading the whole file into memory. It displays method source, Javadoc,
selection provenance, source context, Javadoc references, call graph context,
documentation metrics, and the raw record.

## API

Add the dependency after installing the project locally:

```bash
./mvnw install
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
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                .maxSourceFiles(500)
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
./mvnw install
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

CoCoX does not perform dynamic analysis. It does not execute tests, run
application code, observe runtime values, or resolve reflection dynamically.

Current static-analysis boundaries:

- source extraction uses Spoon; auto mode keeps no-classpath extraction as the
  coverage baseline and uses classpath-aware extraction when it is available and
  coverage-preserving;
- call graph extraction uses optional SootUp `CHA` or `RTA`;
- call graph quality depends on compiled class directories and classpath
  resolution;
- build-tool compilation is explicit via `--compile` or opportunistic through
  `--resolution auto` / `--call-graph auto`; otherwise CoCoX only reuses
  existing class files;
- reflection, proxies, generated code, Lombok, service loaders, and dependency
  injection can reduce precision, but common dynamic-feature hints are labeled
  in JSON;
- generated methods from Lombok/annotation processors are not visible unless
  generated source or bytecode is available.

## Method Identity

CoCoX identifies methods by URI.

Format:

```text
relative/path/ToFile.java#qualified.DeclaringType.method(erasedParamTypes):erasedReturnType
```

Example:

```text
src/main/java/com/example/Hello.java#com.example.Hello.greet(java.lang.String):java.lang.String
```

This is important because overloaded methods need more than a method name. A
reliable locator needs:

- the `.java` source path;
- the declaring type and method/constructor name;
- the erased parameter types;
- the erased return type.

For the broader symbol model, including `type_uri` and `package_uri` selection,
see [symbol-model.md](symbol-model.md).
