#!/usr/bin/env bash
# Runs bazel coverage on two namespaces that both have the file name core.clj,
# and makes sure that the LCOV report has hit lines for each.
set -euo pipefail

cd "$(dirname "$0")/../.."

bazel coverage \
  --combined_report=lcov \
  --instrumentation_filter=//test/coverage \
  //test/coverage:coverage_test

REPORT="bazel-out/_coverage/_coverage_report.dat"
cat "${REPORT}"

for src in test/coverage/foo/core.clj test/coverage/bar/core.clj; do
  hits=$(awk -v sf="SF:${src}" '$0 == sf {on=1} on && /^DA:[0-9]+,[1-9]/ {n++} /^end_of_record/ {on=0} END {print n+0}' "${REPORT}")
  if [[ "${hits}" -lt 1 ]]; then
    echo "ERROR: no hit lines for ${src}" >&2
    exit 1
  fi
  echo "${src}: ${hits} hit lines"
done
