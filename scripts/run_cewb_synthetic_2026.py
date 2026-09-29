#!/usr/bin/env python3
"""Run one isolated main-300 CEWB sensitivity scenario using synthetic Spot VMs."""
import argparse
import json
import math
import subprocess
from pathlib import Path

from run_cewb_ohio_2026 import ROOT, JARS, PRICE_PER_HOUR, SCENARIOS, compile_sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS, default="arrival15_alpha2")
    parser.add_argument("--probability", type=float, default=0.05,
                        help="probability of provider reclamation within one hour")
    parser.add_argument("--discount-min", type=float, default=0.30)
    parser.add_argument("--discount-max", type=float, default=0.70)
    parser.add_argument("--training-minimum-per-hour", type=float, default=0.3237,
                        help="July 2026 use2-az1 minimum used by CEWB Eq. 3")
    parser.add_argument("--spot-capacity", type=int, default=30,
                        help="number of 32-vCPU Spot VMs (default matches 960 CBMW reserved cores)")
    parser.add_argument("--limit", type=int, default=300,
                        help="number of main workflows; use 5 for a smoke run")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--workflow-dir", type=Path,
                        default=ROOT / "test_workflows" / "workflows")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "outputs" / "cewb_synthetic")
    parser.add_argument("--skip-compile", action="store_true")
    args = parser.parse_args()
    if not 0 <= args.probability < 1 or not 0 <= args.discount_min <= args.discount_max < 1:
        parser.error("Probability and discount bounds must be in [0,1); min <= max")
    if args.spot_capacity < 1 or not 1 <= args.limit <= 300 or args.repetitions < 1:
        parser.error("Spot capacity and repetitions must be positive; limit must be 1..300")
    if not 0 < args.training_minimum_per_hour < PRICE_PER_HOUR:
        parser.error("July training minimum must be between zero and the On-Demand rate")
    source = args.workflow_dir.resolve()
    if not source.is_dir():
        parser.error(f"Missing workflow XML/TXT and 500 manifest: {source}")
    classes = ROOT / "build_classes" if args.skip_compile else compile_sources()
    scenario = args.scenario
    mean = scenario.split("_")[0].removeprefix("arrival")
    source_manifest = source / f"dax_poisson_arrivals_mean{mean}s_500workflows.json"
    original = json.loads(source_manifest.read_text())
    if len(original) != 500:
        parser.error("Expected 500 positions in the source manifest")
    middle = original[100:400]
    shift = middle[0]["arrival_time_seconds"]
    manifest = [{**record, "arrival_time_seconds":
                 round(record["arrival_time_seconds"] - shift, 6)}
                for record in middle]
    destination = (args.output / f"{scenario}_p{args.probability:g}_"
                   f"capacity{args.spot_capacity}_limit{args.limit}").resolve()
    destination.mkdir(parents=True, exist_ok=True)
    manifest_path = destination / "middle300_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    config = {
        "mode": "synthetic Spot discount and independent provider reclamation",
        "scenario": scenario, "source_positions_1_based": [101, 400],
        "workflow_limit": args.limit, "source_arrival_shift_seconds": shift,
        "region": "us-east-2", "vm": "m5.8xlarge Linux 32 vCPU 128 GiB",
        "on_demand_usd_per_hour": PRICE_PER_HOUR,
        "spot_max_price_request": "CEWB Eq. 3: three class-specific maximum prices",
        "july_training_minimum_usd_per_hour": args.training_minimum_per_hour,
        "maximum_prices_usd_per_hour": {
            "economy": args.training_minimum_per_hour + 0.25 * (
                PRICE_PER_HOUR - args.training_minimum_per_hour),
            "standard": args.training_minimum_per_hour + 0.50 * (
                PRICE_PER_HOUR - args.training_minimum_per_hour),
            "performance": args.training_minimum_per_hour + 0.75 * (
                PRICE_PER_HOUR - args.training_minimum_per_hour),
        },
        "discount_distribution": "uniform per new physical Spot VM",
        "discount_range": [args.discount_min, args.discount_max],
        "one_hour_reclamation_probability": args.probability,
        "reclamation_hazard_per_second": -math.log1p(-args.probability) / 3600,
        "spot_capacity_32_vcpu_vms": args.spot_capacity,
        "spot_capacity_by_class": {
            "economy": args.spot_capacity // 3,
            "standard": args.spot_capacity // 3,
            "performance": args.spot_capacity // 3 + args.spot_capacity % 3,
        },
        "spot_capacity_cores": args.spot_capacity * 32,
        "capacity_unavailability": "configured simultaneous Spot VM cap and class maximum-price admission; no Markov availability path",
        "repetitions": args.repetitions, "base_seed": 20260716,
        "spot_and_on_demand_vm_provision_seconds": 60,
        "checkpoint_delay_seconds": 90,
        "synthetic_prices_not_historical_prices": True,
    }
    (destination / "run_config.json").write_text(json.dumps(config, indent=2) + "\n")
    command = ["java", "-Xms128m", "-Xmx3g",
               "-Dcbmw.algorithms=CEWB",
               f"-Dcbmw.scenarios={scenario}_middle300",
               "-Dcbmw.workflow.dataset.mode=MIDDLE_300",
               f"-Dcbmw.workflow.manifest.{mean}.MIDDLE_300={manifest_path}",
               f"-Dcbmw.workflow.dir={source}",
               f"-Dcbmw.output.dir={destination}",
               f"-Dcbmw.max.workflows={args.limit}",
               f"-Dcbmw.repetitions={args.repetitions}",
               "-Dcbmw.export.details=false", "-Dcbmw.detail.log=false",
               "-Dcbmw.cewb.export.task.csv=false", "-Dcbmw.quiet=true",
               "-Dcbmw.generate.gantt=false", "-Dcbmw.generate.comparison=false",
               "-Dcbmw.cewb.spot.mode=SYNTHETIC",
               f"-Dcbmw.cewb.spot.synthetic.discount.min={args.discount_min}",
               f"-Dcbmw.cewb.spot.synthetic.discount.max={args.discount_max}",
               f"-Dcbmw.cewb.spot.synthetic.reclamation.hourly={args.probability}",
               f"-Dcbmw.cewb.spot.synthetic.capacity={args.spot_capacity}",
               f"-Dcbmw.cewb.spot.synthetic.training.minimum.per.hour={args.training_minimum_per_hour}",
               "-Dcbmw.cewb.spot.startup.sec=60",
               "-Dcbmw.cewb.ondemand.vm.provisioning.sec=60",
               "-Dcbmw.cewb.snapshot.delay.sec=90",
               "-Dcbmw.cewb.ondemand.vm.ram.mb=131072",
               f"-Dcbmw.cewb.ondemand.vm.price.per.sec={PRICE_PER_HOUR / 3600}",
               "-cp", f"{classes}:{JARS}",
               "org.workflowsim.examples.cbmw.CBMWSimulation"]
    with (destination / "run.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log,
                       stderr=subprocess.STDOUT, check=True)
    print(f"Completed {scenario}, p={args.probability:g}, "
          f"{args.limit} main workflows: {destination}")


if __name__ == "__main__":
    main()
