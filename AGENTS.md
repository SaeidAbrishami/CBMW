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
java '-Dcbmw.algorithms=CBMW' '-Dcbmw.max.workflows=5' '-Dcbmw.output.dir=Output/smoke_tests/CBMW' '-Dcbmw.export.details=false' '-Dcbmw.detail.log=false' '-Dcbmw.quiet=true' -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

Entry point:

`examples/org/workflowsim/examples/cbmw/CBMWSimulation.java`

---

## Current Experiment Driver

`CBMWSimulation` now supports the new experiment matrix:

- Deadline classes: `tight=1.2`, `medium=2.0`, `loose=4.0`
- Load classes: `low=2.0`, `moderate=1.0`, `heavy=0.5`
- Algorithms: `CBMW`, `NOSF`, `CEWB`, `StaticGreedy`, `DynamicGreedy`
- Optional common-market CEWB comparisons: `CEWB-ReferencePolicy` uses the
  external implementation's PCP/absolute-slack task policy with current
  recovery; `CEWB-ReferenceAdapted` additionally preserves partial work across
  spot interruptions. Neither is part of the default 45-scenario matrix.
- Default workflow source: `Output/generated_datasets/test_workflows_sigma005_seed20260716/poisson_distribution.json`
- Default workflow count per scenario: 50 (selected from the 200-arrival source trace)
- Full default run size: 3 deadlines x 3 loads x 5 algorithms = 45 scenarios

Useful JVM switches:

| Switch | Purpose |
|--------|---------|
| `-Dcbmw.workflow.dir=Output/generated_datasets/test_workflows_sigma005_seed20260716` | Directory containing the workflow XML/TXT datasets and manifest. |
| `-Dcbmw.workflow.manifest=poisson_distribution.json` | Arrival manifest filename within the workflow directory. |
| `-Dcbmw.algorithms=CBMW` | Run only selected algorithms, comma-separated. |
| `-Dcbmw.output.dir=Output` | Root output folder; algorithm and comparison subfolders are created inside it. |
| `-Dcbmw.max.workflows=5` | Override the default 50-workflow cap per scenario. |
| `-Dcbmw.max.scenarios=1` | Stop after N completed scenarios. |
| `-Dcbmw.export.details=false` | Skip `.rar-style` detailed export folders. |
| `-Dcbmw.detail.log=false` | Disable `_detail.log` event logging. |
| `-Dcbmw.quiet=true` | Disable CloudSim console logs. |
| `-Dcbmw.generate.gantt=true` | Generate Gantt charts; normally keep false for speed. |
| `-Dcbmw.generate.comparison=false` | Skip comparison chart generation during per-VM runs. |
| `-Dcbmw.python=python3` | Python executable used for optional chart generation. |
| `-Dcbmw.runtime.quantile=0.99` | Paper alpha quantile used to derive conservative CBMW task durations. |
| `-Dcbmw.runtime.stddev.ratio=0.05` | Paper runtime uncertainty, sigma divided by mean runtime. |
| `-Dcbmw.negotiation.beta=1.0` | Workflow-level safety factor applied to the conservative critical path. |
| `-Dcbmw.negotiation.gamma=1.0` | Markup applied to CBMW's post-planning raw execution-cost quote. |
| `-Dcbmw.preemption.safety.sec=0` | Minimum post-preemption slack required before a running reserved task can be interrupted. |
| `-Dnosf.provisioning.delay.sec=0` | NOSF-only provisioning delay; zero is the faithful reference default. |
| `-Dnosf.billing.quantum.sec=60` | NOSF VM billing quantum in seconds. |
| `-Dnosf.vm.type.count=1` | Number of NOSF VM types; configure `nosf.vm.type.<i>.{name,cores,ram.mb,mips,price.per.sec}`. |
| `-Dcbmw.cewb.spot.mtbi.sec=3600` | Override mean time between spot interruptions for every CEWB class. |
| `-Dcbmw.cewb.spot.max.attempts=3` | Spot attempts before CEWB forces on-demand fallback. |
| `-Dcbmw.cewb.spot.min.success.prob=0.80` | Minimum predicted probability that a spot attempt survives. |
| `-Dcbmw.cewb.spot.total.cores=960` | Capacity-matched physical spot-core envelope; divided equally across the three fixed classes unless per-class capacities override it. |
| `-Dcbmw.cewb.pricing.policy=CONSTANT_PROFIT` | CEWB reconstructed pricing family; alternatives are `CONSTANT_DISCOUNT` and `PREDICTION_BASED`. |
| `-Dcbmw.cewb.criticality.ondemand=0.75` | Normalized criticality threshold for the most reliable on-demand class. |
| `-Dcbmw.cewb.reference.slack.base.sec=400.4` | Base absolute-slack boundary for the common-market reference policy; the other boundaries are 2x and 4x this value. |
| `-Dcbmw.cewb.reference.resume.progress=true` | Enable partial-progress recovery in `CEWB-ReferenceAdapted`; false retains its reference timing/classification but uses current escalation recovery. |

Linux VM helper for one algorithm:

```bash
scripts/run_algorithm.sh CBMW
scripts/run_algorithm.sh NOSF
scripts/run_algorithm.sh CEWB
scripts/run_algorithm.sh StaticGreedy
scripts/run_algorithm.sh DynamicGreedy
```

Current Ferdowsi VM inventory:

| VM | vCPUs | IP | SSH user | Local key path |
|----|-------|----|----------|----------------|
| CBMW simulation VM | 6 | `193.93.169.129` | `ubuntu` | `.secrets/CBMW-simulation-privateKey.pem` |

This VM was verified over SSH as `ubuntu` on 2026-07-10 and reports 6 CPUs.
The old per-algorithm Ferdowsi keys (`vm2.pem` through `vm5.pem`) were removed
from `.secrets/` because only the CBMW simulation key authenticates to the
current VM.

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
They also report provisioned on-demand VM count, aggregate on-demand VM
utilization, and deadline-risk task count.
Scenario and aggregate CSVs also report broker revenue and broker profit. For
paper-aligned CEWB these are settled by the selected reconstructed pricing
policy; other algorithms leave them at zero unless they implement pricing.

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

`plot_new_experiment.py` reads `Output/comparison/results_aggregate.csv` by
default and falls back to `Output/comparison/results.csv` if the aggregate file
is missing. It uses line charts for the 5-algorithm comparison.

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

- `WorkflowLoader` reads `Output/generated_datasets/test_workflows_sigma005_seed20260716/poisson_distribution.json`
  by default; `cbmw.workflow.dir` and `cbmw.workflow.manifest` can override it.
- Each entry parses the matching DAX XML and computes critical path.
- Deadline is `arrivalTime + criticalPath * tightness`.
- CBMW computes `cet = mu + z(alpha) * sigma` from the DAX mean runtime,
  with default `alpha=0.99` and `sigma=0.05*mu`, for negotiation and planning.
- After CBMW static planning, price negotiation sums each task's estimated
  duration multiplied by its planned reserved/on-demand price, applies
  `gamma`, and automatically accepts the quote because no user is simulated.
- Dynamic capacity checks and reserved-slot rebooking use the stored planning
  estimate (`cet` for CBMW), never the sampled actual runtime.
- A due task that was statically planned on reserved capacity is handled in the
  current scheduling cycle. Capacity assigned earlier in the same scheduler
  pass is included immediately, so later tasks do not wait for the next
  five-second period because of stale runtime-capacity bookkeeping.
- If no reserved VM can start such a task, CBMW considers only reserved tasks
  that are still executing before their planned SST. Among candidates that can
  individually release enough CPU and RAM, it rejects candidates whose
  post-preemption slack (`LFT - current time - remaining planning duration -`
  `waiting task planning duration`) is below
  `cbmw.preemption.safety.sec`, then selects the eligible task with greatest
  post-preemption slack. It preserves completed work, returns the victim to the
  ready queue, and dispatches the waiting task after a same-timestamp
  cancellation acknowledgement. Preemption history does not restrict
  eligibility. Once a task reaches its SST it is protected; if no deadline-safe
  pre-running victim exists, the waiting task stays queued for its originally
  planned reserved VM and is reconsidered immediately when reserved capacity
  is released instead of falling back to on-demand.
- Static-planner assignments to dummy resource `o0` are unaffected and still
  use dedicated on-demand containers.
- Following Algorithm 1 literally, a task that cannot be placed on reserved
  capacity is assigned to dummy resource `o0` at `SST = LST - OPD`. This value
  is not clamped to workflow arrival; Algorithm 3 provisions immediately when
  SST is past. For an entry task assigned to `o0`, planning includes the
  on-demand provisioning delay from workflow arrival. The workflow is rejected
  if that entry task would finish after its derived LFT, because its downstream
  path would then miss the workflow deadline.
- `applyPerturbedRuntimes()` replaces each task runtime from the matching
  `.txt` file.
- `cloudletLength = runtime_seconds * 1000`.
- Tasks are rigid: the DAX runtime is their wall-clock duration at the requested
  core count. Cores affect capacity and price, not automatic runtime speedup;
  reserved and logical on-demand execution use the same rule.

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
| `cbmw.ondemand.delay.sec` | 90.0 |
| `cbmw.scheduling.period.sec` | 5.0 |
| `cbmw.ondemand.min.billing.sec` | 60.0 |

Following Section 3.3 and Equation 1 of the paper, reserved capacity is treated
as prepaid and excluded from each run's scheduling cost. Reported `totalCost`
is therefore on-demand cost plus spot cost (spot is nonzero only for CEWB).
NOSF is consequently charged only for its on-demand execution and is not
charged for the CBMW reserved pool. This metric is scheduling cost, not full
operational expenditure including prepaid reservations.
On-demand cost is based on instance uptime. CBMW and the container-based
baselines create a dedicated logical container per on-demand assignment. NOSF
instead owns reusable logical VMs so it can minimize incremental billing cost.
Per-task requirements are read from
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
| CBMW | Paper-style EST/EFT/LFT backward sweep-line using estimated durations | Periodic LST-aware dispatch with current-cycle reserved-task replacement; only static `o0` assignments use on-demand |
| NOSF | Uncertainty-aware EST/EFT and proportional sub-deadline preprocessing | EDF, minimum incremental-cost reusable on-demand VM selection, deadline-risk fallback, and completion feedback |
| CEWB | HEFT-style ranks and proportional sub-deadlines | Dynamic slack classification, shared spot-VM containers, reliability escalation, and on-demand fallback |
| CEWB-ReferencePolicy | PCP sub-deadlines adapted from the external implementation | Absolute 400.4/800.8/1601.6-second slack classes and deterministic arrival/workflow/task ordering; current interruption escalation |
| CEWB-ReferenceAdapted | Same PCP sub-deadlines | Same absolute slack classes, with completed work retained and remaining work reclassified after interruption |
| StaticGreedy | Static round-robin reserved planning | Assigned VM, any reserved, then on-demand |
| DynamicGreedy | No static planning | First idle reserved, then on-demand FCFS |

CEWB has a separate configurable logical spot market with economy, standard,
and performance classes. Each class has independent cores, RAM, MIPS, base
price, capacity, and mean time between interruptions. Paper-aligned CEWB keeps
logical VMs alive and multiplexes task containers within CPU/RAM capacity.
Prices vary per instance; interruptions follow an exponential reliability
model, revoke every container on that VM, restart tasks, and escalate them.
The two `CEWB-Reference*` variants deliberately use this same spot market,
resource inventory, prices, capacities, startup delay, and interruption model.
They do not import the external implementation's AWS price histories or spot
instance definitions. `CEWB-ReferenceAdapted` is the exception to full-restart
recovery: it reduces the logical cloudlet to its remaining work after a
revocation, then retries or falls back after reclassification.
CEWB is charged no reserved-pool fixed cost, and spot cost/usage are exported
separately from on-demand cost/usage.
The default capacities are 320 economy, 160 standard, and 80 performance
instances: 320 physical cores per class and 960 in total. This is an explicitly
reported capacity-matched experimental environment, not a claimed CEWB paper
constant. `cbmw.cewb.spot.total.cores=192` restores the former 64/32/16 pool.
`CEWB-Reconstructed` retains the former safe-start/attempt-limit behavior.
All modes log configuration, classification decisions, peak capacity,
saturation, fallback, predicted-miss, wake, and partial-progress diagnostics.

---

## Latest Run Findings

The CBMW planner now uses the paper's alpha-quantile conservative execution
times for negotiation and static planning, then applies the `.txt` perturbed
runtimes only for actual execution. Detailed exports are aligned with the
reference archive shape:
`results.txt`, `TASK_EXECUTION_SUMMARY.xlsx`,
`WORKFLOW_COMPLETION_SUMMARY.xlsx`, and `ON_DEMAND_INSTANCE_USAGE.xlsx`.

The last distributed Ferdowsi run is stored under `Output/remote_full_paper/`.
All five algorithms completed all 9 scenarios (10 `results.csv` lines including
the header), and VM1 through VM5 were shut down after completion. This run was
performed before the alpha-quantile runtime correction and the latest
paper-alignment changes, so it is historical performance data, not validation
of the current implementation. A new full distributed run requires provisioning
additional Ferdowsi VMs; the current inventory has only one 6-core VM.

Validation smoke run:

```powershell
java '-Dcbmw.algorithms=CBMW' '-Dcbmw.max.workflows=2' '-Dcbmw.max.scenarios=1' '-Dcbmw.output.dir=Output/smoke_tests/CBMW_reference_env' '-Dcbmw.export.details=true' '-Dcbmw.detail.log=false' '-Dcbmw.quiet=true' '-Dcbmw.generate.gantt=false' '-Dcbmw.generate.comparison=false' -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

The run compiled and completed, producing the `.rar-style` detailed folder.

CEWB invariant and smoke validation:

```powershell
.\scripts\test_cewb.ps1
```

NOSF invariant and smoke validation:

```powershell
.\scripts\test_nosf.ps1
```

---

## Recent Performance Fixes

- `CBMWLogger` can be disabled with `-Dcbmw.detail.log=false`.
- `CBMWLogger` no longer flushes on every event write.
- `WorkflowParser` now deduplicates required files with a `HashSet`; the old
  indexed scan over a `LinkedList` became cubic for SIPHT tasks with hundreds
  of input files.
- `HybridVmPool` uses constant-time VM lookup and on-demand removal.
- Paper-style on-demand containers are dedicated and task-sized for CBMW and
  the container-based baselines; NOSF uses reusable logical VMs. They execute
  through a lightweight logical path rather than registering tens of thousands
  of heavyweight CloudSim VMs.
- Disabled `CBMWLogger` calls no longer format hot-path task/container messages.
- StaticGreedy tracks per-resource-slot availability with priority queues
  instead of copying, sorting, and rescanning all prior bookings for each task.
- StaticGreedy runtime dispatch is event-driven, keeps only one effective
  planned-start wake-up, does not trigger global rescheduling for container
  order events, and sorts only tasks whose planned start has already arrived.
  This removes the ready-queue event storm seen under moderate/heavy load.
- `WorkflowRecord` tracks completed task IDs.
- `AbstractWorkflowBroker.updateWorkflowCompletion()` no longer scans all
  received cloudlets for every task completion.
- `CBMWStaticPlanningAlgorithm.findLatestFeasibleSlot()` avoids repeated
  `overlapCount()` rescans inside the candidate loop and checks true concurrent
  CPU/RAM usage with an event-based resource profile rather than summing serial
  bookings as if they overlapped.
- `CBMWSimulation` saves `results.csv` and `results_aggregate.csv` after each
  completed scenario.
- CBMW accounts for reserved CPU/RAM selected earlier in the same scheduling
  pass before considering later ready tasks. Due reserved-planned tasks that no
  longer fit trigger same-timestamp replacement of an eligible pre-running task.
  Victims are chosen by maximum post-preemption deadline slack subject to
  CPU/RAM fit and the configured safety margin, preserve partial progress, and
  may be preempted repeatedly while still before SST. If no deadline-safe
  victim exists, the waiting task waits for its planned reserved VM.

Historical paper-container validation: all five algorithms completed a
detailed-output five-workflow smoke run. The former dedicated-container NOSF
completed 200 workflows in 118 seconds, but those results predate the reusable
reference implementation and must be rebenchmarked. A full 200-workflow CBMW
scenario also completed.

---

## Known Remaining Issues

- Full 200-workflow scenarios should be rebenchmarked after the latest planner
  and runtime-quantile changes and the current-cycle replacement policy.
- The paper does not state a precise experimental beta value; the default is
  the minimum valid value `1.0` and must be reported with each experiment.
- The paper does not state a precise experimental gamma value; the price-markup
  default is `1.0` and must be reported with each experiment.
- `NegotiationModule.remainingCP()` still needs cycle detection.
- NOSF is a paper-informed reconstruction because its source article is not
  included and its full pseudocode could not be verified. The reusable VM
  scheduler supports configurable heterogeneous types, but the default
  experiment still provides one homogeneous type.
- CEWB's resource behavior is now explicit rather than borrowing reserved VMs,
  but its configurable spot-market defaults remain simulation assumptions: the
  external paper's full pseudocode and experimental market constants are not
  included in this repository.
