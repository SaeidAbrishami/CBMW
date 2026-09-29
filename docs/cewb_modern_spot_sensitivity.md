# CEWB with a modern Spot market: limited sensitivity implementation

September 29, 2026. This is an adapted CEWB baseline. The CEWB paper explicitly
distinguishes its class-specific **maximum prices** from auction bids. Its
Equation 3 remains part of the algorithm. No simulated reclaim event is a
claim that AWS actually reclaimed a VM.
The previous Ohio July/August historical-price results remain a separate
experiment; this mode does not use their Spot price trace.

## Selected single-reclamation setting for the main comparison

Use the **August Ohio Spot price trace** as the primary price path and set a
separate, synthetic **10% probability of capacity reclamation in the first
hour of each physical Spot VM**. This is one of the levels in the attached
BOSS paper; 15% is a possible sensitivity level but has no stronger empirical
support in these data. Historical prices alone cannot identify a provider
capacity-reclamation probability. With an exponential hazard the setting is
`lambda=-ln(0.90)/3600 ≈ 0.0000292668 s^-1` and starts after the 60-second
VM provisioning delay. Price crossings and capacity reclamations are separate
causes of interruption. The 30-VM Spot cap, class maximums from CEWB Equation
3, On-Demand price, provisioning, and checkpoint assumptions remain fixed.

For just the isolated main 300 workflows under one arrival/deadline scenario:

```bash
python3 scripts/run_cewb_ohio_middle300.py \
  --workflow-dir /path/to/original/test_workflows/workflows \
  --scenario arrival15_alpha2 --reclamation-hourly 0.10
```

This setting is supported but **has not been run on the 300 workflows**.
One repetition yields a single realization; report the number of VMs actually
reclaimed and avoid interpreting its cost or deadline result as an expected
value. The previous trace-only results correspond to `--reclamation-hourly 0`.

## Why the historical run bought so much On-Demand capacity

The completed `arrival15_alpha1.2` full-500 trace run spent $34.6525 on Spot
and $443.3936 on On-Demand VMs. It acquired 29,749 Spot task offers and
made 248,420 On-Demand task allocations. The diagnostic recorded 70,356
unsatisfied Spot requests, **all due to the configured 30-VM, 960-vCPU
Spot capacity ceiling**. The original three Spot maximum-price classes
also split that cap into ten VMs apiece, while the On-Demand pool could
provision more VMs. Price-based rejection and reclamation did not explain
these numbers: the replayed August Spot price was below all July-based
class maximum prices.
The scheduling policy itself routes some critical tasks to On-Demand.

Consequently, merely increasing the maximum acceptable Spot price does not
resolve the observed capacity bottleneck. Neither the observed Spot task share
nor the Spot expenditure share is solely a measure of the algorithm's pricing
preference. Vary the declared simultaneous Spot VM cap as a separate sensitivity
factor; do not treat 960 Spot vCPUs as a measured Ohio availability limit.

## AWS semantics and experimental assumptions

AWS still accepts an **optional** maximum Spot price, while recommending that
most users leave it unset. The CEWB paper deliberately sets a separate maximum
for each Spot class to express the task's cost/reliability choice. The maximum
is not the amount paid: the instance is billed at its *current Spot rate*.
A Spot VM can still be
reclaimed for capacity reasons without a price increase. Historical Spot price
observations therefore do not identify the capacity reclamation probability.

- [AWS EC2 Spot market options](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_SpotMarketOptions.html)
- [AWS EC2 reasons for Spot interruptions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-interruptions.html)
- [AWS EC2 interrupted Linux Spot billing](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/billing-for-interrupted-spot-instances.html)

This synthetic mode models three physical 32-vCPU, 128-GiB `m5.8xlarge` Spot
classes in `us-east-2`. It divides the 30-VM cap into ten VMs per class.
Using CEWB Equation 3, the July `use2-az1` minimum of $0.3237/hour and Ohio
On-Demand price of $1.536/hour give maximum prices of **$0.626775** (economy),
**$0.929850** (standard), and **$1.232925** (performance) per VM-hour.
If a sampled price is above a class maximum, the new launch for that class is
rejected. These are configured maximums, not auction bids. Each newly created
Spot VM draws
one discount independently from Uniform(30%, 70%) against the archived Ohio
On-Demand price of $1.536/VM-hour, and holds that rate for its entire lifetime.
The **mean 50% discount is a planning descriptor**, not the expected realized
total-cost saving after provisioning, idle time, interruptions, and fallback.
There is no per-task discount resampling on a reused VM. This distribution is
the assumption in the supplied BOSS paper; it is not August's empirical
Spot price distribution and is not a 2026 AWS price quote. As a synthetic
VM keeps the same sampled price throughout its lifetime, it cannot later cross
the class maximum. In historical-trace mode, price can cross a class maximum,
and the existing replay models that separate interruption mechanism.

Each launched Spot VM independently draws an exponential post-provisioning
reclamation time with hazard `lambda=-ln(1-p)/3600`, where
`p ∈ {0.05, 0.10, 0.20, 0.40}` denotes the **probability of a VM being
reclaimed within its first running hour**, not a per-task probability. All
containers on a reclaimed VM are interrupted together; partial progress is
restored per the CEWB adaptation. A launched VM may be reclaimed while idle.
Provider-induced interruption in the first hour incurs no Linux EC2 instance
charge; otherwise VM runtime has a 60-second billing minimum. This cost model
does not include EBS/network charges. No Markov capacity-unavailability path
from the BOSS paper is implemented; new launches can fail because a sampled
price exceeds the class maximum or the explicit concurrent VM cap is reached.
The BOSS paper uses a 10% capacity-unavailability
process as an additional, separate assumption.

The three CEWB maximum-price classes remain distinct. A task can request its
assigned class or a higher-maximum class; the scheduler's critical-task
On-Demand rule, deadline handling, and interruption recovery remain. The
independent synthetic provider-reclamation hazard is identical in all three
classes. Thus the paper's claim that higher maximum classes are more reliable
applies here only to price-threshold interruptions. The synthetic mode is a
sensitivity experiment rather than a full replication of a changing Spot market:
it does not model waiting for a changing price to fall below the maximum.

Provisioning is 60 seconds for either physical VM; a container deployment is
0.4 seconds, the pool cycle is 100 seconds, and the separate checkpoint and
restore delay is 90 seconds each. The source workflow positions 101–400 of
the same 500-manifest are run in isolation with their arrival times rebased
to zero. The runner defaults to only these 300, one scenario, one repetition.
It exports per-workflow costs through the existing CEWB result machinery;
VM rental, including idle time, is allocated by each workflow's share of
used core-seconds. No customer pricing policy is applied. The CBMW algorithm
still needs a matching Ohio physical VM price model before *dollar amounts*
are directly compared as equal-price experiments.

## Reproduce one scenario

```bash
python3 scripts/run_cewb_synthetic_2026.py \
  --workflow-dir /path/to/original/test_workflows/workflows \
  --scenario arrival15_alpha2 --probability 0.05 \
  --spot-capacity 30 --limit 300
```

Use a distinct output directory for any repeated configuration. Optional
`--probability 0.10`, `0.20`, or `0.40` changes the reclamation setting.
`--spot-capacity 90` is one explicit high-capacity sensitivity setting, not
an AWS measured quota. Run `--limit 5` to verify plumbing quickly. The
configuration JSON records the class maximums, assumptions, and base seed. Different
repetitions obtain different simulation seeds; matched configurations with
the same seed share the initial random stream but **do not** retain fully
identical VM events once their schedules diverge.

## Verification and previous results

The corrected three-class synthetic mode, the historical maximum-price trace,
and the legacy baseline each pass their focused validation checks. The
five-workflow smoke results in the preceding archive were produced by the
earlier **single-pool** synthetic mode; they must not be used as CEWB results.
The workflow XML/TXT files needed for a replacement smoke run are not present
in this checkout. No 300-workflow synthetic scenario or full probability sweep
has been executed.
