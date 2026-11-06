#!/usr/bin/env bash
# Optional offline measurements only. Run after compilation, serially with other heavy checks.
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
exec python3 -B scripts/benchmark.py "$@"
