# CoCoMUT 🥥

<p align="center">
  <strong>Context Constructor for MUT.</strong>
</p>

<p align="center">
  <a href=""><img alt="Paper" src="https://img.shields.io/badge/📃-Arxiv-b31b1b?style=for-the-badge"></a>
  <a href=""><img alt="Demo" src="https://img.shields.io/badge/Demo-red?style=for-the-badge&logo=youtube&logoColor=white"></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0.txt"><img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge">
</p>

<p align="center">
  <a href="#quickstart">🚀 Quickstart</a> |
  <a href="docs/usage.md">📘 Usage</a> |
  <a href="schemas/README.md">🧾 JSONL schema</a> |
  <a href="#citation">✏️ Cite us!</a>
</p>

---

CoCoMUT extracts method-level context from Java repositories. For every method it
writes one JSONL record with source code, Javadoc, type context, documentation
metadata, provenance, and static bytecode call context.

It is designed for documentation related research:
static, reproducible, bytecode-backed, and explicit about failure modes.

## Why CoCoMUT?

- **Compiled-project extraction** over Java source plus project class files,
  conventional build output directories, or project JARs discovered in the
  checkout.
- **Stable method identity** using path, qualified type, erased parameter types,
  and erased return type.
- **Javadoc-aware context** for `@see`, `{@link ...}`, `{@inheritDoc}`,
  structured tags, documentation metrics, and referenced project symbols.
- **Static bytecode call context** for caller/callee edges and source joins when
  bytecode targets map deterministically to project source methods.
- **Research-friendly output** as JSONL plus an extraction report and a
  dependency-free web viewer.

## Quickstart

```bash
git clone https://github.com/assert-lab/CoCoMUT.git
cd CoCoMUT
./mvnw test
```

Run CoCoMUT on a Java project:

```bash
./bin/cocomut \
  --project /path/to/java/project \
  --scope entry-points \
  --source-set main \
  --allow-build
```

Use `--allow-build` only for trusted checkouts. For untrusted repositories,
compile elsewhere and pass explicit artifacts instead, for example
`--skip-build --class-output target/classes`.

The default output goes to:

```text
./cocomut_output/<project-name>-<path-hash>/method_contexts__<request-hash>.jsonl
```

Open the JSONL viewer:

```bash
python3 scripts/method_contexts_viewer.py ./cocomut_output
```

## Output At A Glance

Each JSONL row contains:

- method URI, signature, source, Javadoc, parameters, return type, annotations,
  thrown exceptions, and source position;
- type context, class Javadoc, hierarchy, fields, overloads, siblings, and
  documentation metrics;
- resolved Javadoc references with target kind, domain, and scope taxonomy;
- callers/callees from static bytecode analysis, with project source joins when
  the bytecode target maps to one unique source method;
- provenance fields describing backend mode, resolution confidence, failures,
  and selected target.

See [schemas/README.md](schemas/README.md) for the full schema.

## Documentation Map

| Topic | Where |
| --- | --- |
| CLI, build, API, viewer | [docs/usage.md](docs/usage.md) |
| JSONL schema | [schemas/README.md](schemas/README.md) |
| Example JSONL output | [examples/sample-output/minimal-method-context.jsonl](examples/sample-output/minimal-method-context.jsonl) |
| Method/type/package URIs | [docs/symbol-model.md](docs/symbol-model.md) |
| Javadoc reference policy | [docs/javadoc-reference-policy.md](docs/javadoc-reference-policy.md) |
| Agent instructions | [AGENTS.md](AGENTS.md) |
| Parallel worktrees | [docs/agents/PARALLEL_WORKFLOW.md](docs/agents/PARALLEL_WORKFLOW.md) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Machine-readable citation metadata | [CITATION.cff](CITATION.cff) |

## Repository Shape

```text
analyzer-core/   Java library and extraction API
cocomut-cli/     Picocli command-line application
analyzer-tests/  unit and integration tests
examples/        small API usage example
docs/            product notes and research-run reports
schemas/         JSON schema documentation
scripts/         release, viewer, and field-test helpers
```

## Citation

Publication metadata is still a placeholder and will be updated when the paper
is available.

```bibtex
@misc{cocomut2026,
  title        = {CoCoMUT: Context Constructor for MUT},
  author       = {{ASSERT Lab}},
  year         = {2026},
  howpublished = {\url{https://github.com/assert-lab/CoCoMUT}},
  note         = {Version 0.1.0}
}
```

## Status

CoCoMUT currently targets Java 17+ and performs static analysis only. It does not
execute application code or tests. By default it also does not execute
repository-controlled Maven or Gradle builds; use `--allow-build` only for
trusted checkouts, or `--externally-sandboxed-build` when a container/VM policy
is provided outside CoCoMUT. The analyzed project must provide usable project
bytecode in a conventional build layout, or use explicit artifact inputs such as
`--class-output` / `--project-jar` before extraction can succeed.
