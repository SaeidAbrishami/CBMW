# CBMW ablation variants

Select these names in `cbmw.algorithms` or on the first line of a
`scripts/run_cbmw_config.py` configuration:

| Name | Static resource placement | Runtime behavior |
| --- | --- | --- |
| `CBMW-Early` | Forward sweep, parents before children, earliest feasible reserved slot | CBMW advancement and due-time substitution |
| `CBMW-NoAdvance` | Original latest feasible planner | Exact planned execution and container-request events; no early start or on-demand-to-reserved substitution |
| `CBMW-Early-NoAdvance` | Early planner | Exact planned events only |
| `CBMW-NoPlan` | DAG earliest/latest bounds only, with no resource booking or scheduled task start | Examine newly ready tasks and newly ready containers immediately; rescan waiting tasks every five seconds |

All variants use the common workload, conservative duration, reserved CPU/RAM
pool, 60-second on-demand provisioning model, and dedicated-container billing.
Early uses the original planner's rollback and recovery path if its primary
forward placement fails. A `PLAN-RECOVERED` detail-log entry identifies those
cases when detail logging is enabled.

NoPlan immediately tries a newly ready task on free reserved capacity. At a
round, it orders an on-demand container for each still-waiting ready task when
the next round would pass `LST - OPD`; if the deadline for the order has passed
when the task becomes ready, it orders immediately. A container-ready event
tries reserved capacity once more and otherwise dispatches to the container.
NoPlan accepts every valid arrival, so compare admission and success relative
to all submitted workflows alongside cost. Its output has blank *planned*
resource/start fields by design.

Run the nine representative arrival/deadline pairs in the supplied config:

```bash
python3 scripts/run_cbmw_config.py config/cbmw_ablation.txt \
  --output Output/ablation
```

Each scenario runs the complete 500-workflow trace and the paired 200-workflow
trace needed for marginal-cost accounting. Edit the config to select fewer
scenarios or algorithms; `--max-workflows 3` is available for a quick smoke
run and is not a substitute for a measured 300-workflow cohort.

The runner streams a `[progress]` bar with started/completed counts for every
algorithm, including the existing baselines. It updates every 60 wall-clock
seconds by default; set `-Dcbmw.progress.interval.sec=30` when launching the
Java simulation directly for shorter updates. A short smoke run may show only
the initial and final bars.
