# CoCoMUT 🥥

<p align="center">
  <strong>Context Constructor for MUT.</strong>
</p>

<p align="center">
  <a href="https://arxiv.org/abs/2606.31971"><img alt="Paper" src="https://img.shields.io/badge/📃-Arxiv-b31b1b?style=for-the-badge"></a>
  <a href="https://youtu.be/RCUzkCQjG30"><img alt="Demo" src="https://img.shields.io/badge/Demo-red?style=for-the-badge&logo=youtube&logoColor=white"></a>
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
static, reproducible, project-bytecode aware, and explicit about failure modes.

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
  warnings, and selected target.

If the SootUp call graph is generated but some selected source methods do not
receive matched bytecode call-graph projections, CoCoMUT records that as a
run warning. The call graph remains available, and resolved caller/callee edges
are still emitted. If none of the selected methods match project bytecode, the
run is `PARTIAL`: source records are retained, but they do not contain usable
method-level call context.

See [schemas/README.md](schemas/README.md) for the full schema.

## Documentation Map

| Topic | Where |
| --- | --- |
| CLI, build, API, viewer | [docs/usage.md](docs/usage.md) |
| JSONL schema | [schemas/README.md](schemas/README.md) |
| Example JSONL output | [examples/sample-output/minimal-method-context.jsonl](examples/sample-output/minimal-method-context.jsonl) |
| Method/type/package URIs | [docs/symbol-model.md](docs/symbol-model.md) |
| Javadoc reference policy | [docs/javadoc-reference-policy.md](docs/javadoc-reference-policy.md) |
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

If you use CoCoMUT in academic work, please cite the paper:

```bibtex
@misc{botta2026cocomut,
  title        = {CoCoMUT: A Tool for Code-Context Mining and Automated Dataset Generation},
  author       = {Botta, Alessandro and Garisa, Shiven and Akurathi, Jaya Vardhini and Sabit, Ahsanul Ameen and Woodlief, Trey and Hossain, Soneya Binta},
  year         = {2026},
  eprint       = {2606.31971},
  archivePrefix = {arXiv},
  primaryClass = {cs.SE},
  doi          = {10.48550/arXiv.2606.31971},
  url          = {https://arxiv.org/abs/2606.31971}
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

CoCoMUT itself requires JDK 17+, but repository builds may use a different JDK.
For build subprocesses, CoCoMUT checks `COCOMUT_BUILD_JAVA_HOME`, then project
declarations such as `.java-version`, `.sdkmanrc`, and the Gradle wrapper. Known
JDK homes can be supplied as `COCOMUT_JAVA_HOME_<major>` for Java 8 through
26. Exact installed versions are preferred for Maven and Gradle toolchains;
otherwise, a compatible newer compiler may be used for release-target builds.
If a compiler, build-tool toolchain, or Maven Enforcer rule explicitly
requests a newer Java release, CoCoMUT performs bounded, monotonic retries with
compatible installed JDKs. This supports multi-module builds whose later
modules require newer Java versions. The extraction report and manifest record the selected
build JDK and the evidence used. The extraction report also records the final
`phase_1_build_command` and a structured `phase_1_build_attempts` list containing
the command, selected JDK, exit code, timeout state, and reason for every bounded
invocation. If the requested JDK is unavailable, CoCoMUT
uses the inherited build environment and reports that fallback explicitly.

Build recovery is bounded and evidence-driven. CoCoMUT retries transient network
failures at most twice, provisions only explicitly declared Android SDK
components when `sdkmanager` is already available, and retries Maven `package`
only when every missing artifact belongs to the declared reactor. It does not
edit subject repositories or guess credentials, dependency versions, SDK
levels, or custom setup commands. Reports retain `BUILD_FAILED` as the primary
code and add a stable `phase_1_build_failure_reason`, such as
`BUILD_FAILED_JDK_UNAVAILABLE`, `BUILD_FAILED_DEPENDENCY_UNAVAILABLE`, or
`BUILD_FAILED_UNKNOWN_ERROR`.

When no build descriptor exists at the requested root, CoCoMUT uses one unique
nested Maven or Gradle root. Multiple independent nested builds remain
ambiguous and require an explicit project root per invocation. Generated Maven
and Gradle source directories are added after a successful build; generated
test sources are included only when the requested source sets include tests.
