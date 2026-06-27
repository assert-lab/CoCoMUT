# Subject Selection Protocol

This file documents the publication cohort in `evaluation/subjects.csv`.

## Selection Rule

The cohort is selected for the reduced evaluation: every final
subject is a real-world Java repository with a pinned commit, a clear Maven or
Gradle build, usable project bytecode, and an RTA call graph under
the evaluation command.

Subjects were selected from the pre-existing
`experiments/expanded-public-repos-auto-main/repos.tsv` popularity-ranked Java
repository pool plus prior compile evidence:

- primary language is Java;
- repository is not archived according to GitHub metadata;
- repository has a clear Maven or Gradle build at the pinned checkout;
- Android-specific and plain/no-build subjects are outside the reduced study
  scope;
- commits are pinned from the already cloned pool before running this
  evaluation;
- the final set is balanced at 10 Maven and 10 Gradle subjects;
- observed tool robustness issues were addressed with general,
  non-repository-specific fixes before the publication rerun;
- the final set contains exactly 10 Maven and 10 Gradle repositories;
- both cohorts contain small, medium, and large repositories and both
  single-module and multi-module subjects where available.

This cohort supports claims about extraction behavior after project build
execution and call-graph construction for the selected subjects.

## Cohort Shape

```text
Build system: 10 Maven, 10 Gradle
Size bins:    4 small, 14 medium, 2 large
Modules:      7 single-module, 13 multi-module
```

The cohort is a small descriptive sample. Maven and Gradle comparisons should be
interpreted as observations over this selected set.
