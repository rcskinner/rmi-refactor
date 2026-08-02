#!/usr/bin/env bash
set -euo pipefail

matches="$(git grep -n -E 'TODO|FIXME' -- ':(glob)src/**/*.java' || true)"
if [[ -z "$matches" ]]; then
  echo "No TODO or FIXME markers found."
  exit 0
fi

invalid="$(printf '%s\n' "$matches" | grep -Ev 'TODO\([A-Z][A-Z0-9-]+-[0-9]+\)|FIXME\([A-Z][A-Z0-9-]+-[0-9]+\)' || true)"
if [[ -n "$invalid" ]]; then
  echo "TODO and FIXME markers must include an issue reference, for example TODO(RMI-123): ..." >&2
  printf '%s\n' "$invalid" >&2
  exit 1
fi

echo "All TODO and FIXME markers include issue references."
