# OE25 Plus Representative Study

> Historical note: this report describes a pre-mandatory-bytecode experiment.
> Mentions of legacy source-only, skipped compilation, or disabled call-graph
> retries are preserved as provenance for that run and are not current CoCoMUT
> behavior.

This branch adds a reproducible field-test runner for the OE25 repository set
plus the 30 representative public-repository checkouts already present under
`experiments/expanded-public-repos-auto-main-representative/checkouts/`.

The local run wrote artifacts to:

```text
experiments/oe25-plus-representative-auto-main/
```

The runner refuses to write to these protected experiment folders:

```text
experiments/expanded-public-repos-auto-main
experiments/expanded-public-repos-auto-main-representative
experiments/manual-commons-lang-rerun-2026-06-17-final
```

## Command

The completed run used:

```bash
python3 scripts/run_oe25_plus_representative_study.py \
  --output-dir experiments/oe25-plus-representative-auto-main \
  --timeout 300 \
  --compile-timeout 120 \
  --heap-gb 2 \
  --retry-max-source-files 1500 \
  --retry-max-methods 5000
```

The first phase of the run used a longer timeout, then the runner was improved
after `apple/pkl` showed that retrying classpath mode without call graph can
repeat the same expensive Spoon phase. The committed runner now sends
timeout/OOM/stack-overflow cases directly to bounded source-only extraction.

## Policy

Legacy default extraction attempts:

```text
pre-mandatory-bytecode optional compile/source-resolution/call-graph modes
```

Legacy fallbacks:

```text
ordinary nonzero exit      -> retry with --call-graph none
timeout/OOM/StackOverflow  -> retry source-only, bounded by source files/methods
```

The runner also skips compilation for Java repositories whose root contains
frontend package-manager markers such as `package.json`, `yarn.lock`,
`package-lock.json`, or `pnpm-lock.yaml`. This is a field-test safety policy:
CoCoMUT should not trigger unrelated frontend dependency installation while
testing Java source extraction.

## Results

Primary local tables:

```text
experiments/oe25-plus-representative-auto-main/results.tsv
experiments/oe25-plus-representative-auto-main/targets.tsv
experiments/oe25-plus-representative-auto-main/README.md
```

Aggregate result:

```text
repositories attempted:        55
OE25 repositories:             25
representative repositories:   30
successful CoCoMUT outputs:      55
methods identified:            204,789
JSONL rows emitted:            204,789
@see references observed:      10,367
inline link references:        48,124
methods using {@inheritDoc}:   3,117
```

Compile/call-graph behavior:

```text
compiled successfully:         33
compile unavailable/failed:    22
RTA call graph available:      37
call graph unavailable/none:   18
bounded source-only fallback:  11
```

Historical source backend modes observed:

```text
legacy source-only fallback:   30
classpath:                     13
legacy bounded source-only:    11
legacy source-only:            1
```

## Javadoc Reference Breakdown

Observed `@see` reference resolution counts:

```text
resolved_method:                       5,815
external URL:                          1,772
external symbol:                         992
resolved_type:                           847
unresolved:                              402
class_resolved_member_unresolved:        172
resolved_inherited_method:               152
resolved_field:                          141
overload_ambiguous:                       68
ambiguous_field:                           4
text:                                      2
```

Observed `@see` kind counts:

```text
member_reference:        7,227
external_url:            1,772
type_reference:          1,163
field_reference:           203
text_reference:              2
```

High-value `@see` inspection cases:

```text
redis/lettuce                         1,560 @see refs
ReactiveX/RxJava                      1,516 @see refs
junit-team/junit-framework            1,234 @see refs
apache/commons-lang                     616 @see refs
remkop/picocli                          424 @see refs
TNG/ArchUnit                            334 @see refs
apache/cassandra-java-driver            322 @see refs
apache/commons-geometry                 320 @see refs
redis/jedis                             308 @see refs
jhy/jsoup                               263 @see refs
```

## Findings

No product code bug was confirmed during this run. The main changes were to the
study runner:

- timeout/OOM/stack-overflow cases now skip the expensive classpath no-callgraph
  retry and go directly to bounded source-only extraction;
- mixed Java/frontend repositories skip compile attempts to avoid unrelated
  package-manager side effects, while still allowing source extraction and any
  already available class-output directories to be used.

The `@see` parser behavior remains framed by standard Javadoc syntax rather than
repository-specific conventions. Any future parser change motivated by these
results should be justified against the Oracle/JDK standard doc-comment model
for `@see`, `{@link ...}`, and `{@linkplain ...}` program-element references.

## Limitations To Inspect Next

- `unresolved` and `class_resolved_member_unresolved` `@see` cases are the next
  best sample for parser improvements.
- `overload_ambiguous` cases should be reviewed to confirm that CoCoMUT reports
  ambiguity only when the target omits enough information to make overload
  selection unsafe.
- External symbols are intentionally symbol-level only; CoCoMUT does not fetch
  external Javadoc text.
- Several large Gradle/Maven projects require bounded source-only extraction on
  this 16 GB machine. That is field-test evidence about resource policy, not a
  Javadoc parsing rule.
