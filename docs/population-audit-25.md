# Issue #25: combined-main population audit

Product baseline: `bee9d6cbb492fed20d4e02d6dc24580525657e83`, after the
Gradle launch/selection/root, source-model and enrichment-preservation changes
were merged. This audit checks the named field-test reproductions in
[#25](https://github.com/assert-lab/CoCoMUT/issues/25); it does not establish
recovery of the original 11,733 declarations in the unidentified 25-project run.
The [compact machine-readable receipt](population-audit-25.json) includes the
JAR/source snapshot hashes, declaration counts and CLI URI reconciliations.

## Independent declaration inventory

The [audit tools](../scripts/population-audit/README.md) use javac parse-only
declarations, independently of Spoon/JDT, and match exact declaration headers to
the source model. The inventory includes tracked production paths, declared
source roots and model files, so it includes test/generated resolution inputs
where those were supplied. These are source-model counts, not default focal
population counts.

| Subject | Subject HEAD | javac declarations | Matched | Findings |
|---|---|---:|---:|---|
| RocketMQ | `e348efa66b08eb645ee123706ea6492fa9a3ad35` | 24,099 | 24,099 | No missing or ambiguous declarations; no javac parse failures |
| Spring Cloud Gateway | `b45150cad83629d5288072ded77090ad5f7a2039` | 5,577 | 5,577 | No missing or ambiguous declarations; no javac parse failures |
| Picocli | `10509c0af89aa3254ca14ba90d9b3b7168e57994` | 6,400 | 6,388 | 12 declarations in six source files outside the root build; no ambiguities or javac parse failures |

RocketMQ and Gateway use the archived field-test manifest roots/artifacts with
the current source backend. Picocli uses current Gradle fallback root discovery
with builds disabled; eight main roots are found instead of the archived single
root. This verifies fallback discovery, not a fresh Gradle build or native
metadata timeout. Existing subjects are dirty checkouts: HEAD matches the pinned
commit, but the audited working-source snapshot is not claimed to be pristine.
Receipts retain dirty status and per-file hashes.

The 12 unmatched Picocli declarations are two each in:

- `picocli-examples/annotation-processing/example-gradle-project/src/main/java/com/company/Main.java`
- `picocli-examples/annotation-processing/example-maven-project-shading/src/main/java/com/company/Main.java`
- `picocli-examples/annotation-processing/example-maven-project-simple/src/main/java/com/company/Main.java`
- `picocli-examples/generate-man-pages/example-gradle-project/src/main/java/com/company/Main.java`
- `picocli-examples/generate-man-pages/example-maven-project/src/main/java/com/company/Main.java`
- `picocli-tests-jpms-modules/app/src/main/java/picocli/test_jpms/modular_app/JpmsModularApp.java`

The first five belong to independent example projects, outside the declared
`picocli-examples/src/main/java` source root. The last belongs to the separate
JPMS build with its own `settings.gradle`. None is included by the root
`settings.gradle`. Their presence in the broad tracked production-path inventory
is retained and documented rather than silently counted as recovered.

## End-to-end selection and JSONL checks

CLI runs use `--skip-build --scope all --source-set main`, existing artifacts,
and exact file filters. Emitted `MUT.method_uri` sets are compared to the
independently checked source model. No method limit is applied.

| Check | Expected rows | Emitted rows | Missing/unexpected URIs |
|---|---:|---:|---:|
| RocketMQ's two reported source files | 21 | 21 | 0/0 |
| Gateway's seven reported source files | 176 | 176 | 0/0 |
| One source file from each of Picocli's seven recovered child roots | 153 | 153 | 0/0 |

RocketMQ retains the reported five plus fourteen non-constructor methods and
two constructors. Gateway retains all 159 reported methods plus 17 constructors.
The Picocli CLI run uses automatic fallback discovery without explicit source
roots. These file selections check row preservation through the real CLI; they
are not full-repository enrichment/call-graph acceptance runs.

All three CLI runs return `PARTIAL`/exit 2. RocketMQ and Gateway record
`SOURCE_CLASSPATH_DEGRADED` and `SOURCE_MODEL_RECOVERED`; Picocli records
`SOURCE_CLASSPATH_DEGRADED`. Restored declarations therefore do not imply fully
resolved source evidence. Strict source-classpath acceptance remains a separate
requirement.

The auditor's focused regression checks pass for annotation/tab positions,
same-line overloads, private methods, nested constructors, entirely omitted
production roots and explicit javac syntax failures. No product schema or
runtime behavior is changed by these audit tools.

## Receipts and remaining historical gap

Durable artifacts on alienserver:
`~/agent-runs/cocomut-issue25-audit/`. Each subject directory contains probe/audit
commands, source-model/declaration inventories, source hashes, CLI commands,
reports/manifests, and exact URI reconciliation. `verification.json` records the
tool JAR hash, subject HEAD/status, source-snapshot digest and counts. Large
inventories/JSONL outputs are kept outside Git.

The original issue does not identify its 25 repositories/commits or supply its
javac/JavaParser comparison. A saved OE25 cohort has older schema 0.3.0 outputs
without original-run commit receipts; a separate evaluation archive contains 20
pinned subjects. Neither has been established as the reported 25-project run.
Substituting either would not account for the original 7,469 within-file losses
and 4,264 declarations in 402 omitted files. Keep #25 open for that historical
reconciliation; the named later reproductions above are verified independently.
