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

`cbmw_detail.log` — written by `CBMWLogger` during each run. Contains timestamped entries for every negotiation decision, slot booking, VM dispatch, and workflow completion. Useful for diagnosing scheduling behaviour.

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| WorkflowSim / CloudSim | 1.0 / 3.0.3 | Discrete-event simulation core (included compiled in `out/`) |
| commons-math3 | 3.2 | Statistical utilities |
| jdom | 2.0.0 | DAX XML parsing |
| flanagan | — | Numerical methods |
