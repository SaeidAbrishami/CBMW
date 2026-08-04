# CBMW Workflow Simulation

Implementation of the **CBMW** (Cost-efficient Broker for Multiple Workflows) algorithm on top of [WorkflowSim 1.0](https://github.com/WorkflowSim/WorkflowSim-1.0) / CloudSim 3.0.3.

CBMW manages a hybrid pool of reserved VMs (fixed hourly cost) and on-demand containers (per-second cost) to schedule dynamically arriving scientific workflows while meeting user-specified deadlines at minimum cost.

### Task Runtime Meaning

The task mean runtime `mu` represents the task's complete expected service time:

```text
mu = computation time + shared-storage input/output access time
```

The simulator therefore does not add a separate shared-storage or dependency
file-transfer delay. The conservative planning duration `cet` is derived from
this combined `mu`, and the matching `.txt` value is the perturbed sample of the
same combined runtime used for actual execution.

Queue waiting, scheduler delay, and on-demand provisioning delay (`OPD`) are
separate from task runtime. Reserved-container startup is currently not added
separately; it is represented only when it is already included in the supplied
DAX/runtime measurement.

### Generating Reproducible Perturbed Runtimes

`test_workflows/duplicate_and_process.py` creates a separate, self-contained
workflow dataset from every entry in an arrival manifest. It copies the
referenced XML files and manifest, generates one normally distributed runtime
sample per task, and writes `runtime_generation_metadata.json` with the seed,
configuration, empirical standard deviation, and CET exceedance rate. The
source dataset is never modified, and the destination must be new or empty.

Generate the paper-aligned dataset with `sigma/mu = 0.05`, `alpha = 0.99`, and
a fixed seed:

```powershell
python test_workflows/duplicate_and_process.py `
  --source-dir test_workflows `
  --manifest poisson_distribution.json `
  --output-dir Output/generated_datasets/test_workflows_sigma005_seed20260716 `
  --stddev-ratio 0.05 `
  --quantile 0.99 `
  --seed 20260716
```

The generator processes all 200 manifest workflows rather than only a subset
of filename variants. It validates that every XML exists, every job has a
finite runtime, and every generated runtime is positive. For the current
109,125-task manifest, a 99th-percentile CET should be exceeded by about 1,091
tasks; the approximate 95% binomial count interval is 1,027-1,156. Sampling
variation means the result is not required to equal exactly 1%.

The generated directory is the experiment driver's default workflow source.
The explicit workflow property below is optional, but shown to make the input
dataset unambiguous in recorded experiment commands:

```powershell
java `
  '-Dcbmw.workflow.dir=Output/generated_datasets/test_workflows_sigma005_seed20260716' `
  '-Dcbmw.workflow.manifest=poisson_distribution.json' `
  '-Dcbmw.runtime.quantile=0.99' `
  '-Dcbmw.runtime.stddev.ratio=0.05' `
  '-Dcbmw.algorithms=CBMW' `
  '-Dcbmw.output.dir=Output/validation_sigma005_seed20260716' `
  '-Dcbmw.export.details=false' `
  '-Dcbmw.detail.log=false' `
  '-Dcbmw.quiet=true' `
  -cp 'bin;lib/*' `
  org.workflowsim.examples.cbmw.CBMWSimulation
```

Existing `.txt` files are intentionally not updated in place. Changing the
generator does not alter an old dataset; a new output directory must be
generated before running the default configuration. A different dataset can be
selected with `cbmw.workflow.dir`.

---

## Algorithm Overview

CBMW processes each workflow arrival through four sequential modules:

| Module | Class | Role |
|--------|-------|------|
| 1. Negotiation | `NegotiationModule` | Checks deadline feasibility, invokes the completed static plan to estimate raw execution cost, applies markup `gamma`, and automatically accepts the quote |
| 2. Static Planning | `CBMWStaticPlanningAlgorithm` | Backward sweep assigns each task a reserved VM slot at its Latest Start Time (LST = deadline − remainingCP) |
| 3. Dynamic Scheduling | `CBMWDynamicSchedulingAlgorithm` | Dispatches ready tasks to reserved capacity and applies current-cycle task replacement when a due reserved-planned task cannot start |
| 4. Provisioning | `ProvisioningModule` | Spins up and terminates on-demand VMs; tracks per-task costs |

The backward sweep in Module 2 deliberately defers reservations to the latest feasible slot, keeping earlier capacity free for workflows that have not yet arrived.

When reserved `TaskPlanner` placement fails, Algorithm 1 is followed literally:
the task is assigned to dummy on-demand resource `o0` with
`SST = LST - on-demand provisioning delay`. Static planning does not add a
second on-demand feasibility rejection or clamp SST to workflow arrival. If SST
is already in the past when the workflow arrives, Algorithm 3 orders the
container immediately; such a task can still miss its deadline.

For a task that was planned on a reserved VM and has reached its SST, runtime
capacity exhaustion is handled in the current scheduling cycle. The scheduler
first checks other reserved VMs. If none can run the task, the broker considers
only reserved tasks that are currently executing before their planned SST. It
selects the candidate with the greatest post-preemption slack (`LFT - current
time - remaining planning duration - waiting task planning duration`) that can
individually release enough CPU and RAM. The candidate is eligible only when
this slack is at least `cbmw.preemption.safety.sec` (zero by default).
Completed work is preserved, the victim returns to the ready queue, and the
waiting task takes its VM after the same-timestamp cancellation acknowledgement.
Preemption history does not affect eligibility, so a task may be preempted more
than once while it is still pre-running. Once current time reaches its SST, it
is protected. If no deadline-safe pre-running victim exists, the waiting task
stays queued for its originally planned reserved VM and is reconsidered as soon
as reserved capacity is released; it does not fall back to on-demand. Static
`o0` assignments retain their normal on-demand provisioning path.

### CBMW Price Negotiation

After a deadline-feasible workflow is statically planned, CBMW computes the
paper's price quote:

```text
raw cost = sum(planned task duration * allocated resource price)
offered price = gamma * raw cost
```

Reserved tasks use the reserved VM's per-second price. On-demand tasks use the
task-sized container price and the configured minimum billing duration. The
markup is configured with `cbmw.negotiation.gamma` and defaults to `1.0`
because the paper does not publish an experimental gamma value.

There is no simulated human user, so every generated price quote is marked
`AUTO_ACCEPTED`. The automatic user decision does not bypass the paper's
deadline-feasibility check or a genuine static-planning failure. Quotes are
written to the detail log, workflow completion summary, `results.txt`, and the
scenario CSV columns `estimatedRawCost` and `offeredPrice`.

### NOSF Baseline

`NOSFBroker` implements the original NOSF article's three-stage online
scheduler:

1. **Workflow preprocessing (Algorithm 1):** use the normal-runtime paper
   weight `w(lambda)=mu+sigma`, calculate EST/EFT/LCT with Eqs. 8-10, find PCP
   paths, assign Eq. 11 sub-deadlines, and retain each task's delta from Eq. 12.
2. **Resource allocation (Algorithm 3):** order ready tasks by paper priority,
   allow at most one waiting task per VM, select a sub-deadline-feasible active
   VM by minimum `price * predicted execution` and then minimum idle time, or
   provision a suitable new type. If none is feasible, provision the
   highest-ranking compatible type and mark the task deadline-risk.
3. **Feedback (Algorithm 2):** update only immediate successors that have become
   ready and apply Eqs. 16-18, preserving the original delta and LCT cap.

The article is inconsistent about whether initial priority is EST or EFT.
`nosf.priority.policy=EST` is the documented default because it follows
Algorithm 3's operational prose; `EFT` is available for sensitivity analysis.
NOSF has two explicit experiment profiles:

- `COMMON_MARKET` (default) uses one configurable homogeneous VM type,
  60-second billing, common shared storage, and one run.
- `PAPER_ALIGNED` uses the paper's seven Table 2 EC2 types and slowdown
  factors, hourly billing, 100-Mbps network transfers with zero same-VM edge
  cost, and 30 independent seeded repetitions.

The paper-aligned profile deliberately retains the common comparison controls:
the project's three deadline factors, the same workflow population, the shared
`cbmw.runtime.stddev.ratio`, and `cbmw.ondemand.delay.sec=90` rather than the
paper's 97-second boot time. Explicit NOSF VM, billing, transfer, or repetition
properties can still override profile defaults for sensitivity experiments.

### CEWB Spot Baseline

`CEWBBroker` now has explicit paper-oriented and historical modes. `CEWB`
uses PCP sub-deadlines, paper interruption-penalty slack classes, reusable
physical VM pools, and checkpoint recovery. `CEWB-Reconstructed` preserves
the earlier safe-start/attempt-limit policy. Paper-oriented CEWB:

1. assigns PCP sub-deadlines and recomputes ready-task slack;
2. maps absolute slack to on-demand/high/medium/low reliability using the
   paper's 400.4-second interruption-penalty boundary;
3. filters spot classes by task cores/RAM, current capacity, bid price,
   predicted sub-deadline finish, and interruption success probability;
4. executes task containers in reusable logical spot VMs with CPU/RAM slots;
5. provisions reusable 32-core on-demand VMs periodically and best-fit packs
   0.4-second task containers onto their idle CPU/RAM;
6. retains completed work across spot interruption, applies snapshot/restore
   delay, and reclassifies the remaining task work.

The experiment matrix deliberately keeps a `tight=1.2` stress case, below the
paper's lowest tested deadline factor of 1.4. CEWB runs that case by default so
its behavior remains observable. Set
`-Dcbmw.cewb.admission.min.cp.multiplier=1.4` to enable the paper-bound
admission guard and reject infeasible deadline spans before capacity is spent.

Default spot classes are explicit simulation assumptions:

| Class | Cores | RAM | MIPS | Base price/s | MTBI | Capacity |
|-------|------:|----:|-----:|-------------:|-----:|---------:|
| economy | 1 | 1024 MB | 900 | 0.000085 | 1800 s | 320 |
| standard | 2 | 4096 MB | 1000 | 0.000140 | 3600 s | 160 |
| performance | 4 | 8192 MB | 1500 | 0.000240 | 7200 s | 80 |

Important properties include `cbmw.cewb.spot.startup.sec`,
`cbmw.cewb.spot.mtbi.sec`, `cbmw.cewb.spot.min.success.prob`,
`cbmw.cewb.spot.max.bid.ratio`, `cbmw.cewb.spot.max.attempts`,
`cbmw.cewb.spot.total.cores`, and per-class properties under
`cbmw.cewb.spot.<class>.*`. Results report `spotCost`,
`spotUsageRatio`, actual VM type `Spot`, and per-task interruption counts.
Paper-style on-demand pool properties include
`cbmw.cewb.ondemand.vm.{cores,ram.mb,provisioning.sec,price.per.sec}`,
`cbmw.cewb.container.delay.sec`, `cbmw.cewb.provisioning.interval.sec`,
`cbmw.cewb.ondemand.{initial.ready.instances,min.ready.instances,max.instances}`,
`cbmw.cewb.snapshot.delay.sec`, `cbmw.cewb.resume.progress`, and
`cbmw.cewb.admission.min.cp.multiplier`.
The default physical-VM provisioning delay is 90 seconds; the independent
Algorithm 2 capacity-adjustment interval remains 100 seconds.
Results also include `brokerRevenue` and `brokerProfit`. The reconstructed
pricing families are selected by `cbmw.cewb.pricing.policy` with values
`CONSTANT_PROFIT`, `CONSTANT_DISCOUNT`, or `PREDICTION_BASED`.
These fields are retained by `scripts/merge_algorithm_outputs.py` in combined
per-run and aggregate results.

The default class capacities are an explicitly labelled capacity-matched
experimental normalization, not a CEWB paper constant. They divide 960
physical spot cores equally across the three fixed instance classes, matching
the five 192-core reserved instances available to CBMW. Setting
`-Dcbmw.cewb.spot.total.cores=192` reproduces the previous 64/32/16 capacities.
Per-class `capacity` properties override the derived defaults. CEWB keeps its
own spot/on-demand selection, bidding, reliability, and retry policy.

CEWB scheduling is event-driven. Paper-aligned ready tasks are ordered by
slack, upward rank, workflow deadline, and task ID. On-demand physical capacity
is adjusted at 100-second provisioning intervals; a fully idle VM survives one
additional interval before termination. The older
`CEWB-Reconstructed` mode retains exact safe-start wake events. Scenario logs contain
`CEWB-CONFIG` and `CEWB-SUMMARY` records with configured/peak cores,
saturation, no-offer, fallback, predicted-miss, and wake counters.

The repository implements the paper's published scheduling/provisioning
pseudocode and delay defaults. Its generated spot market and capacity-matched
class inventory remain configurable simulation assumptions rather than the
paper's historical AWS price trace.

### Baseline Certification Status

The NOSF scheduling logic is implemented from the original publication, while
its default run intentionally uses this repository's common comparison market.
CEWB remains a reconstructed baseline because its complete reference market
and implementation are not available here.

- **NOSF** implements the published Algorithms 1-3 and Eqs. 1, 8-18. Its
  default `COMMON_MARKET` profile supports controlled CBMW comparison, while
  `PAPER_ALIGNED` restores the paper's VM catalog, hourly billing, network
  model, and 30-repetition protocol subject to the documented shared controls.
- **CEWB** implements PCP sub-deadlines, absolute interruption-penalty slack
  classes, shared spot and on-demand physical VM containers, periodic
  provisioning, progress-preserving recovery, and reconstructed pricing.

CEWB results should remain labeled as a reconstructed baseline. NOSF results
should identify the priority policy and market profile; `COMMON_MARKET` is an
algorithm-faithful run, not a reproduction of the paper's original EC2 results.

Focused CEWB validation (capacity/timing invariants plus a two-workflow smoke):

```powershell
.\scripts\test_cewb.ps1
```

### Unresolved Parameters

Some values required by the algorithms are not specified precisely by the
available papers or workflow files. The simulator uses explicit defaults so
experiments remain reproducible:

| Parameter | Current default | Why unresolved |
|-----------|-----------------|----------------|
| CBMW safety factor `beta` | `1.0` | The paper requires `beta >= 1` but does not publish the experimental value. |
| CBMW price markup `gamma` | `1.0` | The paper defines the markup but does not publish the experimental value. |
| Reserved-container startup | `0 s` separately | It is unknown whether the supplied runtime measurements already include this delay. |
| Task cores and RAM | `1 core`, `1 MB` | The supplied DAX files do not contain task resource metadata. |
| NOSF original priority | `EST` | The paper's preprocessing text says EFT while Algorithm 3's operational description says EST; `EFT` is available as a sensitivity policy. |
| NOSF experiment profile | `COMMON_MARKET` | Select `PAPER_ALIGNED` for the paper VM catalog, hourly billing, network model, and repetition protocol. |
| CEWB spot classes, prices, capacities, and reliability | Current documented spot-market defaults | The original experimental market constants are unavailable. |

Every reported experiment must state these values and any JVM-property
overrides. Results using the defaults should describe `beta`, `gamma`, NOSF,
and CEWB settings as simulator assumptions rather than paper-certified values.

---

## Project Structure

```
sources/org/workflowsim/cbmw/
    CBMWBroker.java                     — Central event handler; wires all four modules
    NegotiationModule.java              — Module 1: admission control
    CBMWStaticPlanningAlgorithm.java    — Module 2: backward sweep slot booking
    CBMWDynamicSchedulingAlgorithm.java — Module 3: current-cycle reserved dispatch
    ProvisioningModule.java             — Module 4: on-demand VM lifecycle
    HybridVmPool.java                   — Manages reserved + on-demand VM pools and slot bookings
    WorkflowRecord.java                 — Per-workflow state (LST map, VM assignments, results)
    CBMWResultCollector.java            — Per-scenario statistics and CSV output
    CBMWLogger.java                     — Structured event log to cbmw_detail.log

examples/org/workflowsim/examples/cbmw/
    CBMWSimulation.java                 — Main simulation driver (single or batch scenarios)

test_workflows/
    duplicate_and_process.py            — Reproducible manifest-driven runtime dataset generator
    poisson_distribution.json           — Arrival manifest for the 200-workflow experiment
    manifest.csv                        — Index of generated DAX files with CP and deadline info
    workflow_NNN_TOPOLOGY_Xtasks.xml    — Pegasus DAX files (CHAIN, FORK_JOIN, RANDOM topologies)
```

---

## Build

Requires **JDK 8+** and the bundled libraries in `lib/`.

**Windows PowerShell:**
```powershell
.\scripts\build.ps1
```

The script recursively removes old `.class` files from `bin/`, preserves its
non-class launcher files, compiles every Java file under `sources/` and
`examples/`, and uses a temporary argument file so Windows does not exceed its
command-length limit. It compiles into a temporary staging directory and only
updates `bin/` after success. Cleaning prevents deleted or renamed classes from
remaining in the runtime classpath without destroying the last working build
when compilation fails.

**Linux / macOS:** use the existing `scripts/run_algorithm.sh` helper or compile
with `:` as the classpath separator.

---

## Running

### Run the simulation

```powershell
java -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

By default this runs the full 45-scenario matrix: three load classes, three
deadline classes, and five algorithms, using 50 workflows per scenario for
every algorithm. Workflow inputs are read from
`Output/generated_datasets/test_workflows_sigma005_seed20260716` by default.
Use JVM properties such as
`-Dcbmw.algorithms=CBMW`, `-Dcbmw.max.workflows=5`, and
`-Dcbmw.max.scenarios=1` to restrict smoke or diagnostic runs.

Run the nine NOSF scenarios with the paper-aligned market and 30 repetitions:

```powershell
java '-Dcbmw.algorithms=NOSF' '-Dnosf.profile=PAPER_ALIGNED' `
  '-Dcbmw.output.dir=Output/nosf_paper_aligned' `
  '-Dcbmw.export.details=false' '-Dcbmw.detail.log=false' '-Dcbmw.quiet=true' `
  -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

---

## Simulation Parameters

| Property / scenario | Default | Description |
|---------------------|---------|-------------|
| Load classes | low `2.0`, moderate `1.0`, heavy `0.5` | Multipliers applied to arrival times |
| Deadline classes | tight `1.2`, medium `2.0`, loose `4.0` | Deadline = arrival + CP × tightness |
| `cbmw.workflow.dir` | `Output/generated_datasets/test_workflows_sigma005_seed20260716` | Directory containing the workflow XML/TXT datasets and arrival manifest |
| `cbmw.workflow.manifest` | `poisson_distribution.json` | Arrival manifest filename within `cbmw.workflow.dir` |
| `cbmw.reserved.instances` | `5` | Reserved VM count |
| `cbmw.reserved.cores` | `192` | Cores per reserved VM |
| `cbmw.reserved.ram.mb` | `384000` | RAM per reserved VM |
| `cbmw.reserved.hourly.cost` | `3.26` | Prepaid reference price; excluded from scheduling cost |
| `cbmw.ondemand.per.sec` | `0.000340` | Default CPU price per core-second |
| `cbmw.ondemand.memory.per.gb.sec` | `0.0` | Default memory price per GB-second |
| `cbmw.ondemand.delay.sec` | `90.0` | On-demand provisioning delay (`opd`) |
| `cbmw.ondemand.min.billing.sec` | `60.0` | Minimum on-demand billing duration |
| `nosf.profile` | `COMMON_MARKET` | `PAPER_ALIGNED` selects the paper NOSF market and repetition defaults |
| `cbmw.repetitions` | profile default: `1` or `30` | Independent repetitions of every scenario/algorithm |
| `cbmw.run.start` | `0` | First exported run number, useful when extending an experiment |
| `cbmw.seed.base` | `20260716` | Base seed used to derive a deterministic seed per repetition |
| `cbmw.runtime.resample` | true when repetitions > 1 | Resample task runtimes per run; algorithms share samples within a run |
| `nosf.billing.quantum.sec` | profile default: `60` or `3600` | Reusable NOSF VM billing quantum |
| `nosf.transfer.mode` | profile default | `COMMON_SHARED_STORAGE` or `PAPER_NETWORK` |
| `nosf.vm.type.count` | profile default: `1` or `7` | Explicit value overrides the profile VM catalog |

---

## Output

Simulation artifacts are separated by algorithm. A normal run writes per-algorithm
files under `Output/algorithms/<algorithm>/` and only combined comparison files
under `Output/comparison/`.

Main files:

- `Output/algorithms/<algorithm>/results.csv`
- `Output/algorithms/<algorithm>/results_aggregate.csv`
- `Output/algorithms/<algorithm>/task_execution.csv`
- `Output/comparison/results.csv`
- `Output/comparison/results_aggregate.csv`
- `Output/comparison/new_experiment_<load>.png`

### Console

```
========== RESULTS: low_tight_CBMW_t1.2 ==========
Workflows total/accepted : 50 / 50
Workflows rejected       : 0 (negotiation=0, planning=0)
Acceptance rate          : 1.000 (50 / 50)
Accepted deadline rate   : 0.780 (39 / 50)
Overall success rate     : 0.780 (39 / 50)
Reserved VM utilization  : 36.2%
On-demand cost ($)       : 23.0717
Spot cost ($)            : 0.0000
Reserved prepaid cost ($): 0.00 (excluded)
Total cost ($)           : 23.0717
Makespan (sim s)         : 8652.25
```

Following paper Section 3.3 and Equation 1, `reservedCost` is reported as zero
because reserved capacity is prepaid and outside the scheduler's optimization
objective. `totalCost` is therefore `onDemandCost + spotCost`; NOSF is not
charged for a reserved pool it does not use. This is a scheduling-cost metric,
not full operational expenditure including prepaid reservations.

### CSV

Scenario rows are written to both the current algorithm's own `results.csv`
and the combined comparison `results.csv`. Columns:
```
scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,runSeed,
nosfProfile,total,
accepted,rejected,metDeadline,rejectedNegotiation,rejectedPlanning,
acceptanceRate,deadlineRate,overallSuccessRate,countViolation,timeViolation,
onDemandCost,spotCost,
estimatedRawCost,offeredPrice,reservedCost,totalCost,makespan,reservedUtil,
onDemandUsageRatio,spotUsageRatio
```

Workflow admission and success are reported with separate denominators:

```text
acceptanceRate     = accepted / total submitted
deadlineRate       = met deadline / accepted
overallSuccessRate = met deadline / total submitted
countViolation     = workflows missing deadline / total submitted
timeViolation      = mean(max(0, completion - deadline) / deadline span)
rejected           = total submitted - accepted
```

The CSV also reports `metDeadline`, `rejectedNegotiation`, and
`rejectedPlanning`. This prevents a high accepted-workflow `deadlineRate` from
hiding workflows rejected before execution.

Comparison charts use `overallSuccessRate`, so deadline success is measured
against all submitted workflows. The conditional `deadlineRate` remains in CSV
outputs for admission and execution diagnostics.

Aggregate CSVs report the number of repetitions and the mean, minimum, maximum,
and sample standard deviation for total cost, on-demand VM utilization, count
violation, and time violation. `task_execution.csv` includes the run, seed,
profile, sampled runtime, and actual NOSF VM type/name for auditability.

### Detailed event log

`cbmw_detail.log` — written by `CBMWLogger` during each run. Contains timestamped entries for every negotiation decision, VM dispatch, task completion, and workflow outcome. Useful for diagnosing scheduling behaviour.

### Gantt chart

`<label>_gantt.png` — generated automatically after each scenario by `plot_gantt.py`. Requires Python 3 and matplotlib (`pip install matplotlib`).

The chart has two panels sharing a common time axis:

- **Reserved VMs (top)** — one row per VM (0–49); each bar is one task, coloured by workflow. Hatched bars indicate workflows that missed their deadline.
- **On-demand slots (bottom)** — ephemeral VMs are slot-packed so that when one VM finishes its display row is reused by the next. The row count equals the peak concurrent on-demand usage, not the total number of VMs provisioned.

The chart can also be run standalone:

```bash
# regenerate from an existing log
python plot_gantt.py cbmw_detail.log my_output.png

# restrict the time axis to the first 1000 seconds
python plot_gantt.py cbmw_detail.log out.png 0 1000
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| WorkflowSim / CloudSim | 1.0 / 3.0.3 | Discrete-event simulation core (included compiled in `out/`) |
| commons-math3 | 3.2 | Statistical utilities |
| jdom | 2.0.0 | DAX XML parsing |
| flanagan | — | Numerical methods |
