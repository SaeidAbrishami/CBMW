NOSF conditional-finish update (for the existing CBMW Codex branch)

Extract this archive over the repository root. It contains only NOSF source,
the NOSF runner, and its documentation. Existing CBMW, CEWB, and Greedy files
are not replaced. The runner imports scripts/run_cewb_ohio_2026.py from the
current repository and compiles Java sources before running.

Run one deadline factor across four independent scenario JVMs:

  python3 scripts/run_nosf_ohio_middle300.py --deadline-factor 1.2 --workers 4 \
    --workflow-dir test_workflows/workflows --output outputs/nosf_finish_comparison

Factors: 1.2, 2, 4. For each factor, arrivals 15, 30, 45, and 60 run
concurrently; each uses only original workflow positions 101-400.

After all four finish, find combined results at:
  outputs/nosf_finish_comparison/combined/alpha1.2/results.csv
  outputs/nosf_finish_comparison/combined/alpha1.2/results_aggregate.csv
  outputs/nosf_finish_comparison/combined/alpha1.2/task_execution.csv

Per-scenario results and progress logs remain in their own scenario folders.
The combined task CSV can be several hundred MB; it is written by streaming.
The hourly billing clock begins when the VM becomes ready (after 60 seconds).
