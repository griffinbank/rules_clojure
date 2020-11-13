#!/usr/bin/env bash
OUTPUT=$($1 $2)
if [[ "$OUTPUT" != "$3" ]]; then
    echo "$OUTPUT is not equal to $3"
    exit 1
fi
exit 0
