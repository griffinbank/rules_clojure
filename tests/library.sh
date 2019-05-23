#!/usr/bin/env bash
OUTPUT="$(zipinfo -1 "$1")"
ITEMS=${@:2}
for i in $ITEMS; do
    if ! grep -q "$i" <<< "$OUTPUT"; then
        echo "$1 does not contain $i"
        echo "$OUTPUT"
        exit 1
    fi
done
exit 0
