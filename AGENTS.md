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
.\scripts\build.ps1
```

The script uses a temporary `javac` argument file to avoid Windows
command-length limits and compiles into a temporary staging directory. After a
successful compile, it recursively replaces `.class` files in `bin/`, removing
stale bytecode while preserving non-class launchers and the last good build on
failure.

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
| `-Dcbmw.runtime.quantile=0.90` | Paper alpha quantile used to derive conservative CBMW task durations. |
| `-Dcbmw.runtime.stddev.ratio=0.10` | Paper runtime uncertainty, sigma divided by mean runtime. |
| `-Dcbmw.negotiation.beta=1.0` | Workflow-level safety factor applied to the conservative critical path. |
| `-Dcbmw.negotiation.gamma=1.0` | Markup applied to CBMW's post-planning raw execution-cost quote. |
| `-Dcbmw.cewb.spot.mtbi.sec=3600` | Override mean time between spot interruptions for every CEWB class. |
| `-Dcbmw.cewb.spot.max.attempts=3` | Spot attempts before CEWB forces on-demand fallback. |
| `-Dcbmw.cewb.spot.min.success.prob=0.80` | Minimum predicted probability that a spot attempt survives. |

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
- `Output/algorithms/<algorithm>/task_execution.csv`
- `Output/comparison/results.csv`
- `Output/comparison/results_aggregate.csv`
- `Output/comparison/new_experiment_low.png`
- `Output/comparison/new_experiment_moderate.png`
- `Output/comparison/new_experiment_heavy.png`

Scenario CSVs distinguish admission from execution success: `acceptanceRate`
is accepted/total, the legacy `deadlineRate` remains met/accepted, and
`overallSuccessRate` is met/total. They also include rejected workflow counts
split into negotiation and planning failures.

Detailed `.rar-style` outputs, when `cbmw.export.details=true`:

- `results.txt`
- `TASK_EXECUTION_SUMMARY.xlsx`
- `TASK_EXECUTION_SUMMARY.csv`
- `WORKFLOW_COMPLETION_SUMMARY.xlsx`
- `ON_DEMAND_INSTANCE_USAGE.xlsx`

The algorithm-level `task_execution.csv` is always written incrementally after
each completed scenario, even when detailed XLSX export is disabled. It records
the scenario and algorithm, workflow disposition, task identity/dependencies,
`mu`, `sigma`, paper `cet`, the runtime estimate actually used by the selected
algorithm, sampled actual runtime, EST/EFT/LST/LFT/SST, planned and actual
resources, provisioning/ready/submit/start/finish times, derived delays,
rescheduling classification, execution status, and deadline tightness. Tasks
from rejected workflows are included with a rejection reason and blank actual
execution fields.

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
    NOSFWorkflowPlanner.java
    CEWBBroker.java
    CEWBSpotMarket.java
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
- CBMW computes `cet = mu + z(alpha) * sigma` from the DAX mean runtime,
  with default `alpha=0.90` and `sigma=0.10*mu`, for negotiation and planning.
- After CBMW static planning, price negotiation sums each task's estimated
  duration multiplied by its planned reserved/on-demand price, applies
  `gamma`, and automatically accepts the quote because no user is simulated.
- Dynamic capacity checks and reserved-slot rebooking use the stored planning
  estimate (`cet` for CBMW), never the sampled actual runtime.
- Following Algorithm 1 literally, a task that cannot be placed on reserved
  capacity is assigned to dummy resource `o0` at `SST = LST - OPD`. This value
  is not clamped to workflow arrival and does not trigger a second static
  feasibility rejection; Algorithm 3 provisions immediately when SST is past.
- `applyPerturbedRuntimes()` replaces each task runtime from the matching
  `.txt` file.
- `cloudletLength = runtime_seconds * 1000`.

### VM Model

Current configurable defaults in `HybridVmPool`:

| Property | Default |
|----------|---------|
| `cbmw.reserved.instances` | 5 |
| `cbmw.reserved.cores` | 192 |
| `cbmw.reserved.ram.mb` | 384000 |
| `cbmw.task.cores` | 1 |
| `cbmw.task.ram.mb` | 1 |
| `cbmw.reserved.hourly.cost` | 3.26 |
| `cbmw.ondemand.per.sec` | 0.000340 |
| `cbmw.ondemand.cpu.per.core.sec` | Value of `cbmw.ondemand.per.sec` |
| `cbmw.ondemand.memory.per.gb.sec` | 0.0 |
| `cbmw.ondemand.delay.sec` | 120.0 |
| `cbmw.scheduling.period.sec` | 5.0 |
| `cbmw.provisioner.period.sec` | 15.0 |
| `cbmw.ondemand.min.billing.sec` | 60.0 |

Reserved cost is fixed by makespan. On-demand cost is based on instance uptime.
Following the paper, every on-demand assignment creates one dedicated logical
container sized exactly like its task. Per-task requirements are read from
common DAX attributes/profile keys (`cores`, `cpu`, `num_procs`, `ram`, or
`memory`); `cbmw.task.cores` and `cbmw.task.ram.mb` are explicit fallbacks.
The supplied DAX files do not contain CPU/RAM metadata, so those defaults still
apply uniformly unless enriched workflows are supplied. On-demand prices scale
with task cores and RAM using the configurable CPU/memory per-second rates.
Logical
containers keep independent IDs, OPD, lifecycle, one-minute billing, and
output rows, but bypass CloudSim host registration to avoid treating
serverless containers as heavyweight VMs.

Supported task metadata examples:

```xml
<job ... cores="4" memory="2048MB" />
<profile namespace="pegasus" key="cores">4</profile>
<profile namespace="pegasus" key="ram">2GB</profile>
```

### Broker Hierarchy

All brokers extend `AbstractWorkflowBroker`.

| Broker | planWorkflow | processCloudletUpdate |
|--------|--------------|-----------------------|
| CBMW | Paper-style EST/EFT/LFT backward sweep-line using estimated durations | Periodic LST-aware dynamic dispatch with on-demand fallback |
| NOSF | Paper-informed uncertainty-aware EST/EFT and sub-deadline preprocessing | EST-priority, cost-aware on-demand dispatch with completion feedback |
| CEWB | Computes task safe-start/sub-deadline timing for spot selection | Explicit spot-class selection, interruption/retry, and on-demand fallback |
| StaticGreedy | Static round-robin reserved planning | Assigned VM, any reserved, then on-demand |
| DynamicGreedy | No static planning | First idle reserved, then on-demand FCFS |

CEWB has a separate configurable logical spot market with economy, standard,
and performance classes. Each class has independent cores, RAM, MIPS, base
price, capacity, and mean time between interruptions. Prices vary per attempt;
interruptions follow an exponential reliability model and restart the task.
CEWB is charged no reserved-pool fixed cost, and spot cost/usage are exported
separately from on-demand cost/usage.

---

## Latest Run Findings

The CBMW planner now uses the paper's alpha-quantile conservative execution
times for negotiation and static planning, then applies the `.txt` perturbed
runtimes only for actual execution. Detailed exports are aligned with the reference archive shape:
`results.txt`, `TASK_EXECUTION_SUMMARY.xlsx`,
`WORKFLOW_COMPLETION_SUMMARY.xlsx`, and `ON_DEMAND_INSTANCE_USAGE.xlsx`.

Validation smoke run:

```powershell
java '-Dcbmw.algorithms=CBMW' '-Dcbmw.max.workflows=2' '-Dcbmw.max.scenarios=1' '-Dcbmw.output.dir=Output/validation_reference_env_smoke' '-Dcbmw.export.details=true' '-Dcbmw.detail.log=false' '-Dcbmw.quiet=true' '-Dcbmw.generate.gantt=false' '-Dcbmw.generate.comparison=false' -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

The run compiled and completed, producing the `.rar-style` detailed folder.

---

## Recent Performance Fixes

- `CBMWLogger` can be disabled with `-Dcbmw.detail.log=false`.
- `CBMWLogger` no longer flushes on every event write.
- `WorkflowParser` now deduplicates required files with a `HashSet`; the old
  indexed scan over a `LinkedList` became cubic for SIPHT tasks with hundreds
  of input files.
- `HybridVmPool` uses constant-time VM lookup and on-demand removal.
- Paper-style on-demand containers are dedicated and task-sized. They execute
  through a lightweight logical path rather than registering tens of thousands
  of heavyweight CloudSim VMs.
- Disabled `CBMWLogger` calls no longer format hot-path task/container messages.
- StaticGreedy tracks per-resource-slot availability with priority queues
  instead of copying, sorting, and rescanning all prior bookings for each task.
- `WorkflowRecord` tracks completed task IDs.
- `AbstractWorkflowBroker.updateWorkflowCompletion()` no longer scans all
  received cloudlets for every task completion.
- `CBMWStaticPlanningAlgorithm.findLatestFeasibleSlot()` avoids repeated
  `overlapCount()` rescans inside the candidate loop and checks true concurrent
  CPU/RAM usage with an event-based resource profile rather than summing serial
  bookings as if they overlapped.
- `CBMWSimulation` saves `results.csv` and `results_aggregate.csv` after each
  completed scenario.

Paper-container validation: all five algorithms completed a detailed-output
five-workflow smoke run. A clean full 200-workflow NOSF scenario (the worst
case, because every task uses on-demand) completed in 118 seconds with logical
dedicated containers. A full 200-workflow CBMW scenario also completed.

---

## Known Remaining Issues

- Full 200-workflow scenarios should be rebenchmarked after the latest planner
  and runtime-quantile changes.
- The paper does not state a precise experimental beta value; the default is
  the minimum valid value `1.0` and must be reported with each experiment.
- The paper does not state a precise experimental gamma value; the price-markup
  default is `1.0` and must be reported with each experiment.
- `NegotiationModule.remainingCP()` still needs cycle detection.
- NOSF is a paper-informed reconstruction because its source article is not
  included and its full pseudocode could not be verified. The current
  single-type dedicated-container environment also collapses heterogeneous VM
  selection and utilization tie-breaking to one candidate.
- CEWB's resource behavior is now explicit rather than borrowing reserved VMs,
  but its configurable spot-market defaults remain simulation assumptions: the
  external paper's full pseudocode and experimental market constants are not
  included in this repository.
