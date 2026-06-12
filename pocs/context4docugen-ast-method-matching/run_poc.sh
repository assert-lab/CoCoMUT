#!/usr/bin/env bash
set -euo pipefail

POC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$POC_DIR/../.." && pwd)"
FIXTURE_PROJECT="$POC_DIR/fixture-project"
CONTEXT_REPO="$REPO_ROOT/Context4DocuGen"
LOCAL_M2="$REPO_ROOT/.m2"

if [[ -f /etc/profile ]]; then
  # Arch's java-runtime-common asks users to source /etc/profile after install.
  set +u
  # shellcheck disable=SC1091
  source /etc/profile
  set -u
fi

echo "== Compiling PoC fixture project =="
mvn -q -Dmaven.repo.local="$LOCAL_M2" -f "$FIXTURE_PROJECT/pom.xml" -DskipTests compile

echo
echo "== Installing regression test into analyzer-tests =="
mkdir -p "$CONTEXT_REPO/analyzer-tests/src/test/java/analyzer"
cp "$POC_DIR/SelectedMethodLoaderAstMatchingRegressionTest.java" \
   "$CONTEXT_REPO/analyzer-tests/src/test/java/analyzer/SelectedMethodLoaderAstMatchingRegressionTest.java"

echo
echo "== Running AST method-matching regression test =="
cd "$CONTEXT_REPO"
set +e
MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx2g -Xms512m"}" \
mvn -q -Dmaven.repo.local="$LOCAL_M2" -pl analyzer-tests -am \
  -Dtest=SelectedMethodLoaderAstMatchingRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
TEST_EXIT=$?
set -e

echo
echo "== Loaded methods =="
if [[ -f "$FIXTURE_PROJECT/methods.csv" ]]; then
  sed -n '1,20p' "$FIXTURE_PROJECT/methods.csv"
else
  echo "methods.csv was not generated"
fi

echo
echo "== Generated JSON files =="
find "$FIXTURE_PROJECT/method_context_json" -maxdepth 1 -type f -name '*.json' -printf '%f\n' 2>/dev/null | sort || true

echo
echo "Expected after an AST-based fix: all six selected declarations load."
echo "Verified current behavior: constructor_label and compact_record_constructor are missed."
echo "Also observed: nested_value loads but is attributed to the outer class, and generic_max has weak parameter metadata."

if [[ "$TEST_EXIT" -eq 0 ]]; then
  echo
  echo "Regression test passed. That means the bug may already be fixed in this checkout."
else
  echo
  echo "Regression test failed as expected on the current buggy implementation."
  echo "This is a successful PoC reproduction, not a script failure."
fi
