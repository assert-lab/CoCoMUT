# CoCoX 🥥

<p align="center">
  <strong>Code Context Extractor for Java.</strong>
</p>

<p align="center">
  <a href="https://www.apache.org/licenses/LICENSE-2.0.txt"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-17%2B-orange">
</p>

<p align="center">
  <a href="#quickstart">🚀 Quickstart</a> |
  <a href="docs/usage.md">📘 Usage</a> |
  <a href="schemas/README.md">🧾 JSONL schema</a> |
  <a href="CITATION.cff">✏️ Cite us!</a>
</p>

---

CoCoX extracts method-level context from Java repositories. For every method it
writes one JSONL record with source code, Javadoc, type context, documentation
metadata, provenance, and optional call-graph context.

It is designed for documentation mining and documentation-generation research:
static, reproducible, source-first, and explicit about failure modes.

## Why CoCoX?

- **Source-first extraction** with Spoon, including no-classpath fallback for
  imperfect public repositories.
- **Stable method identity** using path, qualified type, erased parameter types,
  and erased return type.
- **Javadoc-aware context** for `@see`, `{@link ...}`, `{@inheritDoc}`,
  structured tags, documentation metrics, and referenced project symbols.
- **Optional call graph** through SootUp CHA/RTA when bytecode is available.
- **Research-friendly output** as JSONL plus an extraction report and a
  dependency-free web viewer.

## Quickstart

```bash
git clone https://github.com/assert-lab/Code-Context-Extractor.git
cd Code-Context-Extractor
./mvnw test
```

Run CoCoX on a Java project:

```bash
./bin/cocox \
  --project /path/to/java/project \
  --scope entry-points \
  --source-set main \
  --call-graph none
```

The default output goes to:

```text
./cocox_output/<project-name>/method_contexts.jsonl
```

Open the JSONL viewer:

```bash
python3 scripts/method_contexts_viewer.py ./cocox_output
```

## Output At A Glance

Each JSONL row contains:

- method URI, signature, source, Javadoc, parameters, return type, annotations,
  thrown exceptions, and source position;
- type context, class Javadoc, hierarchy, fields, overloads, siblings, and
  documentation metrics;
- resolved Javadoc references with target kind, domain, and scope taxonomy;
- optional callers/callees when bytecode call-graph extraction is available;
- provenance fields describing backend mode, resolution confidence, failures,
  and selected target.

See [schemas/README.md](schemas/README.md) for the full schema.

## Documentation Map

| Topic | Where |
| --- | --- |
| CLI, build, API, viewer | [docs/usage.md](docs/usage.md) |
| JSONL schema | [schemas/README.md](schemas/README.md) |
| Method/type/package URIs | [docs/symbol-model.md](docs/symbol-model.md) |
| Javadoc reference policy | [docs/javadoc-reference-policy.md](docs/javadoc-reference-policy.md) |
| OE25 plus representative study | [docs/oe25-plus-representative-study.md](docs/oe25-plus-representative-study.md) |
| Field-test notes | [docs/research/FIELD_TEST_RESULTS.md](docs/research/FIELD_TEST_RESULTS.md) |
| Documentation-context notes | [docs/research/DOC_CONTEXT_RETRIEVAL_NOTES.md](docs/research/DOC_CONTEXT_RETRIEVAL_NOTES.md) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Citation | [CITATION.cff](CITATION.cff) |

## Repository Shape

```text
analyzer-core/   Java library and extraction API
cocox-cli/       Picocli command-line application
analyzer-tests/  unit and integration tests
examples/        small API usage example
docs/            product notes and research-run reports
schemas/         JSON schema documentation
scripts/         release, viewer, and field-test helpers
```

## Status

CoCoX currently targets Java 17+ and performs static analysis only. It does not
execute the analyzed program. When classpath or bytecode is unavailable, it
continues with source/Javadoc extraction and records that provenance in the
output.
