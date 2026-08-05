#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

python3 -m pip install --user pre-commit
python3 -m pre_commit install

printf '%s\n' "Pre-commit is installed for this repository."
printf '%s\n' "Run 'python3 -m pre_commit run --all-files' to validate all tracked files."
