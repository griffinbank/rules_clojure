#!/usr/bin/env bash
OUTPUT=$($1)
if ! grep -q "$2" <<< "$OUTPUT"; then
    echo "Test output:"
    echo "$OUTPUT"
    echo "Does not contain:"
    echo "$2"
    exit 1
fi
exit 0
