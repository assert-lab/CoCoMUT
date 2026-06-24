# ISSTA Evaluation Plan

> **Deprecated plan. Do not follow this evaluation plan.**
>
> This document is kept only as a historical draft from an earlier evaluation
> direction. We will not use this plan for the paper or the artifact evaluation.
> Future evaluation work should be written in a new document that matches the
> current compiled-bytecode requirement and the final study design.

CoCoMUT does not have a complete oracle for arbitrary Java repositories: for a
real project, there is usually no trusted external file that says exactly which
methods, documentation links, bytecode targets, and source joins should appear
in the output. The evaluation should therefore separate claims that can be
checked exactly from claims that require bounded empirical evidence.

## Evaluation Claims

1. **Correctness on controlled programs.** For programs whose source and bytecode
   behavior are intentionally constructed, CoCoMUT emits the expected method
   identities, source metadata, Javadoc references, and call-edge joins.
2. **Robustness on real repositories.** For a representative corpus of Java
   projects, CoCoMUT either emits schema-valid JSONL from a compiled project or
   reports a controlled build/analysis failure. It should not crash, hang
   indefinitely, write into the analyzed checkout, or silently degrade to
   source-only success.
3. **Precision of source joins.** When bytecode call targets cannot be joined to
   one unique project source method, CoCoMUT should keep bytecode identity and
   leave source identity empty instead of guessing.

These claims are intentionally narrower than "perfect context extraction for
all Java programs." They are testable and defensible for an ISSTA artifact.

## RQs And Metrics

**RQ1: Can CoCoMUT run robustly on compiled real-world projects?**

Report, per project:

- build system, compilation attempt, compilation status, class output count, and
  dependency JAR count;
- extraction status, timeout status, duration, max RSS if collected, JSONL row
  count, and failure codes;
- schema-valid row count and malformed row count;
- generated artifacts and whether any output was written inside the analyzed
  checkout.

Primary metrics: project success rate under the compiled-bytecode requirement,
controlled-failure rate, timeout rate, median/p95 runtime, and median/p95 rows
per successful project.

**RQ2: Are method identities and source fields correct on oracle fixtures?**

Create a versioned fixture suite with hand-authored expected JSON for:

- overloaded methods, constructors, nested classes, enums, records, generics,
  varargs, arrays, inherited methods, and visibility filters;
- source-set filtering for `main`, `test`, generated, examples, and unknown
  roots;
- method URI, type URI, package URI, line number, signature, erased parameter
  types, erased return type, thrown exceptions, annotations, source code, and
  class context.

Primary metrics: exact-match precision/recall for expected method URIs,
field-level exact-match accuracy for required fields, and filter accuracy.

**RQ3: Are Javadoc references resolved correctly on oracle fixtures?**

Create fixtures with official doc-comment reference forms:

- `@see`, `{@link ...}`, `{@linkplain ...}`, `{@inheritDoc}`, `@param`,
  `@return`, `@throws`, `@since`, `@deprecated`, `@apiNote`, `@implSpec`, and
  `@implNote`;
- method, field, type, package, URL, plain-text, inherited, ambiguous, external,
  and unresolved references.

Primary metrics: precision/recall for reference detection, exact-match accuracy
for resolved project targets, and correct unresolved/ambiguous taxonomy.

**RQ4: Are bytecode call targets and source joins correct on oracle fixtures?**

Create compiled fixtures with known calls:

- direct calls, overloads, constructors, inherited calls, interface dispatch,
  static calls, lambdas, string concatenation invokedynamic, nested classes,
  generic erasure, JDK calls, external library calls, and unresolved project
  methods outside the selected source scope;
- expected bytecode `target_uri` for every serialized edge;
- expected source `method_uri` only when a unique project source method exists.

Primary metrics: `target_uri` coverage, project-source join precision/recall,
ambiguous-edge correctness, unresolved-reason correctness, and no false source
joins for JDK/external/compiler-generated targets.

**RQ5: What errors appear in real repositories without a full oracle?**

Use stratified manual audit instead of pretending the full corpus has an oracle.
Sample rows by:

- project size and build system;
- source backend mode;
- call-edge `target_kind`;
- call-edge `resolution` and `unresolved_reason`;
- Javadoc reference kind and resolution;
- success/failure code.

Two annotators should independently inspect a fixed sample, resolve
disagreements, and report agreement plus an error taxonomy. The audit should
estimate precision for resolved source joins and Javadoc references, not recall
over the full repository.

## Robustness Tests

Run a compile-required corpus study on:

- OE25 projects;
- a representative sample from the mined Java repository CSV;
- a small set of bytecode/JAR-only or precompiled layouts;
- intentionally invalid projects that should fail cleanly.

For each project, record a single mandatory bytecode-backed attempt. Do not
count source-only extraction as success. Failure categories should distinguish:

- clone failure;
- unsupported or missing build layout;
- compilation failure;
- missing class output after compilation;
- static bytecode initialization failure;
- source parsing failure;
- selection removed all methods;
- timeout or resource exhaustion;
- malformed output.

The expected robustness property is not that every repository succeeds. The
property is that every target ends in either valid output from compiled bytecode
or a controlled, explainable failure.

## Metamorphic And Invariant Tests

Add automated checks that do not need a full oracle:

- every JSONL row parses and validates against the schema;
- every method URI is unique inside one output file;
- every call edge has `target_uri`;
- `method_uri` on a call edge implies a project-method target and a matching
  method row in the same extraction when the target is in scope;
- ambiguous edges carry candidates and do not choose one candidate as the
  canonical method URI;
- exact method/package/type selectors return subsets of the all-method output;
- `--source-set main` is a subset of `--source-set all`;
- repeated runs over the same checkout produce stable method URIs and stable
  deterministic fields;
- generated output is outside the analyzed checkout unless the user explicitly
  configured otherwise;
- a project with no compiled artifacts fails before JSONL success.

## Paper Wording

Recommended framing:

> Because arbitrary Java repositories do not provide ground-truth context
> labels, we evaluate CoCoMUT with a mixed oracle strategy: exact hand-written
> oracles for controlled Java fixtures, metamorphic invariants that must hold
> for any repository, a compile-required real-project robustness study, and a
> stratified manual audit of resolved references and bytecode-to-source joins.

Avoid claiming a global recall number for arbitrary real repositories unless a
separate manually annotated sample supports that specific claim.

## Artifacts To Produce

- `analyzer-tests` oracle fixtures with expected JSON snippets or expected TSV
  tables;
- a schema/invariant checker script for generated JSONL;
- a robustness runner that records one mandatory bytecode-backed attempt per
  project;
- an audit spreadsheet template with row ID, project, method URI, inspected
  field, expected value, observed value, verdict, and notes;
- a final evaluation report with raw counts, confidence intervals for audited
  precision where applicable, and a failure taxonomy.
