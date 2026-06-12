#!/usr/bin/env bash
set -euo pipefail

POC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$POC_DIR/../.." && pwd)"
CONTEXT_REPO="$REPO_ROOT/Context4DocuGen"
LOCAL_M2="$REPO_ROOT/.m2"

if [[ -f /etc/profile ]]; then
  set +u
  # shellcheck disable=SC1091
  source /etc/profile
  set -u
fi

echo "== Compiling PoC fixture projects =="
mvn -q -Dmaven.repo.local="$LOCAL_M2" -f "$POC_DIR/fixture-parameter-provenance-project/pom.xml" -DskipTests compile
mvn -q -Dmaven.repo.local="$LOCAL_M2" -f "$POC_DIR/fixture-unsafe-id-project/pom.xml" -DskipTests compile

echo
echo "== Installing regression test into analyzer-tests =="
mkdir -p "$CONTEXT_REPO/analyzer-tests/src/test/java/analyzer"
cp "$POC_DIR/UndergradIssuesRegressionTest.java" \
   "$CONTEXT_REPO/analyzer-tests/src/test/java/analyzer/UndergradIssuesRegressionTest.java"

echo
echo "== Running U1/U2/U3 PoC regression tests =="
cd "$CONTEXT_REPO"
set +e
MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx2g -Xms512m"}" \
mvn -q -Dmaven.repo.local="$LOCAL_M2" -pl analyzer-tests -am \
  -Dtest=UndergradIssuesRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
TEST_EXIT=$?
set -e

if [[ "$TEST_EXIT" -eq 0 ]]; then
  echo
  echo "Undergrad regression tests passed. That means U1/U2/U3 may already be fixed in this checkout."
else
  echo
  echo "Undergrad regression tests failed as expected on the current buggy implementation."
  echo "This is a successful PoC reproduction, not a script failure."
fi
