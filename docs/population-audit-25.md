# Issue #25: combined-main population audit

Product baseline: `bee9d6cbb492fed20d4e02d6dc24580525657e83`, after the
Gradle launch/selection/root, source-model and enrichment-preservation changes
were merged. This audit checks the named field-test reproductions in
[#25](https://github.com/assert-lab/CoCoMUT/issues/25); it does not establish
recovery of the original 11,733 declarations in the 25-project OE25 run.
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

The user subsequently identified the original cohort as OE25. The public
[TOGBench project/version table](https://github.com/assert-lab/TOGBench#projects)
lists the same 25 repositories as the saved OE25 cohort, after normalizing
release names such as `commons-lang3` to repository names such as
`apache/commons-lang`. The
[identification receipt](oe25-cohort-identification.json) records the public
repository revision, published versions and exact 25/25 repository-name match.
TOGBench contains developer-written OE25dev tests; it establishes the shared
subject-system list, rather than reproducing the original generated-test dataset
or the historical CoCoMUT run.

The repository-list gap is resolved. Exact source revisions for the original
CoCoMUT comparison and its javac/JavaParser results remain unverified. The saved
OE25 outputs use schema 0.3.0 and lack original-run commit receipts; a separate
evaluation archive contains 20 pinned subjects. Published dataset versions must
not be silently substituted for the actual extraction revisions. The full saved-cohort audit below resolves current population coverage. Its results must remain distinct from the historical 7,469 within-file losses plus 4,264 declarations in 402 omitted files.

## Full saved OE25 cohort audit

The 25 saved checkouts were mirrored at their actual Git commits, with all 8,503 original Java-file hashes verified before and after analysis. All checkouts remained clean. These revisions differ from the public release table; the [full receipt](oe25-population-audit.json) records both versions and exact commits.

Current automatic main-root discovery and full CLI extraction (`--skip-build --scope all --source-set main`, no method limit) preserve **50,232/50,232** selected declarations. Seven projects contain another **3,523** declarations outside the default reactor/source roots. Explicitly supplying these roots produces **53,755/53,755** declarations and JSONL rows across the full cohort, with no declaration-count deficit, duplicate rows, ambiguous declaration matches or javac parse failures. Commons Numbers has two missing and two unexpected URIs in the probe-to-CLI set comparison: both pairs concern anonymous-class `get()` methods at lines 536 and 556 of `GammaContinuedFractionPerformance.java`. Their declaring-type numbering and nested return-type spelling differ. This discrepancy is retained in [#50](https://github.com/assert-lab/CoCoMUT/issues/50); equal counts alone do not establish exact row identity. This sensitivity check does not change the default population policy.

| Repository | Default rows | Broad rows | Roots outside default selection |
|---|---:|---:|---|
| AsyncHttpClient/async-http-client | 2,136 | 2,136 | None |
| JodaOrg/joda-time | 4,283 | 4,283 | None |
| apache/commons-bcel | 3,884 | 3,884 | None |
| apache/commons-beanutils | 1,003 | 1,003 | None |
| apache/commons-collections | 4,886 | 4,886 | None |
| apache/commons-configuration | 3,129 | 3,129 | None |
| apache/commons-dbutils | 546 | 546 | None |
| apache/commons-geometry | 3,167 | 3,495 | examples profile |
| apache/commons-imaging | 2,532 | 2,532 | None |
| apache/commons-jcs | 2,387 | 2,725 | sandbox profile |
| apache/commons-jexl | 2,313 | 2,313 | None |
| apache/commons-lang | 4,489 | 4,489 | None |
| apache/commons-net | 1,938 | 1,938 | None |
| apache/commons-numbers | 895 | 2,310 | examples profile and standalone complex-streams module |
| apache/commons-pool | 847 | 847 | None |
| apache/commons-rng | 1,581 | 2,790 | examples profile |
| apache/commons-validator | 770 | 770 | None |
| apache/commons-vfs | 2,882 | 3,035 | include-sandbox profile |
| apache/commons-weaver | 395 | 467 | src/it/sample fixture builds |
| jhy/jsoup | 2,177 | 2,177 | None |
| kevinsawicki/http-request | 202 | 202 | None |
| perwendel/spark | 872 | 872 | None |
| scribejava/scribejava | 1,205 | 1,205 | None |
| springside/springside4 | 1,221 | 1,229 | standalone modules/jmh |
| stleary/JSON-java | 492 | 492 | None |

Six default runs report SUCCESS and nineteen PARTIAL; the explicit-root runs retain their recorded degraded statuses. Population completeness therefore does not imply resolved classpaths or complete call graphs. Existing bytecode was reused. Joda-Time and HTTP Request were compiled directly with JDK 8; SpringSide completed native Maven `package -DskipTests -Dmaven.javadoc.skip=true`; JSON-java used its existing `target/classes` through `--class-output`. This is not a fresh-build acceptance test for every repository.

The saved schema-0.3.0 ENTRY_POINTS output has 39,159 rows. Of these, 39,154 current URIs match exactly. Five anonymous-class URIs changed; each has one current declaration at the exact original file, line and name. The receipt preserves both identities rather than rewriting them. There are 14,601 current-only URIs and a net increase of 14,596 rows. Of the current-only URIs, 12,352 occur in previously represented files; the remainder occur in 304 previously unrepresented files. These are saved-output comparisons, not the original 7,469 + 4,264 historical counts.

The available old report counters total 53,446 declarations before scope filtering and 39,473 afterward, followed by 39,159 emitted rows. The current 53,755-row total is numerically 309 above the former pre-filter count, plus 13,973 formerly filtered declarations and 314 post-selection losses. This arithmetic describes aggregate counts; it does not assign individual new declarations to historical causes without the missing original model.

Durable per-project input manifests, commands, source/model inventories, javac reports, CLI reports/manifests/JSONL, compilation logs, source hashes and reconciliation are under the receipt’s alienserver artifact directory. Focused audit regressions pass on JDK 17 and JDK 26. The saved-cohort population audit is complete; exact reproduction of the historical 11,733 comparison remains unavailable.
