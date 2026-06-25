# Subject Selection Protocol

This file documents the compile-qualified publication cohort in
`evaluation/subjects.csv`.

## Selection Rule

The cohort is intentionally compile-qualified: every final subject must produce
usable project bytecode under the evaluation command on the worker. Repositories
that fail before usable bytecode exists are excluded from this reduced study and
should be reported as subject-screening failures, not extraction successes.

Subjects were selected from the pre-existing
`experiments/expanded-public-repos-auto-main/repos.tsv` popularity-ranked Java
repository pool plus prior worker compile evidence:

- primary language is Java;
- repository is not archived according to GitHub metadata;
- repository has a clear Maven or Gradle build at the pinned checkout;
- Android-specific and plain/no-build subjects are excluded;
- commits are pinned from the already cloned pool before running this
  evaluation;
- phase-1 build failures are replaced with compile-qualified subjects from the
  same pool;
- general CoCoMUT failures observed after successful build are fixed in CoCoMUT
  rather than avoiding the subject;
- the final set contains exactly 10 Maven and 10 Gradle repositories;
- both cohorts contain small, medium, and large repositories and both
  single-module and multi-module subjects where available.

This cohort supports claims about extraction behavior after build-backed project
preparation succeeds. It should not be used to claim that arbitrary Maven or
Gradle repositories compile at this rate.

## Cohort Shape

```text
Build system: 10 Maven, 10 Gradle
Size bins:    4 small, 13 medium, 3 large
Modules:      7 single-module, 13 multi-module
```

The cohort is still a small descriptive sample. Maven and Gradle comparisons
should be interpreted as observations over this compile-qualified set, not as
causal claims about build systems or repository buildability.
