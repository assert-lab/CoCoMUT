# Manual Audit Annotation Guidelines

Each annotator labels the same 200 sampled method-context records independently.
Use only these labels:

```text
PASS
FAIL
NA
```

Use `NA` only when the category is not applicable for the sampled method.

## Columns To Fill

```text
method_identity
doc_refs
call_context
inheritdoc
notes
```

The script computes `overall_correct`; annotators do not fill that field.

## `method_identity`

Mark `PASS` if the JSONL row points to the correct source method.

Check:

- `method_uri`
- source file
- class name
- method name
- parameters
- return type
- method source/code

Mark `FAIL` if the row is attached to the wrong source method, wrong overload,
wrong class, wrong source file, or wrong signature.

## `doc_refs`

Mark `PASS` if all applicable `@see`, `{@link ...}`, and `{@linkplain ...}`
references are correctly represented.

Check:

- project-local method references point to the correct method;
- project-local field references point to the correct field;
- project-local type references point to the correct type;
- external/JDK references are not wrongly mapped to project code;
- ambiguous references are not forced to a wrong target.

Use `NA` if the method/class Javadoc has no `@see`, `{@link ...}`, or
`{@linkplain ...}` references to inspect.

## `call_context`

Mark `PASS` if emitted source-level caller/callee links are correct.

Check:

- caller `method_uri` points to the correct project method;
- callee `method_uri` points to the correct project method;
- `target_uri` is present for bytecode targets;
- external/JDK/synthetic/unresolved targets are not incorrectly mapped to
  project source methods;
- `unresolved_reason` is plausible when `method_uri` is empty.

This checks correctness of emitted links, not full call-graph completeness.

## `inheritdoc`

Mark `PASS` if inherited-documentation handling is correct.

Check:

- the inherited candidate is the correct overridden/implemented method;
- the candidate comes from the right superclass or interface;
- CoCoMUT does not silently copy inherited documentation as if it were local
  text.

Use `NA` if there is no `{@inheritDoc}` and no inherited-documentation metadata
to inspect.

## Overall Correctness

The scoring script derives:

```text
overall_correct = YES
if method_identity == PASS
AND doc_refs in {PASS, NA}
AND call_context in {PASS, NA}
AND inheritdoc in {PASS, NA}
```

All other combinations produce `overall_correct = NO`.
