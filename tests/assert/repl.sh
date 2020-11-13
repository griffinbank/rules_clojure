#!/usr/bin/env bash
OUTPUT=$(echo "$2" | $1)
if ! grep -q "$3" <<< "$OUTPUT"; then
    echo "REPL output:"
    echo "$OUTPUT"
    echo "Does not contain:"
    echo "$3"
    exit 1
fi
exit 0
