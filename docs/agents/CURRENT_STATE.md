# Current State

This file is for short-lived coordination across multiple agent instances.
Keep durable rules in `AGENTS.md`.

## Active Baseline

- Repository: `assert-lab/CoCoMUT`
- Base branch: `main`
- Current task branch: `task/release-alignment`

## Current Branch Summary

`task/release-alignment` is a release-readiness pass. The branch aligns public
code, CLI/API surfaces, product docs, schemas, and sample outputs. It
deliberately ignores historical notes under `docs/research/`.

Current cleanup goals:

- remove deprecated public API surfaces;
- remove stale release documentation;
- keep the project-bytecode extraction requirements explicit;
- verify CLI/API/schema/sample consistency before pushing.

## Verification Target

Before handing off or opening a PR, run:

```bash
./mvnw -q test
scripts/build_release_jar.sh
./bin/cocomut --project analyzer-tests/src/test/resources/fixtures/minimal-maven-project \
  --scope entry-points --source-set main --max-methods 1 \
  --output-dir /tmp/cocomut-release-smoke
```
