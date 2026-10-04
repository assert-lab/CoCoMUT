# Independent source population audit

These tools compare explicit Java method/constructor declarations with CoCoMUT's
source model. They use javac's parse-only Compiler Tree API independently of
Spoon and the product's JDT declaration audit. They do not compile subjects or
resolve dependencies. Use a JDK supporting the subject's syntax (JDK 26 for the
issue #25 field-test reproductions).

Build the CLI JAR, then keep audit outputs outside the subject checkout:

```sh
mvn -pl cocomut-cli -am -DskipTests package
JAR="$PWD/cocomut-cli/target/cocomut-cli-0.1.0-all.jar"
java -Xmx2g -XX:ActiveProcessorCount=2 -cp "$JAR" \
  scripts/population-audit/SourcePopulationProbe.java \
  /path/to/extraction_manifest.json /tmp/population-probe
java -Xmx2g -XX:ActiveProcessorCount=2 -cp "$JAR" \
  scripts/population-audit/JavacPopulationAudit.java \
  /path/to/subject /tmp/population-probe/methods.jsonl \
  /tmp/population-audit.json /tmp/population-probe/source-roots.json
python3 scripts/population-audit/test_audit.py --jar "$JAR"
```

The probe reconstructs source roots and artifact paths from an extraction
manifest; those paths must still exist. Append `--discover` to the
probe command to exercise current adapter discovery with `--skip-build`, rather
than reuse the archived source-root list. Use `--discover-main` for main-only discovery through the detected project adapter. The probe retains source-set, visibility and generated provenance per declaration. Neither option proves a fresh build or native Gradle metadata success.

The independent inventory includes every tracked `**/src/main/java/**/*.java`,
every file represented in the model, and all Java files under the supplied roots.
Including tracked files independently catches wholly omitted production roots.
The inventory can include test and generated resolution inputs. Its counts are
not the default focal population, and standalone examples or separate builds
outside the selected build must be classified explicitly when reviewing missing
declarations. An omitted root with no tracked Java files is outside the independent
tracked-file inventory unless supplied in the roots JSON.

Matching requires the exact relative source path, constructor/name, parameter
count, and a Spoon source position inside the javac declaration header or its associated Javadoc span, obtained from javac DocTrees. It
handles annotation lines, tabs, multiline signatures and same-line overloads
without guessing between candidates. Implicit constructors, initializers and
lambda bodies are not counted as explicit method declarations. Parse failures
and ambiguous matches are retained separately; neither means complete coverage.
The auditor writes findings and exits normally; consumers must inspect `missing`,
`ambiguous` and `parse_failures` instead of treating exit zero as acceptance.

Source-model coverage alone does not prove selection or JSONL row preservation.
For an end-to-end check, run the CLI with `--scope all`, the intended source sets,
and exact `--include-path` filters; compare emitted `MUT.method_uri` values with
the probe's exact URI set for those files. A `--type` filter names an exact type;
it does not automatically include that type's nested enum/class declarations.
Retain the CLI command, report, manifest, source hashes, actual subject commit and
dirty status alongside results. Degraded evidence remains `PARTIAL`, and strict
source-classpath requirements still reject fallback evidence.

See [the combined-main audit receipt](../../docs/population-audit-25.md) for
the available issue #25 evidence and its historical-cohort limitations.

## Comparing method identities

Replay the **actual CLI extraction manifest** when comparing a probe to CLI rows.
Retain the source snapshot, CoCoMUT JAR, runtime JDK, ordered roots/classpath,
requested compliance and effective backend attempts. Do not construct a manifest
using the runner JDK version as the subject's Java version. The probe records
normalized `source_model_inputs` so these differences are visible.

URI equality is required for equivalent inputs and settings. Cross-compliance or
cross-backend anonymous-class numbering and unresolved nested-type spelling are
not a supported identity-stability guarantee. Keep original receipts and report
changed identities separately; never rewrite them heuristically to make sets agree.

The [controlled #50 receipt](../../docs/identity-audit-50.json) repeats two probe
and two CLI runs of Commons Numbers: all four have exactly 2,310 identical URIs,
no duplicates or omissions, and `no_classpath` mode. The original probe used
Java 17 compliance; the CLI used Java 8. Changing only compliance reproduces the
two anonymous-owner/nested-return-type discrepancies. Changing class-output order
while retaining Java 8 does not. All 273 original Java-file hashes remain unchanged.
No product identifier change is needed for that report. The historical #25
receipts remain unchanged; its population audit stays closed.
