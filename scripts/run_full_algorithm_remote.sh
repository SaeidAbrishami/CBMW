#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/run_full_algorithm_remote.sh <CBMW|NOSF|CEWB|StaticGreedy|DynamicGreedy>" >&2
  exit 2
fi

algorithm="$1"
output_dir="${CBMW_OUTPUT_DIR:-Output/full_reference}"
log_dir="logs"
log_file="${log_dir}/full_${algorithm}.log"
done_file="${log_dir}/full_${algorithm}.done"
failed_file="${log_dir}/full_${algorithm}.failed"

mkdir -p bin "$log_dir"
rm -f "$done_file" "$failed_file"

set +e
{
  echo "[start] $(date -Is) algorithm=${algorithm}"
  echo "[compile] collecting sources"
  find sources examples -name "*.java" | sort > /tmp/cbmw_sources_${algorithm}.txt
  javac -cp "lib/*" -d bin "@/tmp/cbmw_sources_${algorithm}.txt"
  compile_status=$?
  if [[ "$compile_status" -ne 0 ]]; then
    echo "[compile-failed] status=${compile_status}"
    exit "$compile_status"
  fi

  echo "[run] output_dir=${output_dir}"
  java \
    -Dcbmw.algorithms="${algorithm}" \
    -Dcbmw.output.dir="${output_dir}" \
    -Dcbmw.export.details=true \
    -Dcbmw.detail.log=false \
    -Dcbmw.quiet=true \
    -Dcbmw.generate.gantt=false \
    -Dcbmw.generate.comparison=false \
    -cp "bin:lib/*" \
    org.workflowsim.examples.cbmw.CBMWSimulation

  status=$?
  echo "[finish] $(date -Is) status=${status}"
  exit "$status"
} >"$log_file" 2>&1
status=$?
set -e

if [[ "$status" -eq 0 ]]; then
  echo "$(date -Is)" > "$done_file"
else
  echo "$(date -Is) status=${status}" > "$failed_file"
fi
exit "$status"
