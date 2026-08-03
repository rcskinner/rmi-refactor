#!/usr/bin/env bash
set -euo pipefail

max_lines="${MAX_FILE_LINES:-1200}"
status=0

while IFS= read -r -d '' file; do
    lines="$(wc -l < "$file")"
    if (( lines > max_lines )); then
        printf 'File exceeds %s lines: %s (%s lines)\n' "$max_lines" "$file" "$lines" >&2
        status=1
    fi
done < <(find src -type f -name '*.java' -print0)

if (( status == 0 )); then
    printf 'All Java source files are at or below %s lines.\n' "$max_lines"
fi

exit "$status"
