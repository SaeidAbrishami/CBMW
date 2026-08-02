# NOSF WorkflowSim Reference Specification

This specification records how the published NOSF algorithm is implemented in
the CBMW WorkflowSim experiment driver.

Source publication:

Jiagang Liu, Ju Ren, Wei Dai, Deyu Zhang, Peng Zhou, Yaoxue Zhang, Geyong Min,
and Noushin Najjari, "Online Multi-Workflow Scheduling under Uncertain Task
Execution Time in IaaS Clouds," IEEE Transactions on Cloud Computing,
DOI: 10.1109/TCC.2019.2906300.

## Scope and Experiment Profile

`NOSFBroker` implements the paper's Algorithms 1-3 and Eqs. 1 and 8-18. The
default `COMMON_MARKET` profile deliberately differs from the paper's original
EC2 experiment so that NOSF and CBMW run under the same project environment.

In particular, NOSF reads `HybridVmPool.ON_DEMAND_PROVISIONING_DELAY`, which is
configured by `cbmw.ondemand.delay.sec` and defaults to 90 seconds. There is no
independent NOSF provisioning-delay default.

The paper used a 97-second boot delay, seven historical EC2 types, 3600-second
billing, and a 100-Mbps network. Those settings can be supplied as an explicit
paper experiment profile, but are not silently mixed into common-market runs.

## Runtime Uncertainty: Equation 1

For the normal task runtime used by NOSF:

```text
lambda ~ N(mu, sigma^2)
w(lambda) = mu + sigma
sigma = nosf.runtime.stddev.ratio * mu
```

`nosf.runtime.stddev.ratio` defaults to `cbmw.runtime.stddev.ratio`, so the
planning distribution matches the generated common runtime dataset unless an
experiment explicitly overrides it. Sampled `.txt` runtimes are used only for
actual execution.

For VM type `k`:

```text
psi(task, k) = B(k) * w(lambda)
```

The implementation obtains the same scaling from the VM type's MIPS ratio.

## Algorithm 1: Workflow Preprocessing

For every arriving workflow, the planner:

1. Calculates initial EST with Eq. 8 using the fastest configured VM type.
2. Calculates EFT with Eq. 9.
3. Calculates backward LCT with Eq. 10.
4. Finds partial critical paths from the exit toward the entry.
5. Assigns each PCP task's subdeadline with Eq. 11.
6. Stores `delta = subdeadline - EST` using Eq. 12.
7. Sends entry tasks to the runtime scheduler and retains other tasks in the
   WorkflowSim dependency pool.

The implementation keeps NOSF-specific state in `NOSFWorkflowState` and
`NOSFTaskState`; generic `WorkflowRecord` EST/EFT/LST/LFT fields mirror the
current values for exports.

## Priority Interpretation

The publication is internally inconsistent:

- Section 4.2 calls EFT the original task priority.
- The operational explanation of Algorithm 3 calls initial/adjusted EST the
  priority.

The documented default is:

```text
-Dnosf.priority.policy=EST
```

`EFT` is supported as a sensitivity interpretation. Every run logs the chosen
policy.

## Algorithm 3: Resource Allocation

Ready tasks are processed in non-descending paper-priority order. Stable
workflow and task IDs are used only as final deterministic ties.

Each NOSF VM has exactly:

```text
zero or one running task
zero or one waiting task
```

A VM that already has a waiting task is not a candidate for another task.

For each eligible active VM, the scheduler calculates the paper predicted
start, execution, and completion times. A candidate must finish no later than
the task's current subdeadline. Suitable active VMs are ranked by:

1. minimum `price * predicted execution time` from Eq. 15;
2. minimum predicted idle time;
3. predicted finish and stable VM ID only for deterministic ties.

If no active VM is feasible, feasible new types use the same paper execution
cost rule. If no type can meet the subdeadline, Algorithm 3's risk fallback
leases a new highest-ranking compatible VM; it does not reuse an infeasible
active VM.

Paper selection cost and billing-quantized rental cost are tracked separately.

## Algorithm 2: Completion Feedback

When a task completes, its waiting task is eligible to start immediately. The
feedback planner then examines only the completed task's immediate successors.
A successor is updated only after every parent has completed.

For each newly ready successor:

```text
adjustedEFT = max(actual parent finish + edge transfer + task weight)  (Eq. 16)
adjustedEST = adjustedEFT - task weight                               (Eq. 17)
adjustedSubdeadline = min(adjustedEST + originalDelta, originalLCT)   (Eq. 18)
```

Feedback never recursively redistributes deadlines for descendants that are
not ready and never reschedules a running or already waiting task.

## Transfer Modes

`nosf.transfer.mode` supports:

- `COMMON_SHARED_STORAGE` (default): dependency transfer is zero because the
  supplied task mean already includes shared-storage input/output service.
- `PAPER_NETWORK`: same-VM transfer is zero; cross-VM delay is derived from
  matching parent-output/child-input file sizes and
  `nosf.network.bandwidth.mbps`, which defaults to 100.

Paper-network mode should be used with a dataset whose task runtime does not
already include the same transfer service, avoiding double counting.

## Provisioning, Billing, and Release

New-VM predicted start includes the shared CBMW provisioning delay. Billing
starts at VM order time, so boot time is part of the leased interval. An idle
VM remains reusable through its current paid billing quantum and is released at
the boundary if it has neither a running nor waiting task.

Relevant properties:

| Property | Default | Meaning |
|---|---:|---|
| `cbmw.ondemand.delay.sec` | `90` | Shared CBMW/NOSF provisioning delay |
| `nosf.billing.quantum.sec` | `60` | Common-market NOSF billing quantum |
| `nosf.runtime.stddev.ratio` | common runtime ratio | Sigma divided by mu |
| `nosf.priority.policy` | `EST` | Published-priority interpretation |
| `nosf.transfer.mode` | `COMMON_SHARED_STORAGE` | Dependency-transfer model |
| `nosf.network.bandwidth.mbps` | `100` | Paper-network bandwidth |
| `nosf.vm.type.count` | `1` | Configured reusable VM types |

## Validation

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test_nosf.ps1
```

The validation covers the normal runtime weight, shared provisioning delay,
paper selection cost, highest-rank risk fallback, one-waiting-task invariant,
boot-inclusive billing, PCP Eq. 11 subdeadlines, Eqs. 16-18 feedback, immediate
successor readiness, VM reuse, accounting, and a two-workflow smoke scenario.
