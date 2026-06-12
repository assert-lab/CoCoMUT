# PoC: Undergrad Issues U1, U2, U3

This PoC package contains three regression tests for the undergrad-scoped Context4DocuGen issues:

- U1: parameter extraction should preserve parameter names, types, and modifiers.
- U2: generated JSON should include provenance/confidence metadata.
- U3: unsafe selected IDs should not silently break JSON generation while the pipeline reports success.

## Big Picture

The tests run Context4DocuGen in SELECTED mode against tiny Maven fixture projects.

```text
inputs_selected.csv
  -> SelectedMethodLoader
  -> MethodInfo records
  -> CallGraphGenerator
  -> ContextExtractor
  -> JsonGenerator
  -> method_context_json/*.json
```

The tests are intended to fail on the current implementation and pass after the issues are fixed.

## Fixtures

```text
fixture-parameter-provenance-project/
  src/main/java/poc/UndergradIssueFixture.java
  inputs_selected.csv

fixture-unsafe-id-project/
  src/main/java/poc/UnsafeIdFixture.java
  inputs_selected.csv
```

## U1: Fix Parameter Extraction

The fixture method is:

```java
public <T> T choose(final T first, T second) {
    return first != null ? first : second;
}
```

Expected JSON:

```json
"parameters": [
  { "name": "first", "type": "T", "modifiers": ["final"] },
  { "name": "second", "type": "T", "modifiers": [] }
]
```

Current behavior is expected to fail because parameters are emitted as raw strings, and `final` can be mistaken as the parameter type.

## U2: Add Provenance Metadata

Expected JSON should include a top-level `provenance` object such as:

```json
"provenance": {
  "method_source": "selected_csv",
  "method_matching": "regex",
  "javadoc_extraction": "line_search",
  "call_graph": "SootUp CHA",
  "compiled_project": true,
  "context_confidence": {
    "method_body": "medium",
    "javadoc": "medium",
    "callers": "approximate",
    "callees": "approximate"
  }
}
```

Current behavior is expected to fail because JSON contains `metadata` but no `provenance` object.

## U3: Report JSON Generation Failures and Sanitize Unsafe IDs

The selected CSV intentionally uses an unsafe ID:

```text
src/main/java/poc/UnsafeIdFixture.java::x
```

Current filename generation sanitizes only the method name, not the method ID, so `/` inside the ID can be interpreted as directories. If JSON writing fails, the pipeline should not silently report success.

Expected behavior after fix:

- either sanitize the selected ID and generate one JSON file,
- or report the generation failure clearly and avoid final `SUCCESS`.

## How to Run

Put this folder at:

```text
pocs/context4docugen-undergrad-pocs/
```

from the same parent directory that contains:

```text
Context4DocuGen/
```

Then run:

```bash
pocs/context4docugen-undergrad-pocs/run_poc.sh
```

The script copies the JUnit test into:

```text
Context4DocuGen/analyzer-tests/src/test/java/analyzer/UndergradIssuesRegressionTest.java
```

and runs:

```bash
mvn -q -Dmaven.repo.local=../.m2 -pl analyzer-tests -am \
  -Dtest=UndergradIssuesRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

The script intentionally exits with status `0` even when these regression tests fail on the current implementation. Failing tests are the expected PoC result until U1/U2/U3 are fixed.

## Expected Current Result

The tests should fail on the current implementation:

- U1 fails because parameters are raw strings instead of objects with `name`, `type`, and `modifiers`.
- U2 fails because no top-level `provenance` object is emitted.
- U3 fails because unsafe IDs can produce zero JSON files while the pipeline still reports success.

## Acceptance Criteria

U1:

- parameter names are correct;
- parameter types are correct;
- modifiers such as `final` are not mistaken for parameter types;
- generics are covered by the test.

U2:

- every generated JSON contains a `provenance` object;
- provenance distinguishes selected CSV source, matching strategy, Javadoc extraction, call graph source, compilation status, and confidence levels.

U3:

- unsafe IDs cannot silently break output;
- failed JSON writes are counted/reported;
- final status is not `SUCCESS` when every JSON write fails.
