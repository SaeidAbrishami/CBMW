Deadline outcome tolerance for CBMW, CBMW ablations, NOSF, and CEWB
===================================================================

An accepted, completed workflow is reported as missing its deadline only
when actual completion minus its deadline is at least 1.0 seconds. A
positive overrun below 1.0 seconds is reported as met. The lateness fields
retain the full overrun for actual misses; they do not subtract one second.

The shared AbstractWorkflowBroker classifies completion for all algorithms.
CBMWResultCollector updates deadlineMissCount, deadlineMissCountMain300,
metDeadline-derived success rates, countViolation, timeViolation, maximum
and average miss seconds. The detailed exporter updates results.txt delay;
its workbook uses the broker's outcome. Scheduling and admission deadlines
are unchanged. The included Python recovery script uses the same cutoff for
existing task_execution.csv files (with its usual CSV rounding limitation).

The Java paths start at the repository root. If your checkout still matches
the provided source, apply the patch; it updates only the relevant lines:

  unzip -j cbmw-deadline-one-second-updated-files.zip deadline-tolerance.patch -d /tmp
  cd /path/to/CBMW_NOSF_Ohio_middle300
  git apply --check /tmp/deadline-tolerance.patch
  git apply /tmp/deadline-tolerance.patch

The complete replacement Java source files are also provided under sources/.
The collector preserves the previously requested main-300 columns. If your
branch has additional local changes and the patch check fails, reconcile the
small patch with those changes before replacing any complete source file.

For the standalone recovery script:

  python3 cbmw_main300_cpu_share_deadlines.py /path/to/task_execution.csv

To run a small Linux simulation after applying the Java patch:

  python3 scripts/run_cbmw_config.py config/cbmw_experiments.txt --max-workflows 5

The new cutoff applies to future simulator results. Previous result files
remain unchanged; the recovery script can recompute the main-300 miss count
from an existing task_execution.csv. Rerunning the full experiment is needed
to regenerate the simulator's complete CSV and detailed workbook outputs.
