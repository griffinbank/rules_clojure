#!/usr/bin/env bash
# Runs bazel coverage on two namespaces that both have the file name core.clj,
# and makes sure that the LCOV report has a hit on the body of each `greet`
# (line 4). The `defn` line is hit when the namespace only loads, so a body
# line is the check that the test called the function.
set -euo pipefail

cd "$(dirname "$0")/../.."

bazel coverage \
  --combined_report=lcov \
  --instrumentation_filter=//test/coverage \
  //test/coverage:coverage_test

REPORT="bazel-out/_coverage/_coverage_report.dat"
cat "${REPORT}"

BODY_LINE=4

for src in test/coverage/foo/core.clj test/coverage/bar/core.clj; do
  hit=$(awk -v sf="SF:${src}" -v da="DA:${BODY_LINE}," '$0 == sf {on=1} on && index($0, da) == 1 {split($0, a, ","); print a[2]} /^end_of_record/ {on=0}' "${REPORT}")
  if [[ "${hit:-0}" -lt 1 ]]; then
    echo "ERROR: ${src}:${BODY_LINE} has no hits" >&2
    exit 1
  fi
  echo "${src}:${BODY_LINE} hit"
done
