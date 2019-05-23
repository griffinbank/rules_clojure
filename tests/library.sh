#!/usr/bin/env bash
if [[ `zipinfo -1 $1 | grep $2` ]]; then
  echo "Passed: $1 contains $2"
  exit 0
else
  echo "Failed: $1 does not contain $2"
  exit 1
fi
