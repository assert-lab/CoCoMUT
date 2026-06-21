#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DIST_DIR="$PROJECT_ROOT/dist"
TARGET_JAR="$PROJECT_ROOT/cocomut-cli/target/cocomut-cli-0.1.0-all.jar"
DIST_JAR="$DIST_DIR/cocomut-cli.jar"

cd "$PROJECT_ROOT"

mvn -q -Dmaven.repo.local=.m2/repository clean test package

mkdir -p "$DIST_DIR"
cp "$TARGET_JAR" "$DIST_JAR"

printf 'Built standalone CLI jar: %s\n' "$DIST_JAR"
printf 'Try it with: java -jar %s --help\n' "$DIST_JAR"
