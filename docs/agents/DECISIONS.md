# Technical Decisions

This file records durable engineering decisions that are easy to forget during
parallel work. It complements `AGENTS.md`; if a decision becomes a permanent
agent rule, copy the short version there too.

## Deterministic Identity Matching

CoCoMUT must resolve source identities deterministically.

Decision:

- no probabilistic matching for `method_uri`;
- no score-only matching for source identity;
- no fuzzy fallback that silently chooses one candidate;
- ambiguity is explicit output, not an internal detail.

Rationale:

The output is used for empirical studies and documentation-mining datasets.
False positive context is often worse than missing context because it silently
pollutes downstream labels.

## Source URI Versus Bytecode URI

Decision:

- `method_uri` is the canonical source-level method identity.
- `target_uri` is the canonical bytecode-level call graph target identity.
- all SootUp call graph edges should have `target_uri`.
- only uniquely joined project-source edges should have `method_uri`.

Rationale:

SootUp and Spoon operate at different abstraction levels. A JDK method,
dependency method, synthetic method, or lambda bytecode target can be a valid
call graph node without being a source method extracted from the target project.

Example:

```json
{
  "method_uri": "",
  "target_uri": "bytecode://java.util.Objects.requireNonNull(java.lang.Object):java.lang.Object",
  "target_kind": "jdk_method"
}
```

## No Line Number In Canonical Method URI

Decision:

Do not add source line numbers to canonical `method_uri`.

Rationale:

Line numbers help debugging but are unstable across formatting changes,
comments, imports, and nearby edits. They are already emitted as metadata and
can be used for inspection without becoming part of identity.

## External Documentation Retrieval

Decision:

Do not fetch external JDK/library source or Javadoc text by default.

Rationale:

External documentation retrieval requires source/Javadoc jars, JDK version
alignment, dependency resolution, and caching. The current product keeps
external references symbol-level and documents this limitation.

## Experiment Folders

Decision:

Existing folders under `experiments/` are historical artifacts. Do not delete
or regenerate them unless explicitly asked.

Rationale:

Several reports and manual inspections depend on those folders. Losing them
breaks reproducibility and creates confusion between old and new runs.

## Schema Changes Require Documentation

Decision:

Any schema-visible field change must update:

- `schemas/method-context.schema.json`;
- `schemas/README.md`;
- relevant product/research docs;
- tests;
- sample JSONL if useful.

Rationale:

Downstream users depend on JSONL field names and semantics. Schema drift without
docs makes the tool hard to use as a research artifact.
