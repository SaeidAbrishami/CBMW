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
| economy | 1 | 1024 MB | 900 | 0.000085 | 1800 s | 320 |
| standard | 2 | 4096 MB | 1000 | 0.000140 | 3600 s | 160 |
| performance | 4 | 8192 MB | 1500 | 0.000240 | 7200 s | 80 |

Important properties include `cbmw.cewb.spot.startup.sec`,
`cbmw.cewb.spot.mtbi.sec`, `cbmw.cewb.spot.min.success.prob`,
`cbmw.cewb.spot.max.bid.ratio`, `cbmw.cewb.spot.max.attempts`,
`cbmw.cewb.spot.total.cores`, and per-class properties under
`cbmw.cewb.spot.<class>.*`. Results report `spotCost`,
`spotUsageRatio`, actual VM type `Spot`, and per-task interruption counts.

The default class capacities are an explicitly labelled capacity-matched
experimental normalization, not a CEWB paper constant. They divide 960
physical spot cores equally across the three fixed instance classes, matching
the five 192-core reserved instances available to CBMW. Setting
`-Dcbmw.cewb.spot.total.cores=192` reproduces the previous 64/32/16 capacities.
Per-class `capacity` properties override the derived defaults. CEWB keeps its
own spot/on-demand selection, bidding, reliability, and retry policy.

CEWB scheduling is event-driven. A ready task with no feasible spot offer gets
one deduplicated wake event at its exact safe-start threshold rather than being
rounded to the next global scheduling tick. Scenario logs contain
`CEWB-CONFIG` and `CEWB-SUMMARY` records with configured/peak cores,
saturation, no-offer, fallback, predicted-miss, and wake counters.

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
| NOSF constants and VM-selection details | Current documented reconstruction | Complete source pseudocode and experimental constants are unavailable. |
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

By default this runs the full 45-scenario matrix: three load classes, three
deadline classes, and five algorithms, using 50 workflows per scenario for
every algorithm. Use JVM properties such as
`-Dcbmw.algorithms=CBMW`, `-Dcbmw.max.workflows=5`, and
`-Dcbmw.max.scenarios=1` to restrict smoke or diagnostic runs.

---

## Simulation Parameters

| Property / scenario | Default | Description |
|---------------------|---------|-------------|
| Load classes | low `2.0`, moderate `1.0`, heavy `0.5` | Multipliers applied to arrival times |
| Deadline classes | tight `1.2`, medium `2.0`, loose `4.0` | Deadline = arrival + CP × tightness |
| `cbmw.reserved.instances` | `5` | Reserved VM count |
| `cbmw.reserved.cores` | `192` | Cores per reserved VM |
| `cbmw.reserved.ram.mb` | `384000` | RAM per reserved VM |
| `cbmw.reserved.hourly.cost` | `3.26` | Prepaid reference price; excluded from scheduling cost |
| `cbmw.ondemand.per.sec` | `0.000340` | Default CPU price per core-second |
| `cbmw.ondemand.memory.per.gb.sec` | `0.0` | Default memory price per GB-second |
| `cbmw.ondemand.delay.sec` | `120.0` | On-demand provisioning delay (`opd`) |
| `cbmw.ondemand.min.billing.sec` | `60.0` | Minimum on-demand billing duration |

---

## Output

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

Comparison charts use `overallSuccessRate`, so deadline success is measured
against all submitted workflows. The conditional `deadlineRate` remains in CSV
outputs for admission and execution diagnostics.

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
