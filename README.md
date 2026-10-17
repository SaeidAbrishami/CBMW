# CBMW Workflow Simulation

CBMW is a research simulator for scheduling multiple scientific workflows with deadlines over a hybrid pool of reserved VMs and on-demand containers. It is implemented in Java on [WorkflowSim 1.0](https://github.com/WorkflowSim/WorkflowSim-1.0) / CloudSim 3.0.3. The program simulates arrivals, planning, dispatch, provisioning, costs, and deadline outcomes; it does not provision real cloud resources.

The central algorithm has four parts:

1. **Negotiation** checks a workflow's deadline feasibility, calls the static planner, and quotes its estimated execution price. All feasible quotes are automatically accepted in the simulation.
2. **Static planning** reserves capacity as late as feasible, leaving earlier slots for future arrivals. Tasks that cannot be placed on reserved VMs are planned on-demand.
3. **Dynamic scheduling** starts ready tasks at their planned scheduled start times (SSTs). Every five simulated seconds it also checks whether future tasks can start earlier on reserved capacity.
4. **Provisioning** requests on-demand containers at their planned provisioning times and accounts for their lifecycle and costs.

The repository also contains the SG and DG greedy baselines, NOSF and CEWB adaptations, and CBMW ablations (Early, NoAdvance, Early-NoAdvance, and NoPlan). See [Algorithm overview](#algorithm-overview) and [baseline details](#nosf-baseline).

**Start here:** [Linux experiments](#run-cbmw-experiments-on-linux) · [Windows build](#build) · [inputs](#workflow-inputs-and-optional-workbook) · [results and metric definitions](#outputs-and-metrics) · [project structure](#project-structure)

## Run CBMW experiments on Linux

From the repository root, install Python 3 and JDK 17 (including `javac`), then run:

```bash
python3 scripts/run_cbmw_config.py config/cbmw_experiments.txt --max-workflows 5
```

This is a short smoke run. For full experiments, omit `--max-workflows`:

```bash
python3 scripts/run_cbmw_config.py config/cbmw_experiments.txt
```

The launcher compiles sources into `Output/batch/build_classes/`, runs each configured scenario in an isolated JVM, and writes results under `Output/batch/`. The shell wrapper `bash scripts/run_cbmw_config.sh config/cbmw_experiments.txt` runs the same full experiment with the default output path.

The text configuration has three parts: line 1 lists algorithms, line 2 sets the maximum number of simultaneous scenario JVMs, and each remaining line is `mean_inter_arrival_seconds deadline_factor`. The supplied [configuration](config/cbmw_experiments.txt) selects `CBMW SG DG`, permits two concurrent scenarios, and lists 18 combinations of arrival mean 15–90 seconds and deadline factor 1.2, 2, or 4. Each scenario runs both the full 500-workflow trace and its paired edge-200 trace. Other supported names include `NOSF`, `CEWB`, and the CBMW ablations in [`config/cbmw_ablation.txt`](config/cbmw_ablation.txt).

Production runs allocate up to 5120 MiB heap per JVM; smoke runs capped at 100 workflows use 1280 MiB. The launcher checks physical/container memory and reserves 1536 MiB for other processes. If the configured concurrency exceeds the available memory, reduce line 2 rather than assuming it will be reduced automatically. A scenario's `run.log` and terminal show periodic `[progress]` updates.

By default CBMW uses five reserved VMs, each with 192 cores and 384 GiB RAM; tasks fall back to one core and 2 GiB when the input lacks resource metadata. On-demand provisioning is modeled as 60 seconds, with 60-second minimum billing, and the scheduler's advancement scan runs every five simulated seconds. The config runner uses the supplied task runtime samples and one repetition. To change output location or inspect options, run `python3 scripts/run_cbmw_config.py --help`.

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

## Workflow inputs and optional workbook

[`test_workflows/workflows/`](test_workflows/workflows/) contains 500 paired XML/TXT workflow files and arrival manifests. XML describes the DAG; TXT contains sampled task durations in XML job order. The full trace consists of 100 warm-up, 300 measured, and 100 cool-down workflows. The paired edge trace keeps the first and last 100 workflows, with the final arrivals shifted to preserve the original inter-arrival pattern at the boundary. The simulator uses manifest timestamps as supplied.

[`scripts/prepare_cbmw_manifests.py`](scripts/prepare_cbmw_manifests.py) regenerates the later arrival manifests and corrected edge traces. Planning uses `cet = mu * (1 + alpha)`, with `alpha=0.20` by default (`cbmw.runtime.planning.alpha`). The supplied TXT durations are sampled execution times, distinct from the planning estimate.

An optional Windows workbook pipeline is in [`scripts/run_results_workbook.ps1`](scripts/run_results_workbook.ps1). It needs a compatible Excel template and the Node runtime path supplied by `-RuntimeRoot`; those are external to the repository. Give it explicit `-Template`, `-OutputRoot`, and `-WorkbookPath` arguments for reproducible exports. CSV outputs from the core runner do not require Node or Excel.

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

### CBMW price negotiation

For each admitted workflow, the quote sums task estimates from its static plan:

```text
reserved task = planning duration × reserved VM price per second × (task cores / reserved VM cores)
on-demand task = max(minimum billing seconds, ceil(provisioning delay + planning duration))
                 × task-sized on-demand price per second
offered price = gamma × (sum of reserved and on-demand task estimates)
```

The reserved VM's memory limits placement but carries no separate per-task quotation charge. The quote attributes CPU-share cost to reserved tasks; actual `reservedCost` charges for the leased reserved pool over the simulation window, including idle capacity. Thus `estimatedRawCost` and `offeredPrice` are predictions, while `totalCost` is realized resource cost. The default markup `gamma` is 1.0 (`cbmw.negotiation.gamma`). There is no simulated customer decision: a deadline-feasible and successfully planned workflow's quote is auto-accepted.

### NOSF Baseline

`NOSFBroker` adapts the NOSF article's three-stage online scheduler to the
shared rigid-task experiment:

1. **Workflow preprocessing (Algorithm 1):** use the selected planning
   estimate, calculate EST/EFT/LCT with Eqs. 8-10, find PCP
   paths, assign Eq. 11 sub-deadlines, and retain each task's delta from Eq. 12.
   PCP paths are allocated independently as in the article, so an initial
   parent subdeadline may exceed a child's initial subdeadline on another path.
2. **Resource allocation (pre-C Algorithm 3):** order ready tasks by EFT
   priority. Select a compatible active VM that meets the task's subdeadline,
   using predicted execution cost (price times runtime) and idle time to rank
   active candidates. If none is feasible, lease the least-cost feasible new
   VM. If no candidate meets the subdeadline, lease a new highest-ranking
   compatible VM. Active VMs include queued work in their finish prediction;
   new VMs include the 60-second provisioning delay. This implementation
   allows at most one waiting task per VM, as in the article.
3. **Feedback (Algorithm 2):** update only immediate successors that have become
   ready and apply Eqs. 16-18, preserving the original delta and LCT cap.

The default priority is `EFT` as selected for the comparison; `EST` remains
an optional sensitivity policy.
NOSF bills complete instance-hours starting when a VM is ready, excluding the
60-second provisioning interval. Its idle VMs remain reusable until the end
of the paid hour.
NOSF has two explicit experiment profiles:

- `COMMON_MARKET` (default) uses the paper's seven named historical VM types
  and their CPU/RAM capacities, priced with current Ohio Linux on-demand
  proxies, hourly billing, common shared storage, and one run.
- `PAPER_ALIGNED` retains that seven-type catalog, hourly billing, 100-Mbps
  paper network transfers, and a default of 30 repetitions. It is an adapted
  paper profile because tasks remain rigid and prices use Ohio proxies.

Rigid tasks run for the same TXT sampled duration on every compatible VM;
the paper's speed weights are disabled. Aligned planning uses CBMW's
`mu * (1 + cbmw.runtime.planning.alpha)`, defaulting to `1.20 * mu`.
Set `-Dnosf.runtime.estimator=MU_PLUS_SIGMA` for a separate run using the
paper's `mu+sigma` estimate. The queue capacity and rigid execution model are
adaptations for the shared comparison. The estimator is
recorded in the NOSF detail log. VM eligibility checks task cores and RAM.
One task runs per VM at a time.
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

The NOSF preprocessing and feedback follow the original publication, while
resource selection is adapted to this repository's rigid-task Ohio market.
CEWB remains a reconstructed baseline because its complete reference market
and implementation are not available here.

- **NOSF** retains the published PCP preprocessing, successor feedback, and
  active-first Algorithm 3 VM selection with a new-VM fallback when no type
  meets the subdeadline. Its default `COMMON_MARKET` profile supports the
  controlled CBMW comparison, while `PAPER_ALIGNED` selects paper network
  transfers and the 30-repetition default. Both retain rigid runtimes, Ohio
  price proxies, 60-second provisioning, and the paper's one-waiting-task
  limit; rigid runtimes, prices, and provisioning are comparison adaptations.
- **CEWB** implements PCP sub-deadlines, absolute interruption-penalty slack
  classes, shared spot and on-demand physical VM containers, periodic
  provisioning, progress-preserving recovery, and reconstructed pricing.

CEWB results should remain labeled as a reconstructed baseline. NOSF results
should identify the priority policy and market profile; `COMMON_MARKET` is a
rigid-task adaptation, not a reproduction of the paper's original EC2 results.

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

## Project structure

| Path | Purpose |
|------|---------|
| [`sources/org/workflowsim/cbmw/`](sources/org/workflowsim/cbmw/) | CBMW broker, negotiation, planning, scheduling, provisioning, accounting, and validation classes |
| [`sources/org/workflowsim/cbmw/ablation/`](sources/org/workflowsim/cbmw/ablation/) | CBMW variants; see its [README](sources/org/workflowsim/cbmw/ablation/README.md) |
| [`sources/org/workflowsim/cbmw/baselines/`](sources/org/workflowsim/cbmw/baselines/) | SG, DG, NOSF, and CEWB implementations |
| [`examples/org/workflowsim/examples/cbmw/`](examples/org/workflowsim/examples/cbmw/) | `CBMWSimulation` experiment driver |
| [`config/`](config/) and [`test_workflows/workflows/`](test_workflows/workflows/) | Scenario selection and paired workflow inputs |
| [`scripts/`](scripts/) | Build, experiment launchers, and result utilities |
| [`lib/`](lib/) | Bundled Java dependencies |
| [`docs/`](docs/) | Baseline-specific experimental notes |

## Build

JDK 17 and the JDK compiler (`javac`) are needed for source builds; Python 3 is needed for the Linux batch runner. The four required JARs are included in `lib/`.

On Windows PowerShell, from the repository root:

```powershell
.\scripts\build.ps1
```

The PowerShell build compiles the Java sources into `bin/`. On Linux, the batch runner above compiles automatically; [`scripts/run_algorithm.sh`](scripts/run_algorithm.sh) also compiles before running one algorithm. Use `javac -version` to check that your installed JDK is on `PATH`.

## Run an individual algorithm

On Linux, use the helper for a targeted diagnostic run:

```bash
CBMW_MAX_WORKFLOWS=5 CBMW_MAX_SCENARIOS=1 bash scripts/run_algorithm.sh CBMW
```

Omit the two caps for a full run. `scripts/run_algorithm.sh` also accepts `NOSF`, `CEWB`, `StaticGreedy`, `DynamicGreedy`, and the ablation names printed by the script when run without arguments. For a batch of specified scenarios with the paired full/edge runs, edit the text configuration and use the Python runner above.

On Windows after building, a small diagnostic run is:

```powershell
java '-Dcbmw.algorithms=CBMW' '-Dcbmw.max.workflows=5' '-Dcbmw.max.scenarios=1' -cp 'bin;lib/*' org.workflowsim.examples.cbmw.CBMWSimulation
```

These caps are for diagnosis; a capped full trace does not produce the 300-workflow cohort or a valid full/edge marginal-cost pair. Workflow inputs default to `test_workflows/workflows/`.

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
| `cbmw.runtime.stddev.ratio` | `0.05` | Normal-resampling and optional NOSF `MU_PLUS_SIGMA` sigma/mu; does not affect CBMW or aligned NOSF planning |
| `nosf.runtime.estimator` | `CBMW_CONSERVATIVE` | Aligned NOSF planning; `MU_PLUS_SIGMA` selects the paper runtime estimate |
| `cbmw.runtime.resample` | false (use matching TXT files) | Resample task runtimes per run; algorithms share samples within a run |
| `nosf.billing.quantum.sec` | `3600` | Reusable NOSF VM billing quantum |
| `nosf.transfer.mode` | profile default | `COMMON_SHARED_STORAGE` or `PAPER_NETWORK` |
| `nosf.vm.type.count` | `7` | Explicit value overrides the Ohio proxy catalog; all custom MIPS must match shared CBMW MIPS |

---

## Outputs and metrics

The Linux config runner writes one folder per arrival/deadline pair under `Output/batch/arrival<mean>_alpha<factor>/`. Each folder has `run.log`, `performance_metrics.csv`, and per-algorithm CSVs in `algorithms/<algorithm>/`. Across scenarios, `Output/batch/combined/` contains `results.csv`, `results_aggregate.csv`, `performance_metrics.csv`, and per-algorithm combined results. `task_execution.csv` is written per algorithm by the driver (CEWB can disable it separately). The config runner sets `cbmw.export.details=false`, which suppresses the additional detailed export bundle and plots.

| Field in `results.csv` | Meaning |
|-------------------------|---------|
| `total`, `accepted`, `metDeadline` | Submitted, admitted, and completed within the deadline tolerance |
| `overallSuccessRate` | `metDeadline / total`; `deadlineRate` instead divides by admitted workflows |
| `deadlineMissCount` | Admitted workflows completed at least 1.0 second after their deadlines |
| `deadlineMissCountMain300` | The same count for workflow positions 101–400 in a complete full-500 run |
| `maxDeadlineMissSeconds`, `avgDeadlineMissSeconds` | Actual lateness among reportable misses; the latter averages only missed workflows |
| `estimatedRawCost`, `offeredPrice` | Sum of planning-stage estimates and `gamma`-marked quotes for the run |
| `onDemandCost`, `reservedCost`, `spotCost`, `totalCost` | Realized resource charges; `totalCost` is their sum |
| `marginalCost` | Full-500 total cost minus paired edge-200 total cost; populated on a matching full row |
| `reservedUtil` | Reserved-core occupancy over the whole scenario window |
| `reservedUtilMain300` | Reserved-core occupancy attributable to main-300 tasks, from first main arrival to last admitted main completion |
| `reservedCpuWorkShare`, `onDemandCpuWorkShare` | Fractions of executed task core-seconds on each resource for the full run |
| `reservedCpuWorkShareMain300`, `onDemandCpuWorkShareMain300` | The same work shares using tasks of the main 300 only |
| `directMeasuredOnDemandCost` | Actual on-demand cost attributed directly to workflows 101–400 |

The main-300 fields appear only when the complete 500-workflow trace is present. `reservedUtilMain300` counts main-workflow tasks in its numerator; overlapping warm-up/cool-down tasks are excluded even if they execute during its time window. `marginalCost` is a paired-run difference and includes changes in resource reuse and timing, so it need not equal direct cost attribution to the 300 workflows. The predicted quote does not allocate idle reserved lease cost, which is part of realized cost.

For direct driver runs (without the batch wrapper), output defaults to `Output/algorithms/<algorithm>/` and `Output/comparison/`. Detail bundles, comparison plots, and Gantt charts are controlled by driver properties; the batch launcher disables them to reduce run time and memory.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| WorkflowSim / CloudSim | 1.0 / 3.0.3 | Discrete-event simulation core (compiled from `sources/`) |
| commons-math3 | 3.2 | Statistical utilities |
| jdom | 2.0.0 | DAX XML parsing |
| flanagan | — | Numerical methods |

