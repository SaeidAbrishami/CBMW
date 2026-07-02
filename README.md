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

---

## Algorithm Overview

CBMW processes each workflow arrival through four sequential modules:

| Module | Class | Role |
|--------|-------|------|
| 1. Negotiation | `NegotiationModule` | Checks deadline feasibility, invokes the completed static plan to estimate raw execution cost, applies markup `gamma`, and automatically accepts the quote |
| 2. Static Planning | `CBMWStaticPlanningAlgorithm` | Backward sweep assigns each task a reserved VM slot at its Latest Start Time (LST = deadline − remainingCP) |
| 3. Dynamic Scheduling | `CBMWDynamicSchedulingAlgorithm` | Dispatches ready tasks to their planned VM; advances to any idle reserved VM if the planned VM is busy; falls back to on-demand if no reserved VM is available |
| 4. Provisioning | `ProvisioningModule` | Spins up and terminates on-demand VMs; tracks per-task costs |

The backward sweep in Module 2 deliberately defers reservations to the latest feasible slot, keeping earlier capacity free for workflows that have not yet arrived.

When reserved `TaskPlanner` placement fails, Algorithm 1 is followed literally:
the task is assigned to dummy on-demand resource `o0` with
`SST = LST - on-demand provisioning delay`. Static planning does not add a
second on-demand feasibility rejection or clamp SST to workflow arrival. If SST
is already in the past when the workflow arrives, Algorithm 3 orders the
container immediately; such a task can still miss its deadline.

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

`NOSFBroker` implements a paper-informed reconstruction of NOSF's three-stage
online scheduler:

1. **Workflow preprocessing:** compute uncertainty-aware task durations, EST/EFT
   values, and proportional critical-path sub-deadlines.
2. **Resource allocation:** order ready tasks by earliest EST (then
   sub-deadline), predict whether each task can finish before its sub-deadline,
   and select the lowest incremental-cost on-demand resource.
3. **Feedback:** after each actual task completion, update successor timing and
   redistribute the remaining sub-deadlines using the observed finish time.

The reconstruction uses the same conservative runtime model as the paper-style
experiments: `cet = mu + z(alpha) * sigma`, where the default uncertainty is
`sigma = 0.1 * mu` and `alpha = 0.90`.

The original NOSF article is not available in this repository, so this is not
claimed as a line-for-line reproduction of its unpublished pseudocode. The
current experiment also exposes one task-sized, dedicated on-demand container
configuration. Consequently, NOSF's heterogeneous reusable-VM comparison and
utilization tie-break reduce to a single feasible candidate here; its deadline,
priority, uncertainty, cost, and feedback rules are still applied.

### CEWB Spot Baseline

`CEWBBroker` uses an explicit logical spot market instead of treating reserved
VMs as fake low-cost capacity. For every ready task it:

1. filters spot classes by task cores/RAM, current capacity, bid price,
   predicted sub-deadline finish, and interruption success probability;
2. selects the class with the lowest reliability-adjusted expected cost;
3. samples a volatile spot price and an exponential interruption time;
4. retries an interrupted task from the beginning, then falls back to a
   dedicated on-demand container after the configured attempt limit or when
   the task reaches its safe start time.

Default spot classes are explicit simulation assumptions:

| Class | Cores | RAM | MIPS | Base price/s | MTBI | Capacity |
|-------|------:|----:|-----:|-------------:|-----:|---------:|
| economy | 1 | 1024 MB | 900 | 0.000085 | 1800 s | 64 |
| standard | 2 | 4096 MB | 1000 | 0.000140 | 3600 s | 32 |
| performance | 4 | 8192 MB | 1500 | 0.000240 | 7200 s | 16 |

Important properties include `cbmw.cewb.spot.startup.sec`,
`cbmw.cewb.spot.mtbi.sec`, `cbmw.cewb.spot.min.success.prob`,
`cbmw.cewb.spot.max.bid.ratio`, `cbmw.cewb.spot.max.attempts`, and per-class
properties under `cbmw.cewb.spot.<class>.*`. Results report `spotCost`,
`spotUsageRatio`, actual VM type `Spot`, and per-task interruption counts.

The external CEWB paper's complete pseudocode and experimental spot constants
are not available in this repository. These defaults must therefore be cited as
the simulator's configurable market model, not as values claimed by the paper.

### Baseline Certification Status

NOSF and CEWB cannot currently be certified as exact reproductions of their
original publications. Their complete source pseudocode, implementation, and
all experimental constants are not available in this repository.

- **NOSF** is a paper-informed reconstruction of preprocessing, sub-deadlines,
  EST-priority allocation, uncertainty handling, and completion feedback.
- **CEWB** implements explicit reliability classes, dynamic spot prices,
  interruptions, retries, and on-demand fallback using documented configurable
  simulation assumptions.

Results should label both algorithms as **paper-informed reconstructed
baselines**, not exact reference implementations. Exact certification requires
the original algorithms and experiment parameters from their authors.

---

## Project Structure

```
sources/org/workflowsim/cbmw/
    CBMWBroker.java                     — Central event handler; wires all four modules
    NegotiationModule.java              — Module 1: admission control
    CBMWStaticPlanningAlgorithm.java    — Module 2: backward sweep slot booking
    CBMWDynamicSchedulingAlgorithm.java — Module 3: dispatch and on-demand fallback
    ProvisioningModule.java             — Module 4: on-demand VM lifecycle
    HybridVmPool.java                   — Manages reserved + on-demand VM pools and slot bookings
    WorkflowRecord.java                 — Per-workflow state (LST map, VM assignments, results)
    CBMWResultCollector.java            — Per-scenario statistics and CSV output
    CBMWLogger.java                     — Structured event log to cbmw_detail.log

examples/org/workflowsim/examples/cbmw/
    CBMWSimulation.java                 — Main simulation driver (single or batch scenarios)

test_workflows/
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

By default this runs a single scenario (`lambda=2.0`, `tightness=1.2`, `seed=0`). To run the full 180-scenario experiment, uncomment the nested loop in `CBMWSimulation.main()`.

---

## Simulation Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `lambda` | 2.0, 3.0, 6.0 workflows/min | Poisson arrival rate |
| `tightness` | 1.2 (tight), 3.0 (loose) | Deadline = arrival + CP × tightness |
| `NUM_RESERVED` | 50 | Reserved VM count (fixed pool) |
| `RESERVED_MIPS` | 1000 | Compute capacity per VM (MI/s) |
| `RESERVED_HOURLY_COST` | $3.26/hr | Modelled on AWS hpc7a.96xlarge |
| `ON_DEMAND_PER_SEC` | $0.000905/s | Modelled on AWS Fargate |
| `SIM_MINUTES` | 60 | Simulated time window for arrivals |
| `NUM_SEEDS` | 10 | Independent runs per scenario |

---

## Output

### Console

```
========== RESULTS: CBMW_tight_lam2_t1.2_seed0 ==========
Workflows total/accepted : 94 / 94
Deadline met rate        : 1.000 (94 / 94)
On-demand cost ($)       : 176.0594
Reserved fixed cost ($)  : 163.00
Total cost ($)           : 339.0594
Makespan (sim s)         : 6880.14
```

### CSV

Appended to stdout after the console report. Columns:
```
scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,total,
accepted,rejected,metDeadline,rejectedNegotiation,rejectedPlanning,
acceptanceRate,deadlineRate,overallSuccessRate,onDemandCost,spotCost,
estimatedRawCost,offeredPrice,reservedCost,totalCost,makespan,reservedUtil,
onDemandUsageRatio,spotUsageRatio
```

Workflow admission and success are reported with separate denominators:

```text
acceptanceRate     = accepted / total submitted
deadlineRate       = met deadline / accepted
overallSuccessRate = met deadline / total submitted
rejected           = total submitted - accepted
```

The CSV also reports `metDeadline`, `rejectedNegotiation`, and
`rejectedPlanning`. This prevents a high accepted-workflow `deadlineRate` from
hiding workflows rejected before execution.

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
