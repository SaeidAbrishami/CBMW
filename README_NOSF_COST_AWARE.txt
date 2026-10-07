NOSF cost-aware rigid-task update

Apply this archive over the current CBMW repository after installing the
previous NOSF conditional-finish package. Only the NOSF resource selector,
its validation, the four-arrival runner, and its documentation are replaced.
It does not replace CBMW, CEWB, Greedy, or shared broker source files.

Policy: Among compatible existing and fresh VMs that meet the predicted NOSF
subdeadline, choose the smallest increase in rounded hourly rental cost.
Break cost ties by earliest finish. If no candidate meets the subdeadline,
choose the earliest finish (cost tie-break). Existing VMs may hold any number
of FIFO waiting tasks; their queued work contributes to predicted finish.
Fresh VMs include a 60-second provisioning delay. Provisioning is not billed;
hourly billing starts at readiness. Task runtimes remain rigid and RAM/CPU
requirements are enforced. This is an adaptation of NOSF Algorithm 3.

Install from the repository root:

  unzip -o /path/to/nosf_cost_aware_four_arrivals.zip -d .

Run four arrival rates 15, 30, 45, and 60 concurrently (four independent
JVMs), using the original workflow positions 101-400:

  python3 scripts/run_nosf_ohio_middle300.py --deadline-factor 1.2 --workers 4 \
    --workflow-dir test_workflows/workflows

Repeat with --deadline-factor 2 and --deadline-factor 4. The default output
root is outputs/nosf_cost_aware_middle300, separate from earlier NOSF runs.
Per-scenario CSVs are stored under arrival<rate>_alpha<factor>/middle300/.
Each factor's combined/results.csv, results_aggregate.csv, and
task_execution.csv are stored under combined/alpha<factor>/.

The runner compiles once, shows scenario progress, then starts up to four
JVMs. Each JVM has a 3 GiB maximum heap. On a 16 GiB server, choose
--workers 2 if memory pressure occurs.
