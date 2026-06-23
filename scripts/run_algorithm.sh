#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/run_algorithm.sh <CBMW|NOSF|CEWB|StaticGreedy|DynamicGreedy> [extra java -D options]" >&2
  exit 2
fi

algorithm="$1"
shift

output_dir="${CBMW_OUTPUT_DIR:-Output}"
export_details="${CBMW_EXPORT_DETAILS:-false}"
detail_log="${CBMW_DETAIL_LOG:-false}"
quiet="${CBMW_QUIET:-true}"
generate_gantt="${CBMW_GENERATE_GANTT:-false}"
generate_comparison="${CBMW_GENERATE_COMPARISON:-false}"

mkdir -p bin
mapfile -t java_files < <(find sources examples -name "*.java" | sort)

javac -cp "lib/*" -d bin "${java_files[@]}"

java_opts=(
  "-Dcbmw.algorithms=${algorithm}"
  "-Dcbmw.output.dir=${output_dir}"
  "-Dcbmw.export.details=${export_details}"
  "-Dcbmw.detail.log=${detail_log}"
  "-Dcbmw.quiet=${quiet}"
  "-Dcbmw.generate.gantt=${generate_gantt}"
  "-Dcbmw.generate.comparison=${generate_comparison}"
)

if [[ -n "${CBMW_MAX_WORKFLOWS:-}" ]]; then
  java_opts+=("-Dcbmw.max.workflows=${CBMW_MAX_WORKFLOWS}")
fi

if [[ -n "${CBMW_MAX_SCENARIOS:-}" ]]; then
  java_opts+=("-Dcbmw.max.scenarios=${CBMW_MAX_SCENARIOS}")
fi

java "${java_opts[@]}" "$@" -cp "bin:lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
