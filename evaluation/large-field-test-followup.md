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
