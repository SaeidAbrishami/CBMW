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
  TXT runtime. Aligned NOSF and CBMW both plan with `mu * 1.20` under the
  default `cbmw.runtime.planning.alpha=0.20`. Set the optional
  `-Dnosf.runtime.estimator=MU_PLUS_SIGMA` for a separate paper-estimate
  sensitivity run; with the shared sigma ratio 0.05 it uses `mu * 1.05`.
- EFT is the ready-task priority. PCP sub-deadlines and successor feedback
  remain NOSF. A VM executes one task at a time and may hold an unbounded
  FIFO queue of waiting tasks; predictions are recomputed after actual task
  completion. Core/RAM compatibility is checked before selecting a VM. Every
  ready task compares the earliest predicted finish among compatible active
  VMs (including their existing waiting work) and a newly provisioned VM
  (including the 60-second provisioning delay). A VM may receive additional
  waiting tasks only when it finishes no later than the best new VM. Equal
  finishes favor lower incremental hourly rental cost. This comparison is a
  rigid-task adaptation of the paper, which allows one waiting task per VM.
- VM provisioning takes 60 seconds and is not billed. Billing starts when
  the VM becomes ready and rounds to full 3600-second hours. An idle VM is
  kept until the end of its paid hour for reuse. Shared-storage transfer mode matches the common CBMW
  experiment (`COMMON_MARKET` profile).
- `results.csv`, `results_aggregate.csv` and `task_execution.csv` use the
  shared CBMW exporter and metrics. The run is tagged `COMMON_MARKET` in the
  `nosfProfile` field; the accompanying `run_config.json` records the Ohio
  catalog, planning estimator, VM selection, and comparison controls. The detail
  log also records `estimator=CBMW_CONSERVATIVE`.

Run the four primary arrival rates concurrently for one deadline factor:

```bash
python3 scripts/run_nosf_ohio_middle300.py --deadline-factor 1.2 --workers 4 \
  --workflow-dir test_workflows/workflows
```

The factor may be `1.2`, `2`, or `4` (`2.0` and `4.0` are accepted). The four
JVMs run independently in four Python worker threads. The scenario outputs
stay under `outputs/nosf_ohio_2026/arrival<rate>_alpha<factor>/middle300/`.
After all four finish, `outputs/nosf_ohio_2026/combined/alpha<factor>/` holds
`results.csv` (four rows per repetition), `results_aggregate.csv` (four rows),
and the streamed concatenation `task_execution.csv`, plus `run_config.json`.
The merged files are only replaced after all four scenarios succeed.

Run all 18 scenarios with the dataset from the original repository:

```bash
python3 scripts/run_nosf_ohio_middle300.py --all --workers 2 \
  --workflow-dir test_workflows/workflows
```

For one configuration, use for example
`--scenario arrival15_alpha1.2`. No full-500 or edge-200 experiment is
launched by this runner. Override `--repetitions` only when performing
additional runs; TXT runtime resampling stays off so all algorithms use the
same per-task samples.

The runner compiles once, then runs up to `--workers` independent scenario
JVMs at a time (1–4; default 4 with `--deadline-factor`, otherwise 2).
Each Java process can use up to 3 GiB of heap.
Four workers can therefore reserve 12 GiB of Java heap plus JVM and OS
overhead; monitor available memory on a 16 GiB machine, and reduce to two
workers if memory becomes tight. The terminal prints a labeled progress bar for each scenario
on its own line, every 10 seconds by default. Use `--progress-interval-sec N`
to change this. Each scenario keeps its full Java output in its own `run.log`.
With `--all`, the prefix shows the current scenario out of 18. Every scenario
still uses only its 300-entry middle workflow manifest.
