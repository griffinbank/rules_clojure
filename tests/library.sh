#!/usr/bin/env bash
ACTUAL="$(zipinfo -1 "$1")"
EXPECTED=${@:2}
for i in $EXPECTED; do
    if ! grep -q "$i" <<< "$ACTUAL"; then
        echo "$1 does not contain $i"
        echo "$ACTUAL"
        exit 1
    fi
done
exit 0
