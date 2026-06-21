# Agent Instructions

This file is the shared operating guide for Codex or other coding agents
working on CoCoMUT. Keep it stable, technical, and repo-specific. Put temporary
branch status in `docs/agents/CURRENT_STATE.md` instead.

## Project Purpose

CoCoMUT, Context Construction for MUT, is a static Java context extraction tool for
documentation-mining research and reusable Java repository analysis.

CoCoMUT is not a generic Java static-analysis framework, not a build tool, and
not a benchmark-specific script collection. New work should strengthen the
source/Javadoc/context extraction product rather than expanding the repository
into unrelated static-analysis infrastructure.

The tool combines:

- Spoon for source, Javadoc, symbols, method/type/package selection, and
  source-level context;
- optional SootUp CHA/RTA call graphs when compiled bytecode is available;
- JSONL output designed for downstream empirical analysis and model prompts.

CoCoMUT must remain a product-style tool, not an experiment dump. Prefer small,
documented, deterministic features over project-specific heuristics.

## Protected Product Paths

Fixes in one subsystem must not quietly break another product path. When a
change touches shared models, schema fields, project resolution, or extraction
control flow, consider all of these paths:

- default source and Javadoc extraction;
- Spoon no-classpath fallback;
- classpath-aware source extraction when build/classpath evidence is available;
- optional SootUp CHA/RTA call graph extraction;
- CLI extraction;
- Java API extraction;
- JSONL schema and sample output;
- research viewer and script compatibility when fields are user-facing.

Do not optimize one path by weakening another unless the tradeoff is explicit,
documented, and tested.

## Non-Negotiable Invariants

- Static analysis only. Do not add dynamic execution of analyzed projects.
- Do not add Apache Commons Lang, OE25, or benchmark-specific rules.
- Preserve correctness before coverage. Do not increase match counts by
  guessing source identities.
- Do not fake source identities. If a source method cannot be resolved
  deterministically, leave `method_uri` empty and explain why.
- Prefer deterministic matching. Do not use probabilistic, fuzzy, or score-only
  matching for method identity.
- If several source candidates remain, emit `candidate_method_uris` and mark the
  edge/reference ambiguous.
- Keep generated analysis artifacts out of analyzed repositories by default.
- Do not delete or rewrite existing experiment folders unless the user
  explicitly asks for it.
- Do not commit generated test output such as `analyzer-tests/cocomut_output/`.

## Engineering Quality Rules

- Keep the implementation small, readable, and testable.
- Do not add fragile project-specific patches, dead code, unused abstractions,
  or complicated fallback paths without evidence.
- Prefer one clear release path over many permanent semantic variants.
- Diagnostic switches are acceptable when they validate or explain the intended
  behavior. Avoid long-lived flags that create multiple incompatible meanings
  for the same output field.
- Comment important extraction logic where source/bytecode identity, Javadoc
  resolution, fallback behavior, cache lifetime, or schema semantics are not
  obvious from the local code.
- Prefer compact comments beside the implementation over detached design notes
  when the comment explains why a matching rule, fallback, or memory policy
  exists.
- Keep public APIs narrow. CLI and API layers should not know Spoon `Ct*`
  internals or SootUp implementation details. Hide those behind internal
  adapters and product-level request/result objects.

## Public Entry Points

The main user-facing entry point is `cocomut extract`. Running `cocomut` without a
subcommand should behave like extraction.

Useful local entry points:

```bash
./bin/cocomut --project /path/to/java/repo --source-set main --scope all
java -jar dist/cocomut-cli.jar --project /path/to/java/repo --source-set main
```

API usage should go through the public service/request model, not direct
orchestrator internals. See:

- `docs/usage.md`
- `examples/api/`

## Output Model

The primary output is JSONL. Per-method JSON files and legacy CSV workflows
should not be reintroduced unless there is a deliberate product decision.

Schema documentation lives in:

- `schemas/README.md`
- `schemas/method-context.schema.json`

When changing output fields:

1. update the Java emitter;
2. update the JSON schema;
3. update `schemas/README.md`;
4. update a sample in `examples/sample-output/` if schema-visible output
   changes;
5. add or update tests.

## URI Model

CoCoMUT source-backed URIs are documented in `docs/symbol-model.md`.

Canonical source identities:

```text
method_uri  = relative/path/Foo.java#pkg.Foo.method(erased.Param):erased.Return
type_uri    = relative/path/Foo.java#pkg.Foo
package_uri = relative/path/package-info.java#pkg
```

Important rule:

```text
method_uri is source-backed.
target_uri is bytecode-backed.
method_uri implies target_uri for call graph edges.
target_uri does not imply method_uri.
```

Do not put line numbers into canonical `method_uri`. Line numbers are useful
debug metadata, but they make identifiers unstable across commits and
formatting-only changes.

## Call Graph Model

SootUp reports bytecode-level signatures. Spoon reports source-level methods.
CoCoMUT joins them only when the match is deterministic and unique.

Call-edge identity fields:

- `target_uri`: bytecode-level URI derived from the SootUp signature. Present
  for every call graph edge.
- `method_uri`: source-level CoCoMUT method URI. Present only when a unique
  project source method is identified.
- `target_kind`: taxonomy for the bytecode target.
- `resolution`: how the edge was resolved or why it stayed unresolved.
- `candidate_method_uris`: source candidates when the edge is ambiguous.
- `unresolved_reason`: deterministic reason for no source `method_uri`.

The `target_kind` taxonomy is documented in
`schemas/README.md#call-graph-target-taxonomy`.

Call-edge resolution work is documented in:

- `docs/research/CALL_EDGE_RESOLUTION_REPORT.md`

Do not collapse `target_uri` and `method_uri`. They answer different questions:

- `target_uri`: what bytecode target did SootUp report?
- `method_uri`: can CoCoMUT attach project source/Javadoc context to that target?

## Javadoc Reference Model

CoCoMUT follows official Javadoc syntax, not project-specific style.

Reference docs:

- `docs/javadoc-reference-policy.md`
- `docs/symbol-model.md`
- `docs/research/DOC_CONTEXT_RETRIEVAL_NOTES.md`

Important distinction:

- `reference_target_kind` is for Javadoc references such as `@see` and
  `{@link ...}`.
- `target_kind` is for SootUp call graph edges.

Do not mix these taxonomies.

External JDK/library Javadoc text is intentionally not fetched. External
references can be symbol-level metadata only unless there is a deliberate,
well-tested source/Javadoc-jar feature.

## Source and Build Resolution

Product direction:

- if build/classpath is available, use classpath-aware source extraction and
  optional SootUp call graph;
- if build/classpath fails or is too expensive, fall back to Spoon no-classpath
  source extraction and mark call graph unavailable when needed.

Do not force a repository to compile for source/Javadoc extraction. Many public
repositories need unavailable SDKs, services, generated code, or special local
setup.

## Selection Semantics

CoCoMUT supports layered selection:

- project;
- package;
- type/class;
- method;
- visibility;
- source set;
- include/exclude path globs;
- bounded extraction controls.

Selection output should still be JSONL. A smaller target simply means fewer
rows.

## Testing Expectations

Before finalizing code changes, run the smallest relevant test first, then the
broader suite when feasible.

General:

```bash
./mvnw -q test
```

Risk-based testing:

### Source Extraction Changes

Run source-model tests and cover both no-classpath and classpath-aware behavior
when the change could affect project parsing or symbol resolution.

Suggested commands:

```bash
./mvnw -q -pl analyzer-tests -am \
  -Dtest=SourceModelEdgeCaseTest,AnalyzerFacadeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

If classpath/build behavior changes, also test a small Maven fixture or known
compiled repository with:

```bash
./bin/cocomut --project analyzer-tests/src/test/resources/fixtures/minimal-maven-project \
  --compile --resolution auto --call-graph none --source-set main --scope all \
  --output-dir /tmp/cocomut-minimal-source-test
```

### Javadoc Extraction Or Reference Changes

Run the tests that cover Javadoc tags, `@see`, `{@link ...}`, and
`{@inheritDoc}`. Inspect at least one generated JSONL row when the output shape
or semantics changes.

Suggested commands:

```bash
./mvnw -q -pl analyzer-tests -am \
  -Dtest=SourceModelEdgeCaseTest,RobustExtractionRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

When adding Javadoc reference behavior, confirm it follows official Javadoc
syntax and update:

- `docs/javadoc-reference-policy.md`;
- `docs/symbol-model.md`;
- `schemas/README.md` if fields or semantics change.

### Call Graph Or SootUp-to-Spoon Join Changes

Run call graph and JSON output tests:

```bash
./mvnw -q -pl analyzer-tests -am \
  -Dtest=CallGraphGeneratorTest,JsonGeneratorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

If the join logic changes, test a compiled repository with `--call-graph auto`
and inspect the distribution of:

- `target_kind`;
- `resolution`;
- `unresolved_reason`;
- `method_uri` presence versus `target_uri` presence.

Do not claim aggregate matching-rate improvements from a single repository.
Use a broader field run before making global claims.

### Schema Or JSONL Output Changes

Any schema-visible output change requires:

- Java emitter update;
- schema update;
- schema docs update;
- sample JSONL update if useful;
- tests for the new field or semantic.

Run schema sanity:

```bash
python3 -m json.tool schemas/method-context.schema.json >/dev/null
python3 - <<'PY'
import json
from pathlib import Path
for line in Path("examples/sample-output/minimal-method-context.jsonl").read_text().splitlines():
    if line.strip():
        json.loads(line)
print("sample JSONL parses")
PY
```

If the field is user-facing in the viewer, update `scripts/method_contexts_viewer.py`
or document why it is intentionally hidden.

### CLI Or API Changes

CLI and API should expose equivalent extraction functionality unless there is a
documented reason not to. When changing request options, update:

- `docs/usage.md`;
- `examples/api/` if API examples are affected;
- CLI tests or command examples where present.

Run at least one CLI smoke extraction and one API-level test when feasible.

### Experiment-Folder Safety

Existing folders under `experiments/` are historical artifacts. They may contain
manual reruns, representative subsets, or evidence used in reports. Do not
delete, overwrite, rename, or regenerate them unless the user explicitly asks.
When a new experiment is needed, create a new timestamped or descriptive folder.

The local pre-commit hook may attempt `mvn formatter:validate` even when that
plugin is not configured. If it fails for plugin-resolution reasons after tests
pass, record that fact in the final message and commit with `--no-verify`.

## Git and Worktree Hygiene

Use one branch or worktree per task. Avoid multiple agents editing the same
physical checkout.

Recommended sibling worktree layout from the parent directory:

```bash
git -C Code-Context-Extractor worktree add ../cocomut-task-callgraph -b task/callgraph main
git -C Code-Context-Extractor worktree add ../cocomut-task-docs -b task/docs main
git -C Code-Context-Extractor worktree add ../cocomut-task-viewer -b task/viewer main
```

Rules:

- one Codex instance per worktree;
- one branch per worktree;
- one coherent PR per branch;
- rebase/merge from `main` before opening a PR if multiple branches touch the
  same files;
- avoid parallel edits to `README.md`, schema files, and central Java model
  classes unless coordination is explicit.

Use `docs/agents/CURRENT_STATE.md` for active coordination among agents.
