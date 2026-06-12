#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 <project-path> [c4dg extract options]" >&2
  echo "Compatibility wrapper for: ./bin/c4dg extract --project <project-path>" >&2
  exit 1
fi

PROJECT_PATH=$1
shift

exec "$SCRIPT_DIR/bin/c4dg" extract --project "$PROJECT_PATH" "$@"
