# CBMW Workflow Simulation

University research project implementing the **CBMW** (Cost-efficient Broker for Multiple Workflows) algorithm on top of WorkflowSim 1.0 / CloudSim 3.0.3.

**Goal:** Simulate a cloud broker that schedules scientific workflows on a hybrid reserved/on-demand VM pool, then compare deadline satisfaction rate and cost against two baselines (StaticGreedy, DynamicGreedy).

---

## Build & Run

```bash
# Compile (from project root)
javac -cp "lib/*" -d bin $(find sources examples -name "*.java")

# Package
jar -cvmf manifest.mf WorkflowSim.jar -C bin .

# Run
java -cp "WorkflowSim.jar:lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
```

Entry point: `examples/org/workflowsim/examples/cbmw/CBMWSimulation.java`

---

## Source layout

```
sources/org/workflowsim/cbmw/
  AbstractWorkflowBroker.java     ← base class for all three brokers
  CBMWBroker.java                 ← CBMW algorithm (extends AbstractWorkflowBroker)
  CBMWStaticPlanningAlgorithm.java
  CBMWDynamicSchedulingAlgorithm.java
  HybridVmPool.java               ← 50 reserved VMs + on-demand pool
  NegotiationModule.java          ← Module 1: CP computation + accept/reject
  ProvisioningModule.java         ← on-demand VM lifecycle
  WorkflowLoader.java             ← reads poisson_distribution.json + computes CP
  WorkflowArrivalData.java
  WorkflowRecord.java
  CBMWResultCollector.java
  CBMWLogger.java
  baselines/
    StaticGreedyBroker.java       ← round-robin static planning, no deadline awareness
    DynamicGreedyBroker.java      ← no planning, FCFS dispatch at runtime

examples/org/workflowsim/examples/cbmw/
  CBMWSimulation.java             ← main driver

test_workflows/                   ← real scientific workflow DAX files
  CyberShake_100_1.xml .. _25.xml
  CyberShake_1000_1.xml .. _25.xml
  Inspiral_100/1000 × 25 variants
  Montage_100/1000 × 25 variants
  Sipht_100/1000 × 25 variants
  (+ matching .txt perturbed runtime files for each)
  poisson_distribution.json       ← pre-computed arrival timestamps (seconds)
```

---

## Simulation design

### Workflow input
- `WorkflowLoader` reads `test_workflows/poisson_distribution.json` for arrival timestamps.
- For each entry it parses the matching `.xml` (Pegasus DAX 2.1 format) to compute the critical path, then sets `deadline = arrivalTime + CP × TIGHTNESS`.
- After `WorkflowParser` creates task objects, `applyPerturbedRuntimes()` replaces each task's `cloudletLength` with the value from the matching `.txt` file (Normal(nominal, nominal/10) distribution), making the 25 variants genuinely distinct.

### VM model
- **50 reserved VMs**, MIPS = 1000, cost $3.26/hr (fixed regardless of utilisation).
- **On-demand VMs**, MIPS = 1000, cost $0.000905/sec (pay per task CPU time).
- `cloudletLength = runtime_seconds × 1000` so `execTime = cloudletLength / MIPS = runtime_seconds`.

### Broker hierarchy
All three brokers extend `AbstractWorkflowBroker`, which provides:
- CloudSim event wiring, DAX parsing, `.txt` override, negotiation, job wrapping, completion tracking.
- Template method `planWorkflow(wfr, tasks)` — subclass assigns VMs.
- Abstract `processCloudletUpdate(ev)` — subclass dispatches ready jobs.
- `dispatchScheduledJobs(toSchedule)` helper for on-demand VM registration.

| Broker | planWorkflow | processCloudletUpdate |
|--------|-------------|----------------------|
| CBMW | Backward sweep-line, LST-aware slot booking | LST/deadline-aware, advance logic, on-demand fallback |
| StaticGreedy | Round-robin reserved VM assignment | Assigned VM → any reserved → on-demand |
| DynamicGreedy | None (workflow ID stamp only) | First idle reserved → on-demand FCFS |

### Current experiment
Single scenario: `TIGHTNESS = 2.0`, all three algorithms, one run.
Sim duration = `max(arrivalTime from JSON) + 5000s`.
Output per algorithm: `Output/algorithms/<algorithm>/results.csv`,
`results_aggregate.csv`, `task_execution.csv`, scenario detail logs, optional
Gantt charts, and optional detail folders. Combined comparison files go under
`Output/comparison/`.

---

## Key constants

| Constant | Location | Value |
|----------|----------|-------|
| `NUM_RESERVED` | `HybridVmPool` | 50 |
| `RESERVED_MIPS` | `HybridVmPool` | 1000.0 |
| `ON_DEMAND_PER_SEC` | `HybridVmPool` | $0.000905 |
| `RESERVED_HOURLY_COST` | `HybridVmPool` | $3.26 |
| `BETA` (negotiation safety factor) | `NegotiationModule` | 1.1 |
| `TIGHTNESS` | `CBMWSimulation` | 2.0 |
| `SIM_BUFFER_SECS` | `CBMWSimulation` | 5000.0 |

---

## Known defects (from prior analysis)

- **D6 (critical):** `findLatestFeasibleSlot` in `CBMWStaticPlanningAlgorithm` uses `>= 0` guards instead of `>= CloudSim.clock()` — can book slots in the past.
- **D1:** `Math.log(1.0 - rng.nextDouble())` (now removed — arrivals come from JSON).
- **D3:** `getCloudletReceivedList()` never cleared — O(n²) completion check at high workflow counts.
- **D4:** No cycle detection in `NegotiationModule.remainingCP()`.
- **D10:** Float equality in `releaseSlot` (`s[0] == start`).

Full defect list in memory file `project_cbmw_layers.md`.
