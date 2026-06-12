# PoC: SelectedMethodLoader Should Use AST-Based Method Matching

This PoC demonstrates a selected-mode failure in Context4DocuGen's current method matching.

The issue is not SootUp. The failure happens earlier, in phase 2, before call graph generation. `SelectedMethodLoader` receives selected focal methods from `inputs_selected.csv`, then tries to parse and locate them in source files with regular expressions and text heuristics.

## Big Picture

Context4DocuGen has two method-selection modes:

1. **FULL mode**
   The tool scans a Java project and tries to identify methods itself.

2. **SELECTED mode**
   The user provides `inputs_selected.csv` with focal methods. This is the mode useful for documentation-dataset work, because an external miner can decide which method/Javadoc pairs should be enriched.

In selected mode, the pipeline is:

```text
inputs_selected.csv
  -> SelectedMethodLoader
  -> MethodInfo records
  -> CallGraphGenerator
  -> ContextExtractor
  -> JsonGenerator
  -> method_context_json/*.json
```

`SelectedMethodLoader` must recover the source file and line number for each focal method. If it cannot match a row, that selected method disappears from all later phases.

## Why This PoC Exists

The current loader uses regex/text matching:

```text
focal method text -> regex parse method declaration -> scan .java files -> score candidate line
```

That is fragile for valid Java declarations and method identities such as:

- package-private generic methods
- bounded generic type parameters
- constructors
- compact record constructors
- annotated declarations
- nested classes

This PoC keeps the fixture small but covers the main selected-method identity cases: a simple control method, a package-private generic method, an annotated method, a normal constructor, a nested-class method, and a compact record constructor.

## Fixture

The fixture project is a tiny external Maven project:

```text
fixture-project/
├── pom.xml
├── inputs_selected.csv
└── src/main/java/poc/SelectedMethodMatchingFixture.java
```

The selected CSV contains six focal rows:

```text
control_add                  expected to load today
generic_max                  currently loads, but exposes weak parameter metadata
constructor_label            valid Java constructor, currently missed
annotated_method             expected to load today
nested_value                 currently loads, but is attributed to the outer class
compact_record_constructor   valid Java compact constructor, currently missed
```

The package-private generic method is:

```java
<T extends Comparable<T>> T max(final T a, final T b) {
    return a.compareTo(b) >= 0 ? a : b;
}
```

This is valid Java, but it is hard to handle correctly with declaration regexes because:

- it starts with a type parameter rather than a visibility modifier;
- the type parameter itself contains nested angle brackets;
- it is package-private;
- parameters include `final` modifiers.

In the current run this row is located, but the generated JSON still shows weak parser behavior:

```json
"parameters": ["final", "final"]
```

An AST-backed loader should recover parameter names and types:

```json
[
  {"name": "a", "type": "T", "modifiers": ["final"]},
  {"name": "b", "type": "T", "modifiers": ["final"]}
]
```

The ordinary constructor row is also valid Java:

```java
public SelectedMethodMatchingFixture(String label) {
    this.label = label;
}
```

The current regex expects a return type, so constructors are not handled as first-class selected methods.

The compact record constructor is also valid Java 17:

```java
record RangeFixture(int start, int end) {
    RangeFixture {
        if (start > end) {
            throw new IllegalArgumentException("start > end");
        }
    }
}
```

It has neither an explicit return type nor an explicit parameter list, so it is another constructor-shape failure that a Java AST can represent but declaration regexes generally handle poorly.

The nested-class method currently loads:

```java
public static class Nested {
    public String value() {
        return "nested";
    }
}
```

However, `methods.csv` attributes it to:

```text
poc.SelectedMethodMatchingFixture
```

instead of the nested declaring class. This shows that selected matching must recover method identity, not only source-file identity.

## How To Run

From the repository root:

```bash
pocs/context4docugen-ast-method-matching/run_poc.sh
```

If your shell is currently inside `Context4DocuGen/`, run it through the parent path:

```bash
../pocs/context4docugen-ast-method-matching/run_poc.sh
```

The script:

1. compiles the tiny fixture project;
2. copies the regression test into `Context4DocuGen/analyzer-tests`;
3. runs the regression test through Maven's `analyzer-tests` module;
4. prints `methods.csv`;
5. lists generated JSON files.

The script intentionally exits with status `0` even when the regression test fails on the current implementation. A failing regression test is the expected PoC result until the bug is fixed.

## Maintainer-Friendly Reproduction

For an upstream issue or PR, prefer a JUnit regression test because it uses existing Context4DocuGen APIs.

This PoC includes:

```text
SelectedMethodLoaderAstMatchingRegressionTest.java
```

To use it in a Context4DocuGen checkout:

```bash
cp ../pocs/context4docugen-ast-method-matching/SelectedMethodLoaderAstMatchingRegressionTest.java \
  analyzer-tests/src/test/java/analyzer/SelectedMethodLoaderAstMatchingRegressionTest.java

mvn -Dmaven.repo.local=../.m2 -Dtest=SelectedMethodLoaderAstMatchingRegressionTest test
```

If running from the Context4DocuGen multi-module root, the more precise command is:

```bash
mvn -Dmaven.repo.local=../.m2 -pl analyzer-tests -am \
  -Dtest=SelectedMethodLoaderAstMatchingRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Expected current result:

```text
test failure: phase_2_methods_loaded is 4, expected 6
```

Expected result after the AST-based matching fix:

```text
both tests pass
```

## Verified Current Behavior

Verified with `run_poc.sh` on 2026-06-04:

```text
[SelectedMethodLoader] Could not locate focal method for row id=constructor_label
[SelectedMethodLoader] Could not locate focal method for row id=compact_record_constructor
phase_2_methods_loaded: 4
phase_5_files_generated: 4
```

Loaded rows:

```text
control_add
generic_max
annotated_method
nested_value
```

Missing rows:

```text
constructor_label
compact_record_constructor
```

Additional wrong identity:

```text
nested_value loads, but methods.csv says classname=poc.SelectedMethodMatchingFixture
```

The tool still reports overall success because at least one selected method loads. That is part of why this is dangerous for dataset work: selected rows can disappear unless the user inspects the warnings/counts.

## Expected Behavior After Fix

After replacing regex matching with AST-based matching, all valid selected rows should load and keep structurally correct method metadata:

```text
control_add
generic_max
constructor_label
annotated_method
nested_value
compact_record_constructor
```

The expected generated files would include:

```text
target_control_add__add.json
target_generic_max__max.json
target_constructor_label__SelectedMethodMatchingFixture.json
target_annotated_method__annotated.json
target_nested_value__value.json
target_compact_record_constructor__RangeFixture.json
```

The expected phase counts after the fix are:

```text
phase_2_methods_loaded: 6
phase_5_files_generated: 6
```

The specific correctness checks are:

```text
constructor_label is loaded
compact_record_constructor is loaded
nested_value is attributed to the nested declaring class
generic_max has structurally correct parameter metadata, not ["final", "final"]
```

## What Maintainers Should Do After Fixing

For an upstream PR, the PoC should become a self-contained regression test inside the Context4DocuGen repository.

Move the fixture project into test resources:

```text
analyzer-tests/src/test/resources/fixtures/selected-method-matching-project/
```

Move or adapt the regression test into:

```text
analyzer-tests/src/test/java/analyzer/SelectedMethodLoaderAstMatchingRegressionTest.java
```

Then run:

```bash
mvn -Dtest=SelectedMethodLoaderAstMatchingRegressionTest test
```

The test should fail before the fix and pass after the fix.

Optionally run the full suite:

```bash
mvn test
```

Success condition:

```text
All six selected declarations are converted into MethodInfo records,
all six produce JSON outputs,
and nested/generic/constructor declarations preserve their real Java structure.
```

## Suggested Fix Direction

Replace the current selected-mode matching logic with an AST-backed matcher:

```text
parse source files with Spoon / JavaParser / JDT
parse focal method snippet with the same parser
compare AST-derived method identity:
  - method or constructor name
  - declaring class when available
  - parameter count
  - resolved/normalized parameter types
  - normalized method body as tie-breaker
```

The important design change is this:

```text
Do not infer Java structure with regex.
Ask a Java parser for method/constructor declarations.
```

A good fix should also report selected rows that remain unmatched:

```text
selected_rows_total
selected_rows_loaded
selected_rows_unmatched
unmatched_ids
```

That would make failures auditable instead of easy to miss.
