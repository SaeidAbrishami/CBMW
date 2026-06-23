# CBMW Workflow Simulation

University research project implementing the CBMW (Cost-efficient Broker for
Multiple Workflows) algorithm on top of WorkflowSim 1.0 / CloudSim 3.0.3.

Goal: simulate a cloud broker that schedules scientific workflows on a hybrid
reserved/on-demand VM pool, then compare deadline satisfaction rate and cost
against paper-style baselines and greedy baselines.

---

## Build And Run

Compile from the project root on Windows PowerShell:

```powershell
$files = Get-ChildItem -Path sources,examples -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -cp "lib/*" -d bin $files
```

Run from compiled classes:

```powershell
java -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

Fast CBMW-only smoke run:

```powershell
java '-Dcbmw.algorithms=CBMW' '-Dcbmw.max.workflows=5' '-Dcbmw.export.details=false' '-Dcbmw.detail.log=false' '-Dcbmw.quiet=true' -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

Entry point:

`examples/org/workflowsim/examples/cbmw/CBMWSimulation.java`

---

## Current Experiment Driver

`CBMWSimulation` now supports the new experiment matrix:

- Deadline classes: `tight=1.2`, `medium=2.0`, `loose=4.0`
- Load classes: `low=2.0`, `moderate=1.0`, `heavy=0.5`
- Algorithms: `CBMW`, `NOSF`, `CEWB`, `StaticGreedy`, `DynamicGreedy`
- Default workflow source: `test_workflows/poisson_distribution.json`
- Default workflow count per scenario: 200
- Full default run size: 3 deadlines x 3 loads x 5 algorithms = 45 scenarios

Useful JVM switches:

| Switch | Purpose |
|--------|---------|
| `-Dcbmw.algorithms=CBMW` | Run only selected algorithms, comma-separated. |
| `-Dcbmw.output.dir=Output` | Root output folder; algorithm and comparison subfolders are created inside it. |
| `-Dcbmw.max.workflows=5` | Cap workflows per scenario for smoke/debug runs. |
| `-Dcbmw.max.scenarios=1` | Stop after N completed scenarios. |
| `-Dcbmw.export.details=false` | Skip `.rar-style` detailed export folders. |
| `-Dcbmw.detail.log=false` | Disable `_detail.log` event logging. |
| `-Dcbmw.quiet=true` | Disable CloudSim console logs. |
| `-Dcbmw.generate.gantt=true` | Generate Gantt charts; normally keep false for speed. |
| `-Dcbmw.generate.comparison=false` | Skip comparison chart generation during per-VM runs. |
| `-Dcbmw.python=python3` | Python executable used for optional chart generation. |

Linux VM helper for one algorithm:

```bash
scripts/run_algorithm.sh CBMW
scripts/run_algorithm.sh NOSF
scripts/run_algorithm.sh CEWB
scripts/run_algorithm.sh StaticGreedy
scripts/run_algorithm.sh DynamicGreedy
```

Current Ferdowsi VM inventory:

| VM | Algorithm | IP | SSH user | Local key path |
|----|-----------|----|----------|----------------|
| VM1 | `CBMW` | `193.93.169.129` | `ubuntu` | `.secrets/CBMW-simulation-privateKey.pem` |
| VM2 | `NOSF` | `193.93.169.137` | `ubuntu` | `.secrets/vm2.pem` |
| VM3 | `CEWB` | `193.93.169.106` | `ubuntu` | `.secrets/vm3.pem` |
| VM4 | `StaticGreedy` | `193.93.169.86` | `ubuntu` | `.secrets/vm4.pem` |
| VM5 | `DynamicGreedy` | `193.93.169.53` | `ubuntu` | `.secrets/vm5.pem` |

All current VMs have been verified with SSH as `ubuntu` and passwordless
`sudo`. The previous VM4 IP `193.93.169.114` was replaced because SSH timed out
during banner exchange.

After collecting `Output/algorithms/<algorithm>/` folders from separate VMs
onto one machine, rebuild combined comparison CSVs and charts:

```bash
python scripts/merge_algorithm_outputs.py Output
```

Results are now saved after each completed scenario, not only at the end of the
full run.

---

## Outputs

Main outputs:

- `Output/algorithms/<algorithm>/results.csv`
- `Output/algorithms/<algorithm>/results_aggregate.csv`
- `Output/comparison/results.csv`
- `Output/comparison/results_aggregate.csv`
- `Output/comparison/new_experiment_low.png`
- `Output/comparison/new_experiment_moderate.png`
- `Output/comparison/new_experiment_heavy.png`

Detailed `.rar-style` outputs, when `cbmw.export.details=true`:

- `results.txt`
- `TASK_EXECUTION_SUMMARY.xlsx`
- `WORKFLOW_COMPLETION_SUMMARY.xlsx`
- `ON_DEMAND_INSTANCE_USAGE.xlsx`

`Output/` is ignored by git.

`plot_new_experiment.py` reads `Output/results_aggregate.csv` by default and
falls back to `Output/results.csv` if the aggregate file is missing. It uses
line charts for the 5-algorithm comparison.

---

## Source Layout

```text
sources/org/workflowsim/cbmw/
  AbstractWorkflowBroker.java
  CBMWBroker.java
  CBMWStaticPlanningAlgorithm.java
  CBMWDynamicSchedulingAlgorithm.java
  HybridVmPool.java
  NegotiationModule.java
  ProvisioningModule.java
  WorkflowLoader.java
  WorkflowArrivalData.java
  WorkflowRecord.java
  CBMWAccounting.java
  CBMWDetailedResultExporter.java
  CBMWResultCollector.java
  CBMWLogger.java
  baselines/
    NOSFBroker.java
    CEWBBroker.java
    StaticGreedyBroker.java
    DynamicGreedyBroker.java

examples/org/workflowsim/examples/cbmw/
  CBMWSimulation.java

test_workflows/
  CyberShake_100_1.xml .. _25.xml
  CyberShake_1000_1.xml .. _25.xml
  Inspiral_100/1000 x 25 variants
  Montage_100/1000 x 25 variants
  Sipht_100/1000 x 25 variants
  matching .txt perturbed runtime files
  poisson_distribution.json
```

---

## Simulation Design

### Workflow Input

- `WorkflowLoader` reads `test_workflows/poisson_distribution.json`.
- Each entry parses the matching DAX XML and computes critical path.
- Deadline is `arrivalTime + criticalPath * tightness`.
- `applyPerturbedRuntimes()` replaces each task runtime from the matching
  `.txt` file.
- `cloudletLength = runtime_seconds * 1000`.

### VM Model

Current configurable defaults in `HybridVmPool`:

| Property | Default |
|----------|---------|
| `cbmw.reserved.instances` | 50 |
| `cbmw.reserved.cores` | 192 |
| `cbmw.reserved.ram.mb` | 786432 |
| `cbmw.ondemand.cores` | 32 |
| `cbmw.ondemand.ram.mb` | 64000 |
| `cbmw.task.cores` | 1 |
| `cbmw.task.ram.mb` | 0 |
| `cbmw.reserved.hourly.cost` | 3.26 |
| `cbmw.ondemand.per.sec` | 0.000340 |
| `cbmw.ondemand.delay.sec` | 120.0 |

Reserved cost is fixed by makespan. On-demand cost is based on instance uptime.

### Broker Hierarchy

All brokers extend `AbstractWorkflowBroker`.

| Broker | planWorkflow | processCloudletUpdate |
|--------|--------------|-----------------------|
| CBMW | Backward sweep-line, LST-aware slot booking | LST-aware dynamic dispatch with on-demand fallback |
| NOSF | On-demand-only approximation | Dispatch to on-demand |
| CEWB | Low-cost/revocable approximation | Reserved/spot-style dispatch |
| StaticGreedy | Static round-robin reserved planning | Assigned VM, any reserved, then on-demand |
| DynamicGreedy | No static planning | First idle reserved, then on-demand FCFS |

CEWB is an approximation because the simulator does not have a true spot-market
pool.

---

## Latest Run Findings

CBMW-only full-size runs are still too slow:

- `-Dcbmw.algorithms=CBMW` with 200 workflows did not finish the first scenario
  before timeout.
- `-Dcbmw.algorithms=CBMW -Dcbmw.max.workflows=40` also did not finish the
  first scenario within 15 minutes.
- `-Dcbmw.algorithms=CBMW -Dcbmw.max.workflows=5` completed all 9 load/deadline
  scenarios in about 3 minutes and generated CSVs/plots.

Reason: each full scenario has about 200 workflows and roughly 110,000 tasks
(100 small workflows plus 100 large workflows). CBMW static planning does
task-by-task reserved slot search, so full-size scenarios are currently
computationally expensive.

Current generated outputs are therefore valid CBMW-only smoke/subset outputs
when `cbmw.max.workflows=5`; they are not full 200-workflow paper-scale results.

---

## Recent Performance Fixes

- `CBMWLogger` can be disabled with `-Dcbmw.detail.log=false`.
- `CBMWLogger` no longer flushes on every event write.
- `WorkflowRecord` tracks completed task IDs.
- `AbstractWorkflowBroker.updateWorkflowCompletion()` no longer scans all
  received cloudlets for every task completion.
- `CBMWStaticPlanningAlgorithm.findLatestFeasibleSlot()` avoids repeated
  `overlapCount()` rescans inside the candidate loop.
- `CBMWSimulation` saves `results.csv` and `results_aggregate.csv` after each
  completed scenario.

---

## Known Remaining Issues

- CBMW full 200-workflow scenarios remain too slow; optimize static planning
  before attempting the full 45-scenario experiment.
- `releaseSlot` still uses exact floating-point equality for slot removal.
- `NegotiationModule.remainingCP()` still needs cycle detection.
- Task sub-deadlines are intentionally not used in this project.
