# CBMW Workflow Simulation

Implementation of the **CBMW** (Cost-efficient Broker for Multiple Workflows) algorithm on top of [WorkflowSim 1.0](https://github.com/WorkflowSim/WorkflowSim-1.0) / CloudSim 3.0.3.

CBMW manages a hybrid pool of reserved VMs (fixed hourly cost) and on-demand containers (per-second cost) to schedule dynamically arriving scientific workflows while meeting user-specified deadlines at minimum cost.

This `codex/cbmw-exact-sst-paper` branch tests exact-time CBMW starts against
the optimized periodic baseline on `codex/cbmw-config-concurrency`. A ready task
can dispatch when its planned SST arrives, and a static on-demand order is
issued at its planned SPT (`SST - 60 seconds`). Completion or container readiness
can also dispatch an overdue task between ticks. Only attempts to advance tasks
*before* their SST scan the full ready queue every five seconds. Static LFT/LST
and reserved bookings use continuous time. Compare the two branches using the
same scenario, workflow cap, runtime seed, JVM heap, and machine before choosing
which method to report in the paper.

### Preliminary matched comparison

Both versions were run sequentially on `arrival15_alpha1.2_full500`, with
the same first 20 or 100 workflows, TXT runtimes, default resources, one
repetition, 2 GiB JVM heap, and details/charts disabled. The times below
include loading the 500-workflow manifest. These capped runs establish
correctness and give an early performance indication; they are not the
full 500/200 paper experiment.

| Workflows | Version | Deadlines met | Wall time | Total cost | Simulated makespan |
|-----------|---------|---------------|-----------|------------|--------------------|
| 20 | Optimized periodic | 20/20 | 28.26 s | 40.9458 | 4790 s |
| 20 | Exact SST | 20/20 | 26.97 s | 41.1113 | 4790 s |
| 100 | Optimized periodic | 100/100 | 82.48 s | 78.9987 | 7760 s |
| 100 | Exact SST | 100/100 | 52.94 s | 79.9981 | 7880 s |

The 100-workflow exact-SST run started 4,226 tasks at fractional SSTs, and
its planned on-demand orders occurred exactly 60 seconds before SST. Its
runtime was lower in this single run, while total cost was higher because
reserved rental covered an additional 120 simulated seconds. Repeat the full
scenario pairs and compare both deadline success and cost before selecting the
paper result.

## Revised CBMW experiments on Linux (8 cores, 8 GiB)

Edit `config/cbmw_experiments.txt`: line 1 selects `CBMW`, line 2 sets the
number of concurrent experiments, and each subsequent line gives
`mean_inter_arrival_seconds deadline_factor`. The supplied file has 18
scenarios: means 15, 30, 45, 60, 75 and 90, with factors 1.2, 2 and 4.

```bash
bash scripts/run_cbmw_config.sh config/cbmw_experiments.txt
```

The launcher compiles Java 17 sources and runs each scenario in an isolated
CloudSim JVM. Each scenario executes the full 500-workflow trace and the
compressed 200-workflow boundary trace once, preserving the same workflow
identities and arrival pattern across deadline factors. Full runs on an 8 GiB
machine use one JVM at a time with a 5120 MiB heap. The supplied config
sets line 2 to `2` for a 16 GiB machine. A machine with more RAM can use a
larger number on line 2;
the launcher uses that number without silently reducing it. It checks physical
RAM and container limits before starting, reserving 1536 MiB for the OS and
native processes. If the requested JVM heaps would exceed that limit, it
reports the largest safe number and stops. Small smoke tests capped at 100
workflows use 1280 MiB per JVM.
For example, run `python3 scripts/run_cbmw_config.py
config/cbmw_experiments.txt --max-workflows 5` for a smoke test.

During each dataset, the terminal and that scenario's `run.log` print a
`[progress]` line every 60 seconds of wall-clock time. It reports distinct
tasks started, completed tasks, and completion percentage of tasks in accepted
workflows. `rejectedWorkflows` counts rejected workflows; their tasks are
excluded from the completion denominator. Each configuration reports the full
500 and edge 200 datasets separately, and a final progress line appears when
each dataset finishes. The progress timer
can be changed with `-Dcbmw.progress.interval.sec=N` when invoking Java
directly; the batch launcher uses 60 seconds.

Combined per-scenario metrics, aggregate metrics and planning/dispatch timing
are written to `Output/batch/combined/`. Individual runs retain their
`task_execution.csv`, run log and performance CSV under
`Output/batch/arrival<mean>_alpha<factor>/`. Deadline miss time is the positive
difference between actual workflow completion and its deadline; the reported
average uses completed, admitted workflows that missed. The
`directMeasuredOnDemandCost` column counts costs from workflows 101–400 in
the full trace. The default task allocation is a synthetic 1 vCPU / 2 GiB;
the DAX files do not include CPU/RAM task profiles.

Use `python3 scripts/prepare_cbmw_manifests.py` to reproduce the 75/90-second
traces and the corrected compressed boundary traces. The simulation assumes
one bounded-uniform runtime sample per task from the supplied TXT files, a
20% conservative planning margin, one 60-second allowance in generated
deadlines, 5-second advancement checks and 60-second billable provisioning for
each on-demand container.

The 60-second allowance covers an on-demand order placed at workflow arrival;
the 1.2 factor provides additional room over the conservative critical path.
Neither value alone guarantees admission under reserved-capacity contention. If CBMW's
backward reserved-slot pass cannot place a workflow, it releases that
workflow's tentative bookings and tries an earliest feasible continuous plan.
This recovery uses reserved capacity only when it starts no later than the
task's on-demand alternative, and rejects a workflow if the resulting plan
still exceeds its deadline. Recovery may raise on-demand cost.
If an earlier task occupies a reserved VM past a committed start, the ready
task waits until capacity is released; measured deadline misses include any
resulting delay.

### Task Runtime Meaning

The task mean runtime `mu` represents the task's complete expected service time:

```text
mu = computation time + shared-storage input/output access time
```

The simulator therefore does not add a separate shared-storage or dependency
file-transfer delay. CBMW's conservative planning duration is
`cet = mu + alpha * mu`, with `alpha=0.20` by default. The matching `.txt` value
is the perturbed sample of the same combined runtime used for actual execution.

Queue waiting, scheduler delay, and on-demand provisioning delay (`OPD`) are
separate from task runtime. Reserved-container startup is currently not added
separately; it is represented only when it is already included in the supplied
DAX/runtime measurement.

### Current dataset and results workbook

The default `test_workflows/workflows` folder contains 500 XML/TXT pairs and
12 arrival manifests. TXT task runtimes are `ceil(mu + uniform(-0.2*mu, 0.2*mu))`
in XML job order, generated with seed 20260909. All four full manifests share
one shuffled workflow order (seed 20260909); exponential inter-arrival gaps
use seeds `20260909 + mean` for means 15, 30, 45 and 60 seconds; the
75/90-second traces use seeds `20260924 + mean`. Edge manifests preserve
the first 100 and last 100 workflows, shifting the latter by
arrival(400)-arrival(100) (1-based positions), so the original gap between
arrivals 400 and 401 remains.
The simulator never rescales or rebases these JSON timestamps.

Run all 24 CBMW scenarios and fill the supplied template:

```powershell
.\scripts\run_results_workbook.ps1 -Template 'C:\Users\AsiaLapTop.Com\OneDrive\Desktop\results.xlsx'
```

This runs one repetition with TXT runtime samples in four independent JVMs
(configurable with `-Workers`), saves scenario CSVs and
`run_config.json` under a fresh `Output/results_24_<timestamp>` directory, and
writes `outputs/results_24_<timestamp>/results.xlsx`. The original template
is preserved. Task and aggregate CSVs remain under each scenario subfolder;
the combined 24-row CSV is `algorithms/CBMW/results.csv`. The exporter rejects missing, duplicate, capped, mixed-algorithm,
or multi-repetition inputs. To export an already completed run, pass
`-SkipSimulation -OutputRoot <run-directory>`.

Workbook definitions: success is met-deadline/total; time is elapsed simulated
seconds from first arrival to final completion; Fun. Cost is on-demand cost;
total cost includes reserved rental; marginal cost is full cost minus matching
edge cost, on the full row only. RAM is MB; capacity integrals are core-seconds
and MB-seconds. Utilization means are time-weighted over the workload window.
Reserved integrals are available capacity, not consumed capacity. On-demand
counts/capacities sum provisioned instances across their lifecycles, not peaks.
The exported sheet contains formulas for success rate, total and marginal cost.

The CBMW planning margin is controlled by
`cbmw.runtime.planning.alpha` (default `0.20`), so the default planning runtime
is `1.20 * mu`. It is separate from the 20% uniform uncertainty used to
generate the supplied actual runtimes and from `cbmw.runtime.stddev.ratio`,
which remains available for normal runtime resampling and NOSF.

## Algorithm Overview

CBMW processes each workflow arrival through four sequential modules:

| Module | Class | Role |
|--------|-------|------|
| 1. Negotiation | `NegotiationModule` | Uses the static planner as its admission test, quotes the planned cost, and accepts the quote |
| 2. Static Planning | `CBMWStaticPlanningAlgorithm` | Backward sweep assigns each task a reserved VM slot at its Latest Start Time (LST = deadline − remainingCP) |
| 3. Dynamic Scheduling | `CBMWDynamicSchedulingAlgorithm` | Dispatches ready tasks at SST and scans future tasks for advancement every five seconds |
| 4. Provisioning | `ProvisioningModule` | Spins up and terminates on-demand VMs; tracks per-task costs |

The backward sweep in Module 2 deliberately defers reservations to the latest feasible slot, keeping earlier capacity free for workflows that have not yet arrived.

When reserved placement fails, SST is the on-demand task's planned execution
start at LST. The request is issued at `Spt = SST - opd`. The planner rejects
a plan if that request would precede workflow arrival or if execution would
precede the task's precedence bound. An exact-time wake dispatches due ready
tasks; on each five-second tick the dynamic scheduler also tries to advance
future ready tasks on reserved capacity. On-demand orders are issued at their
planned request time, including for tasks that are not yet ready.
Previously committed reserved slots remain available at their planned start;
an early start replaces only the task's own future booking.

### CBMW Price Negotiation

After a deadline-feasible workflow is statically planned, CBMW computes the
paper's price quote:

```text
raw cost = sum(planned task duration * allocated resource price)
offered price = gamma * raw cost
```

Reserved tasks use the reserved VM's per-second price. On-demand tasks use the task-sized container price and the greater of
60 seconds or the conservative task duration plus provisioning time. The
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
   allow any number of FIFO waiting tasks per VM, select a sub-deadline-feasible active
   VM by minimum `price * predicted execution` and then minimum idle time, or
   provision a suitable new type. If none is feasible, provision the
   highest-ranking compatible type and mark the task deadline-risk.
3. **Feedback (Algorithm 2):** update only immediate successors that have become
   ready and apply Eqs. 16-18, preserving the original delta and LCT cap.

The default priority is `EFT` as selected for the comparison; `EST` remains
an optional sensitivity policy.
NOSF has two explicit experiment profiles:

- `COMMON_MARKET` (default) uses the paper's seven named historical VM types
  and their CPU/RAM capacities, priced with current Ohio Linux on-demand
  proxies, hourly billing, common shared storage, and one run.
- `PAPER_ALIGNED` retains that seven-type catalog, hourly billing, 100-Mbps
  paper network transfers, and a default of 30 repetitions. It is an adapted
  paper profile because tasks remain rigid and prices use Ohio proxies.

Rigid tasks run for the same TXT sampled duration on every compatible VM;
the paper's speed weights are disabled. Planning still uses `mu+sigma` and
VM eligibility checks both task cores and RAM. One task runs per VM at a time.
Historical M1/M2 capacities are kept in the simulator, while the price proxies
are r5.2xlarge, r5.xlarge, m5.xlarge, r5.large, m5.large, t2.medium,
and t2.small respectively. M1/M2 are not offered in Ohio. See
`docs/nosf_ohio_middle300.md` for capacities and prices.

The paper-aligned profile deliberately retains the common comparison controls:
the project's three deadline factors, the same workflow population, the shared
`cbmw.runtime.stddev.ratio`, and `cbmw.ondemand.delay.sec=60` rather than the
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
The default physical-VM provisioning delay is 60 seconds; the independent
Algorithm 2 capacity-adjustment interval remains 100 seconds.
Results also include `brokerRevenue` and `brokerProfit`. The reconstructed
pricing families are selected by `cbmw.cewb.pricing.policy` with values
`CONSTANT_PROFIT`, `CONSTANT_DISCOUNT`, or `PREDICTION_BASED`.
These fields are retained by `scripts/merge_algorithm_outputs.py` in combined
per-run and aggregate results.

### Ohio July–August 2026 CEWB replay

Run `python scripts/run_cewb_ohio_2026.py --all --workers 2
--workflow-dir /path/to/test_workflows/workflows` from this checkout to
simulate every arrival/deadline pair with the same full 500 and edge 200
manifests. The script selects `use2-az1` by default; `--zone use2-az2` and
`--zone use2-az3` allow zone sensitivity. Each pair replays the same August
window for both manifests, and the 18 pairs start at offsets spread over the
month. All three deadline factors at one arrival rate use the same price
window; the six arrival rates begin on August 1, 6, 11, 16, 21, and 26 UTC.
Each output folder contains `run_config.json`, `run.log`, scenario
results, and `algorithms/CEWB/workflow_costs.csv`. The latter records each
workflow's attributed Spot and On-Demand physical rental costs and marks
positions 101–400 in the full manifest. The full-row `marginalCost` is the
500-run total cost minus the matched edge-200 total cost. The edge manifest
shifts the final 100 workflows earlier, so this difference also includes
resource reuse and timing changes. The sum of the marked workflows'
attributed costs is a different, descriptive quantity.

To measure the middle 300 *alone*, run `python
scripts/run_cewb_ohio_middle300.py --all --workers 2 --workflow-dir
/path/to/test_workflows/workflows --output outputs/cewb_ohio_2026`.
It writes a separate middle-300 result for each pair. The original workflow
positions 101–400 are rebased to start at zero; the Spot clock is advanced
by the removed leading arrival time, preserving the corresponding August
price interval. `python scripts/summarize_cewb_ohio_2026.py
outputs/cewb_ohio_2026` combines the three cost definitions and checks that
per-workflow attributed costs sum to each physical VM total.

The six small files under `data/spot_history` are the Linux/UNIX
`m5.8xlarge` prices extracted for the three `use2-az*` zones from [Eric
Pauley's AWS Spot Price History](https://zenodo.org/records/22647367)
(July/August 2026). July supplies the *training minimum*, and August supplies
the price timeline; the scheduler never trains its bids on future August
prices. AWS's archived July (`20260728175247`) and August
(`20260831181331`) Ohio EC2 price lists both quote $1.536 per hour for
Linux/shared/used `m5.8xlarge`, SKU `8X9B68EH66TVHPDD`. These historical
files are documented by [AWS's bulk price list API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-the-aws-price-list-bulk-api-fetching-price-list-files-manually.html).

Trace mode uses physical 32-vCPU/128-GiB Spot and On-Demand VMs, a 60-second
VM startup, a 0.4-second container delay, a 100-second provisioning cycle,
and a separate 90-second snapshot/restore delay. The paper's three Spot
maximum bids are the July minimum plus 25%, 50%, and 75% of the difference
to the Ohio On-Demand price. Spot rental is charged by physical VM,
including its idle time, at the recorded price at the start of each
instance-hour, with per-second metering. AWS-initiated interruptions during
the first instance-hour have no Spot VM charge. VM cost is attributed to
workflows using their container core-time on that VM. Customer quotes,
revenue, and profit are disabled in trace mode.

The CBMW runner still uses its separately configured reserved and task-sized
On-Demand rates. The paired workflow inputs and provisioning assumptions can
be compared now, but a claim about absolute dollar savings versus CBMW
requires repricing the CBMW resource model to the same Ohio price basis.

**Interpretation:** the history is a price trace, not an interruption event
log. It supports only price-above-bid interruptions. For this instance and
these bids, none of the three August zone traces exceeds even the lowest
bid. AWS may still reclaim Spot capacity. The observed zero price crossings
must not be presented as a measured zero capacity-interruption probability.
Likewise, the synthetic fixed-MTBI mode is independent of this replay and
does not calibrate a provider reclamation probability.

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

The NOSF scheduling logic follows the original publication and is adapted to
this repository's rigid-task Ohio comparison market.
CEWB remains a reconstructed baseline because its complete reference market
and implementation are not available here.

- **NOSF** implements the published Algorithms 1-3 and Eqs. 1, 8-18. Its
  default `COMMON_MARKET` profile supports controlled CBMW comparison, while
  `PAPER_ALIGNED` keeps Ohio price proxies and rigid task times while selecting
  paper network transfers and the 30-repetition default.
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
| Task cores and RAM | `1 core`, `2048 MB` | Synthetic defaults when DAX task resource metadata is absent. |
| NOSF priority | `EFT` | Selected for the comparison; `EST` is available for sensitivity analysis. |
| NOSF experiment profile | `COMMON_MARKET` | Seven historical-capacity VM types with Ohio price proxies and hourly billing; `PAPER_ALIGNED` selects paper network transfers and repetition default. |
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

The historical driver defaults to five algorithms over 36 scenarios each: six
arrival rates (15/30/45/60/75/90), three factors (1.2/2/4), and both dataset
modes. For the revised CBMW experiment, use the Linux config launcher above.
Inputs default to `test_workflows/workflows`. Each scenario selects its
`dax_poisson_arrivals_mean<mean>s_500workflows.json` or `_edge200.json` file
and uses the manifest arrival times without modification.
Use JVM properties such as
`-Dcbmw.algorithms=CBMW`, `-Dcbmw.max.workflows=5`, and
`-Dcbmw.max.scenarios=1` to restrict smoke or diagnostic runs.

Run the same isolated middle 300 manifests used for the Ohio CEWB experiment
(all 18 arrival/tightness configurations, one shared TXT sample per task):

```bash
python3 scripts/run_nosf_ohio_middle300.py --all \
  --workflow-dir test_workflows/workflows
```

---

## Simulation Parameters

| Property / scenario | Default | Description |
|---------------------|---------|-------------|
| Arrival/deadline configurations | `15/30/45/60/75/90` x `1.2/2/4` | `(target mean inter-arrival seconds, deadline multiplier alpha)` |
| Dataset modes | `FULL_500`, `EDGE_200` | Both modes for all algorithms |
| `cbmw.workflow.dir` | `test_workflows/workflows` | Flat directory containing all 500 XML/TXT pairs and the arrival manifest |
| `cbmw.scenarios` | all 36 | Comma-separated scenario IDs for isolated/resumable runs |
| `cbmw.workflow.manifest.<mean>.<mode>` | scenario-specific filename | Arrival manifest filename within `cbmw.workflow.dir` |
| `cbmw.workflow.dataset.mode` | both modes when unset | Restrict execution to `FULL_500` or `EDGE_200` |
| `cbmw.reserved.instances` | `5` | Reserved VM count |
| `cbmw.reserved.cores` | `192` | Cores per reserved VM |
| `cbmw.reserved.ram.mb` | `393216` | RAM per reserved VM |
| `cbmw.task.ram.mb` | `2048` | Synthetic fallback RAM per task |
| `cbmw.reserved.per.sec` | `0.0017` | Reserved rental price included in report cost |
| `cbmw.ondemand.per.sec` | `0.00001` | Default CPU price per core-second |
| `cbmw.ondemand.memory.per.gb.sec` | `0.000001` | Default memory price per GB-second |
| `cbmw.ondemand.delay.sec` | `60.0` | On-demand provisioning delay (`opd`) |
| `cbmw.ondemand.min.billing.sec` | `60.0` | Minimum on-demand billing duration |
| `nosf.profile` | `COMMON_MARKET` | `PAPER_ALIGNED` selects paper network transfers and repetition default while retaining Ohio proxies and rigid runtimes |
| `cbmw.repetitions` | profile default: `1` or `30` | Independent repetitions of every scenario/algorithm |
| `cbmw.run.start` | `0` | First exported run number, useful when extending an experiment |
| `cbmw.seed.base` | `20260716` | Base seed used to derive a deterministic seed per repetition |
| `cbmw.runtime.planning.alpha` | `0.20` | CBMW additive planning margin; `cet = mu + alpha * mu` |
| `cbmw.runtime.stddev.ratio` | `0.05` | Normal-resampling and NOSF sigma/mu; does not affect CBMW planning |
| `cbmw.runtime.resample` | false (use matching TXT files) | Resample task runtimes per run; algorithms share samples within a run |
| `nosf.billing.quantum.sec` | `3600` | Reusable NOSF VM billing quantum |
| `nosf.transfer.mode` | profile default | `COMMON_SHARED_STORAGE` or `PAPER_NETWORK` |
| `nosf.vm.type.count` | `7` | Explicit value overrides the Ohio proxy catalog; all custom MIPS must match shared CBMW MIPS |

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

Current report accounting includes reserved lease cost for CBMW and greedy
baselines. `totalCost = reservedCost + onDemandCost + spotCost`. NOSF/CEWB have
no reserved lease charge. The console example above is historical; the current
report includes reserved rental over the simulated workload duration.

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
