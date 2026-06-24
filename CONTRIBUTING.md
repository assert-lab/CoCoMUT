# Contributing

CoCoMUT is a Java 17 Maven project.

## Build

```bash
./mvnw test
./mvnw -DskipTests package
```

Build the standalone CLI jar:

```bash
scripts/build_release_jar.sh
```

## CLI Smoke Test

```bash
./bin/cocomut --project analyzer-tests/src/test/resources/fixtures/minimal-maven-project --scope entry-points --source-set main
```

## Development Notes

- Keep core extraction code in `analyzer-core`.
- Keep CLI-specific code in `cocomut-cli`.
- Keep generated outputs out of git.
- Add focused tests for parser, method identity, JSON output, and CLI behavior.
- Do not add benchmark repositories or generated research artifacts to the product repository.
