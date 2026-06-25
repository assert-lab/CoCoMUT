# Subject Selection Protocol

This file documents the publication rerun cohort in `evaluation/subjects.csv`.

## Selection Rule

The cohort was frozen before the publication rerun and must not be modified
after observing CoCoMUT outcomes.

Subjects were selected from the pre-existing
`experiments/expanded-public-repos-auto-main/repos.tsv` popularity-ranked Java
repository pool. Selection used repository metadata and build files only:

- primary language is Java;
- repository is not archived according to GitHub metadata;
- repository has a clear Maven or Gradle build at the pinned checkout;
- Android-specific and plain/no-build subjects are excluded;
- commits are pinned from the already cloned pool before running this
  evaluation;
- the final set contains exactly 10 Maven and 10 Gradle repositories;
- both cohorts contain small, medium, and large repositories and both
  single-module and multi-module subjects where available.

Prior CoCoMUT success/failure outcomes were not used to replace subjects in the
publication rerun. Any build, parsing, call-graph, JSONL, or timeout failure in
the frozen cohort remains in the denominator.

## Cohort Shape

```text
Build system: 10 Maven, 10 Gradle
Size bins:    4 small, 13 medium, 3 large
Modules:      5 single-module, 15 multi-module
```

The cohort is still a small descriptive sample. Maven and Gradle comparisons
should be interpreted as observations over this frozen set, not as causal
claims about build systems.
