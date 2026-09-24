#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/run_cbmw_config.py "${1:-config/cbmw_experiments.txt}"
