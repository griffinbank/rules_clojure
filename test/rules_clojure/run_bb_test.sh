#!/usr/bin/env bash
set -euo pipefail

bb_bin="${TEST_SRCDIR}/${BB_BIN}"
test_script="${TEST_SRCDIR}/${GAZELLE_SERVER_BB_TEST}"
server_script="${TEST_SRCDIR}/${GAZELLE_SERVER_BB}"

for f in "$bb_bin" "$test_script" "$server_script"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: cannot locate $f" >&2
    echo "  TEST_SRCDIR=$TEST_SRCDIR" >&2
    echo "  BB_BIN=$BB_BIN" >&2
    echo "  GAZELLE_SERVER_BB_TEST=$GAZELLE_SERVER_BB_TEST" >&2
    echo "  GAZELLE_SERVER_BB=$GAZELLE_SERVER_BB" >&2
    exit 1
  fi
done

export GAZELLE_SERVER_BB="$server_script"
exec "$bb_bin" "$test_script"
