# Known Issues

Context4DocuGen is intentionally static-analysis only. The issues below are known product boundaries, not dynamic-analysis TODOs.

## Source Model Backend

Method identity and source context now use Spoon in no-classpath mode. This is much stronger than the previous regex parser, but unresolved symbols remain possible when dependency jars or sibling modules are missing. JSON provenance records `source_backend=spoon`, `source_backend_mode=noclasspath`, and hierarchy/type-resolution confidence.

Spoon parsing degrades from whole-project parsing to source-root and file-level parsing. That keeps more repositories analyzable. The expanded 541-repository source-only sweep completed with one JSONL row per selected method, but very large repositories can still require bounded extraction controls.

Current builds write per-method failure artifacts when this happens:

```text
selected_method_failures.jsonl
method_context_failures.jsonl
```

For low-memory smoke tests, use `--max-methods N` and `--max-source-files N`.
The source-file cap is useful for quickly testing huge repositories, but it is
a sampling/control knob, not a full-repository coverage result.

## Call Graph Precision

Call graph extraction uses optional SootUp `CHA` or `RTA`. CHA is conservative and can over-approximate calls for inheritance, interfaces, callbacks, dependency injection, and framework-heavy code. RTA can reduce noise by considering instantiated classes, but it can still miss behavior created by reflection, framework wiring, or incomplete classpaths.

## Build and Classpath Sensitivity

Call graph quality depends on the analyzed project compiling and on class directories being discoverable. Multi-module Maven projects, Gradle projects, generated sources, Lombok, annotation processors, and unusual build layouts can reduce call-graph coverage.

Build-tool compilation is opt-in via `--compile`. Without it, C4DG reuses existing class files if present and otherwise continues in source-only mode when source files are available.

Very large repositories can be slow or memory-heavy during source modeling. Use
`--max-source-files N` and optionally `--max-methods N` for resource-safe smoke
runs. In the expanded field test, `lakesoul-io/LakeSoul`,
`kubernetes-client/java`, and `apache/incubator-seata` needed both caps to
complete as bounded 5,000-method runs.

## Dynamic Java Features

Reflection, proxies, service loaders, native methods, runtime code generation, and dependency-injection wiring are not resolved dynamically. C4DG detects common hints such as `Class.forName`, `Method.invoke`, `Proxy.newProxyInstance`, `ServiceLoader.load`, and DI-style annotations, then records them as `dynamic_features`.

## Selected CSV Compatibility

`method_uri` is the preferred selected-method key. Legacy selected CSVs with `focal_method|test_prefix|docstring|id` are still accepted for old PoCs, but that path is compatibility mode and may be ambiguous for overloaded methods.

## Packaging

The repository includes lightweight wrappers and a standalone shaded CLI jar:

```bash
./bin/c4dg extract --project /path/to/repo
scripts/build_release_jar.sh
java -jar dist/context4docugen-cli.jar --help
```

`mvn -DskipTests package` produces normal, sources, Javadocs, and shaded CLI jars. `scripts/build_release_jar.sh` copies the runnable shaded jar to `dist/context4docugen-cli.jar`.

## Field Testing

See `FIELD_TEST_RESULTS.md`.

Current baseline:

- 541 English, non-tutorial public Java repositories in source-only JSONL mode;
- 541 successful repositories;
- 2686556 identified methods and 2686556 generated JSONL rows;
- 100.00% method-to-context coverage for the selected run;
- 10 bounded retry runs, including 3 strict `--max-source-files 1500 --max-methods 5000` runs;
- bounded call-graph smoke test passed for both `CHA` and `RTA` on three compiled Maven repositories.
