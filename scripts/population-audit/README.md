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
manifest; those paths must still exist. For Gradle, append `--discover` to the
probe command to exercise current fallback discovery with `--skip-build`, rather
than reuse the archived source-root list. This does not exercise a fresh Gradle
build or a successful native Gradle metadata query.

The independent inventory includes every tracked `**/src/main/java/**/*.java`,
every file represented in the model, and all Java files under the supplied roots.
Including tracked files independently catches wholly omitted production roots.
The inventory can include test and generated resolution inputs. Its counts are
not the default focal population, and standalone examples or separate builds
outside the selected build must be classified explicitly when reviewing missing
declarations. An omitted root with no tracked Java files is outside the independent
tracked-file inventory unless supplied in the roots JSON.

Matching requires the exact relative source path, constructor/name, parameter
count, and a Spoon source position inside the javac declaration header. It
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
