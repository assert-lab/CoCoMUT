# Large Field-Test Follow-up

This follow-up substitutes exact-commit reruns into the original 803-repository
field test. It is not a complete rerun of all subjects with the latest code.

| Outcome | Original adjusted count | After targeted recovery |
| --- | ---: | ---: |
| SUCCESS | 148 | 150 |
| PARTIAL | 126 | 128 |
| FAILED | 502 | 498 |
| ERROR | 16 | 16 |
| TIMEOUT | 8 | 8 |
| Clone timeout | 1 | 1 |
| Bounded skip | 2 | 2 |
| **Total** | **803** | **803** |

Recovered exact-commit subjects:

- `torakiki/pdfsam`: `FAILED` to `SUCCESS`;
- `Col-E/Recaf`: `FAILED` to `SUCCESS` after selecting its declared Java 22 toolchain;
- `TeamNewPipe/NewPipeExtractor`: `FAILED` to `PARTIAL`;
- `google/error-prone`: `FAILED` to `PARTIAL`.

The remaining targeted failures retained explicit second-level reasons such as
`BUILD_FAILED_TOOLCHAIN_UNAVAILABLE`,
`BUILD_FAILED_ANDROID_SDK_UNAVAILABLE`,
`BUILD_FAILED_PROJECT_COMPILATION_ERROR`, and
`BUILD_FAILED_REACTOR_ARTIFACT_MISSING`. CoCoMUT did not patch subject sources,
change dependency versions, bypass credentials, or run undeclared setup scripts.

## Latest bounded validation

The following later cohorts are reported separately because they overlap with
the original 803 subjects and with each other; they must not be added directly
to the table above.

| Cohort | Subjects | SUCCESS | PARTIAL | FAILED | ERROR | TIMEOUT | Records | Call edges |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Compatibility and discovery targets (4 GiB) | 114 | 24 | 15 | 65 | 4 | 6 | 398,099 | 2,190,554 |
| Post-fix recovery targets (4 GiB) | 25 | 13 | 2 | 8 | 1 | 1 | 148,964 | 255,726 |
| SonarQube heap validation (8 GiB) | 1 | 1 | 0 | 0 | 0 | 0 | 29,320 | 114,005 |

The post-fix cohort confirmed general recoveries for inherited Maven source
roots, standard Gradle source roots from built modules, active Maven-profile
outputs, same-reactor classifier artifacts, and JDK version diagnostics. For
example, `JPlag/JPlag` emitted 29,651 records, and
`dtinit/data-transfer-project` emitted 2,985 records after the
respective discovery fixes. Keycloak and Jib emitted source-derived records as
`PARTIAL` when SootUp call-graph construction was unavailable,
instead of reporting a hard call-graph failure.

The SonarQube validation identified and fixed an unbounded optional artifact:
serializing the full SootUp graph with `toString()` exhausted the 4 GiB
heap before context extraction. CoCoMUT now writes a bounded, explicitly
truncated human-readable edge listing. At 8 GiB, SonarQube completed all
29,320 records and 114,005 serialized caller/callee entries within the same
20-minute wall-time bound.
