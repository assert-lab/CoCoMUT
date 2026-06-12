# Security Policy

Context4DocuGen is a static analysis tool. It should not execute analyzed project code during normal extraction.

## Supported Versions

The active development branch supports the current `1.0-SNAPSHOT` line.

## Reporting Security Issues

Please report security concerns privately to the maintainers before opening a public issue.

Include:

- affected command or API entry point;
- operating system and Java version;
- whether the issue requires analyzing untrusted repositories;
- minimal reproduction steps when possible.

## Untrusted Repositories

Use `--call-graph none` and avoid `--compile` when analyzing untrusted repositories. `--compile` invokes the target project's build tool and can execute build scripts.
