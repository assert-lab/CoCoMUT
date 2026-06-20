# Agent Instructions

This file is the shared operating guide for Codex or other coding agents
working on CoCoX. Keep it stable, technical, and repo-specific. Put temporary
branch status in `docs/agents/CURRENT_STATE.md` instead.

## Project Purpose

CoCoX, Code Context Extractor, is a static Java context extraction tool for
documentation-mining research and reusable Java repository analysis.

The tool combines:

- Spoon for source, Javadoc, symbols, method/type/package selection, and
  source-level context;
- optional SootUp CHA/RTA call graphs when compiled bytecode is available;
- JSONL output designed for downstream empirical analysis and model prompts.

CoCoX must remain a product-style tool, not an experiment dump. Prefer small,
documented, deterministic features over project-specific heuristics.

## Non-Negotiable Invariants

- Static analysis only. Do not add dynamic execution of analyzed projects.
- Do not add Apache Commons Lang, OE25, or benchmark-specific rules.
- Do not fake source identities. If a source method cannot be resolved
  deterministically, leave `method_uri` empty and explain why.
- Prefer deterministic matching. Do not use probabilistic, fuzzy, or score-only
  matching for method identity.
- If several source candidates remain, emit `candidate_method_uris` and mark the
  edge/reference ambiguous.
- Keep generated analysis artifacts out of analyzed repositories by default.
- Do not delete or rewrite existing experiment folders unless the user
  explicitly asks for it.
- Do not commit generated test output such as `analyzer-tests/cocox_output/`.

## Public Entry Points

The main user-facing entry point is `cocox extract`. Running `cocox` without a
subcommand should behave like extraction.

Useful local entry points:

```bash
./bin/cocox --project /path/to/java/repo --source-set main --scope all
java -jar dist/cocox-cli.jar --project /path/to/java/repo --source-set main
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

CoCoX source-backed URIs are documented in `docs/symbol-model.md`.

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
CoCoX joins them only when the match is deterministic and unique.

Call-edge identity fields:

- `target_uri`: bytecode-level URI derived from the SootUp signature. Present
  for every call graph edge.
- `method_uri`: source-level CoCoX method URI. Present only when a unique
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
- `method_uri`: can CoCoX attach project source/Javadoc context to that target?

## Javadoc Reference Model

CoCoX follows official Javadoc syntax, not project-specific style.

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

CoCoX supports layered selection:

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

Call graph and JSON output changes:

```bash
./mvnw -q -pl analyzer-tests -am \
  -Dtest=CallGraphGeneratorTest,JsonGeneratorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Schema sanity:

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

The local pre-commit hook may attempt `mvn formatter:validate` even when that
plugin is not configured. If it fails for plugin-resolution reasons after tests
pass, record that fact in the final message and commit with `--no-verify`.

## Git and Worktree Hygiene

Use one branch or worktree per task. Avoid multiple agents editing the same
physical checkout.

Recommended sibling worktree layout from the parent directory:

```bash
git -C Code-Context-Extractor worktree add ../cocox-task-callgraph -b task/callgraph main
git -C Code-Context-Extractor worktree add ../cocox-task-docs -b task/docs main
git -C Code-Context-Extractor worktree add ../cocox-task-viewer -b task/viewer main
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

