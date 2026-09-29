# NOSF: Ohio middle-300 comparison

This is a comparison adaptation of NOSF Algorithms 1–3. It uses the current
`Codex` branch's shared CBMW workflows, deadlines, TXT runtime samples and
result columns, including the latest CEWB and Greedy baseline changes.
The runner `scripts/run_nosf_ohio_middle300.py` executes **only** original
workflow positions 101–400 from each 500-workflow arrival manifest, rebased
to arrival zero exactly as in `run_cewb_ohio_middle300.py`. By default it
runs the 18 configurations (arrival means 15, 30, 45, 60, 75, 90 seconds;
deadline multipliers 1.2, 2, 4), one repetition per configuration. The
same loader calculates all CBMW/NOSF deadlines, so there is no separate
NOSF deadline rule. The runner stores its exact manifest and settings.

## VM catalog

The seven NOSF type **names and simulated capacities** are historical M1/M2.
Their prices are Linux/UNIX On-Demand **proxies** in AWS Ohio (`us-east-2`),
in USD per leased hour, not actual prices for unavailable M1/M2 instances.
The proxy closest in memory is used; `t2.medium` has two vCPUs even though
the simulated `m1.medium` retains one. Prices are fixed experimental inputs,
not a live billing query. Capacity is converted to MiB by rounding GiB × 1024.

| Simulated type | vCPU | RAM GiB | Ohio price proxy | USD/hour |
| --- | ---: | ---: | --- | ---: |
| m2.4xlarge | 8 | 68.4 | r5.2xlarge (8/64) | 0.5040 |
| m2.2xlarge | 4 | 34.2 | r5.xlarge (4/32) | 0.2520 |
| m1.xlarge | 4 | 15.0 | m5.xlarge (4/16) | 0.1920 |
| m2.xlarge | 2 | 17.1 | r5.large (2/16) | 0.1260 |
| m1.large | 2 | 7.5 | m5.large (2/8) | 0.0960 |
| m1.medium | 1 | 3.7 | t2.medium (2/4) | 0.0464 |
| m1.small | 1 | 1.7 | t2.small (1/2) | 0.0230 |

Historical capacities: [AWS previous generation specifications](https://docs.aws.amazon.com/ec2/latest/instancetypes/pg.html).
Region and On-Demand price basis: [AWS EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/)
and [AWS Price List API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/price-changes.html).
Proxy rate cross-checks: [R5](https://instances.vantage.sh/aws/ec2/r5.2xlarge?region=us-east-2),
[M5](https://instances.vantage.sh/aws/ec2/m5.xlarge?region=us-east-2),
[T2](https://instances.vantage.sh/aws/ec2/t2.medium?region=us-east-2).
Prices recorded for this comparison on 2026-09-29.

## Common workload and NOSF policy

- Each task retains its XML and TXT runtime data, core demand and RAM demand.
  The default task requirement is one core and 2048 MiB. `m1.small` is thus
  ineligible for a default task. Extra VM resources do not shorten a rigid
  task. All eligible VM types use the shared MIPS and exactly the same actual
  TXT runtime. The NOSF planning weight remains `mu + sigma` with the shared
  sigma ratio 0.05; CBMW keeps its own conservative planning estimate.
- EFT is the ready-task priority. PCP sub-deadlines and successor feedback
  remain NOSF. A VM executes one task at a time and may hold an unbounded
  FIFO queue of waiting tasks; predictions are recomputed after actual task
  completion. Core/RAM compatibility is checked before selecting a VM.
- VM provisioning takes 60 seconds. Billing starts at order time and rounds
  to full 3600-second hours. An idle ordered VM is kept until the end of its
  paid hour for reuse. Shared-storage transfer mode matches the common CBMW
  experiment (`COMMON_MARKET` profile).
- `results.csv`, `results_aggregate.csv` and `task_execution.csv` use the
  shared CBMW exporter and metrics. The run is tagged `COMMON_MARKET` in the
  `nosfProfile` field; the accompanying `run_config.json` records the Ohio
  catalog and comparison controls.

Run all 18 scenarios with the dataset from the original repository:

```bash
python3 scripts/run_nosf_ohio_middle300.py --all \
  --workflow-dir test_workflows/workflows
```

For one configuration, use for example
`--scenario arrival15_alpha1.2`. No full-500 or edge-200 experiment is
launched by this runner. Override `--repetitions` only when performing
additional runs; TXT runtime resampling stays off so all algorithms use the
same per-task samples.

The runner displays a live task progress bar for each scenario and keeps the
full Java output in that scenario's `run.log`. The bar updates every 10 seconds
of wall time by default; use `--progress-interval-sec N` to change this.
With `--all`, the prefix also shows the current scenario out of 18.
