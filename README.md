# CBMW Workflow Simulation

Implementation of the **CBMW** (Cost-efficient Broker for Multiple Workflows) algorithm on top of [WorkflowSim 1.0](https://github.com/WorkflowSim/WorkflowSim-1.0) / CloudSim 3.0.3.

CBMW manages a hybrid pool of reserved VMs (fixed hourly cost) and on-demand containers (per-second cost) to schedule dynamically arriving scientific workflows while meeting user-specified deadlines at minimum cost.

---

## Algorithm Overview

CBMW processes each workflow arrival through four sequential modules:

| Module | Class | Role |
|--------|-------|------|
| 1. Negotiation | `NegotiationModule` | Computes critical path; rejects workflows whose deadline is shorter than `CP × 1.1` |
| 2. Static Planning | `CBMWStaticPlanningAlgorithm` | Backward sweep assigns each task a reserved VM slot at its Latest Start Time (LST = deadline − remainingCP) |
| 3. Dynamic Scheduling | `CBMWDynamicSchedulingAlgorithm` | Dispatches ready tasks to their planned VM; advances to any idle reserved VM if the planned VM is busy; falls back to on-demand if no reserved VM is available |
| 4. Provisioning | `ProvisioningModule` | Spins up and terminates on-demand VMs; tracks per-task costs |

The backward sweep in Module 2 deliberately defers reservations to the latest feasible slot, keeping earlier capacity free for workflows that have not yet arrived.

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
    CreateTestDaxModule.java            — Standalone generator for test DAX workflows

examples/org/workflowsim/examples/cbmw/
    CBMWSimulation.java                 — Main simulation driver (single or batch scenarios)

test_workflows/
    manifest.csv                        — Index of generated DAX files with CP and deadline info
    workflow_NNN_TOPOLOGY_Xtasks.xml    — Pegasus DAX files (CHAIN, FORK_JOIN, RANDOM topologies)
```

---

## Build

Requires **JDK 8+** and the bundled libraries in `lib/`.

**Windows:**
```bat
javac -cp "lib/*;out" -d out ^
    sources/org/workflowsim/cbmw/*.java ^
    examples/org/workflowsim/examples/cbmw/CBMWSimulation.java
```

**Linux / macOS:** replace `;` with `:` in the classpath.

---

## Running

### 1. Generate test workflows (first time only)

```bat
java -cp "lib/*;out" org.workflowsim.cbmw.CreateTestDaxModule
```

Writes DAX files and `test_workflows/manifest.csv`. Edit the constants at the top of `CreateTestDaxModule.java` to control the number, size, and topology of generated workflows.

### 2. Run the simulation

```bat
java -cp "lib/*;out" org.workflowsim.examples.cbmw.CBMWSimulation
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
scenario, algorithm, lambda, tightness, run, total, accepted, deadlineRate, onDemandCost, reservedCost, makespan
```

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
