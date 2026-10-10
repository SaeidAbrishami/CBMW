NOSF pre-Option-C placement with CBMW planning runtime

Apply over the latest NOSF code (including the unlimited FIFO queue and
ready-time hourly billing correction). This patch replaces only NOSF's
resource selector and validation, the isolated main-300 runner, and its
experiment documentation. It does not replace shared CBMW, CEWB, Greedy,
or ablation files.

Compared with the earlier pre-C experiment, NOSF now plans with 1.20 times
the nominal runtime, as CBMW does. It retains the earlier NOSF active-first
resource selection: rank subdeadline-feasible active VMs by predicted
execution cost and idle time; otherwise lease the least-cost feasible new
VM; if none can meet the subdeadline, lease a new highest-ranking compatible
VM. An active VM may have any number of FIFO waiting tasks. Task runtimes are
rigid and CPU/RAM compatibility is enforced. Fresh VMs take 60 seconds to
provision. NOSF bills whole hours from VM readiness, excluding provisioning.

Install from the repository root:

    unzip -o /path/to/nosf_pre_c_120_main300_patch.zip -d .

Provide the original test_workflows/workflows directory, which includes the
XML/TXT files and four 500-workflow arrival manifests. The runner extracts
only positions 101 through 400 and re-bases their arrivals to zero.

Run 12 scenarios (arrival 15, 30, 45, 60 x deadline 1.2, 2, 4), at most
two concurrent JVMs, with progress in the terminal:

    python3 scripts/run_nosf_ohio_middle300.py --all --workers 2 \
      --workflow-dir test_workflows/workflows

Or run four arrival rates concurrently for one deadline factor:

    python3 scripts/run_nosf_ohio_middle300.py --deadline-factor 1.2 \
      --workers 4 --workflow-dir test_workflows/workflows

Repeat with deadline factors 2 and 4. On a 16-GiB machine, use --workers 2
if four 3-GiB Java heaps leave too little memory for the OS. The output
root is outputs/nosf_pre_c_120_middle300. Each factor has four-scenario
combined/results.csv, results_aggregate.csv, and task_execution.csv under
combined/alpha<factor>/.

Build and validate independently of the full workflow dataset:

    python3 -c 'from scripts.run_cewb_ohio_2026 import compile_sources; compile_sources()'
    java -ea -cp 'build_classes:lib/*' org.workflowsim.cbmw.baselines.NOSFValidationTest

The input workflow dataset is not included in this patch.
