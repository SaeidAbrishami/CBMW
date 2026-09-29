#!/usr/bin/env python3
"""Run the original middle 300 workflows in isolation with matched Spot time."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import math
import subprocess
from pathlib import Path

from run_cewb_ohio_2026 import (ROOT, HISTORY, JARS, PRICE_PER_HOUR,
                                SCENARIOS, compile_sources,
                                default_offset_for_scenario)


def run_one(args, scenario, classes, source):
    mean = scenario.split("_")[0].removeprefix("arrival")
    original = json.loads((source / f"dax_poisson_arrivals_mean{mean}s_500workflows.json")
                          .read_text())
    if len(original) != 500:
        raise ValueError(f"Expected original 500 workflows for {scenario}")
    middle = original[100:400]
    shift = middle[0]["arrival_time_seconds"]
    rebased = [{**row, "arrival_time_seconds":
                round(row["arrival_time_seconds"] - shift, 6)} for row in middle]
    pairedOffset = args.offset_sec if args.offset_sec is not None else (
        default_offset_for_scenario(scenario) if args.all else 0)
    offset = pairedOffset + shift
    experiment_root = args.output.resolve()
    if args.reclamation_hourly > 0:
        experiment_root /= f"trace_capacity_reclaim_p{args.reclamation_hourly:g}"
    directory = experiment_root / scenario / "middle300"
    directory.mkdir(parents=True, exist_ok=True)
    previousLog = directory / "run.log"
    resultFile = directory / "algorithms" / "CEWB" / "results.csv"
    priorConfig = directory / "run_config.json"
    if (args.resume and resultFile.is_file() and previousLog.is_file()
            and priorConfig.is_file()
            and json.loads(priorConfig.read_text()).get("price_trace_offset_seconds")
                == offset
            and json.loads(priorConfig.read_text()).get("reclamation_probability_per_hour", 0.0)
                == args.reclamation_hourly
            and json.loads(priorConfig.read_text()).get("cewb_random_seed", 42)
                == args.seed
            and f"Completed: {scenario} MIDDLE_300" in previousLog.read_text()):
        print(f"Already completed isolated middle 300: {scenario}", flush=True)
        return
    manifest = directory / "middle300_manifest.json"
    manifest.write_text(json.dumps(rebased, indent=2) + "\n")
    metadata = {
        "scenario": scenario + "_middle300",
        "source_positions_1_based": [101, 400],
        "source_arrival_shift_seconds": shift,
        "rebased_first_arrival_seconds": 0.0,
        "price_trace_offset_seconds": offset,
        "note": "Rebase arrivals to zero; advance the August Spot clock by the"
                " removed leading time, matching the middle arrivals in full500.",
        "region": "us-east-2", "availability_zone_id": args.zone,
        "on_demand_usd_per_hour": PRICE_PER_HOUR,
        "spot_source": "https://zenodo.org/records/22647367",
        "spot_maximum_prices": "July minimum + [0.25, 0.5, 0.75] * (OnDemand - July minimum)",
        "spot_vm_capacity": "30 physical 32-vCPU VMs, ten in each class by default",
        "reclamation_probability_per_hour": args.reclamation_hourly,
        "reclamation_hazard_per_second": -math.log1p(-args.reclamation_hourly) / 3600,
        "reclamation_source": "synthetic per-VM capacity risk; independent of the historical price path",
        "cewb_random_seed": args.seed,
    }
    (directory / "run_config.json").write_text(json.dumps(metadata, indent=2) + "\n")
    command = [
        "java", "-Xms128m", "-Xmx3g", "-Dcbmw.algorithms=CEWB",
        f"-Dcbmw.scenarios={scenario}_middle300",
        "-Dcbmw.workflow.dataset.mode=MIDDLE_300",
        f"-Dcbmw.workflow.manifest.{mean}.MIDDLE_300={manifest}",
        f"-Dcbmw.workflow.dir={source}", f"-Dcbmw.output.dir={directory}",
        "-Dcbmw.export.details=false", "-Dcbmw.detail.log=false",
        "-Dcbmw.cewb.export.task.csv=false", "-Dcbmw.quiet=true",
        "-Dcbmw.generate.gantt=false", "-Dcbmw.generate.comparison=false",
        "-Dcbmw.repetitions=1", "-Dcbmw.cewb.spot.startup.sec=60",
        f"-Dcbmw.cewb.seed={args.seed}",
        f"-Dcbmw.cewb.spot.trace.reclamation.hourly={args.reclamation_hourly}",
        "-Dcbmw.cewb.ondemand.vm.provisioning.sec=60",
        "-Dcbmw.cewb.snapshot.delay.sec=90",
        "-Dcbmw.cewb.ondemand.vm.ram.mb=131072",
        f"-Dcbmw.cewb.ondemand.vm.price.per.sec={PRICE_PER_HOUR / 3600}",
        f"-Dcbmw.cewb.spot.trace.july={HISTORY / f'2026-07_{args.zone}_m5.8xlarge.tsv'}",
        f"-Dcbmw.cewb.spot.trace.august={HISTORY / f'2026-08_{args.zone}_m5.8xlarge.tsv'}",
        f"-Dcbmw.cewb.spot.trace.offset.sec={offset}",
        "-cp", f"{classes}:{JARS}",
        "org.workflowsim.examples.cbmw.CBMWSimulation",
    ]
    print(f"Starting isolated middle 300: {scenario}", flush=True)
    with (directory / "run.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log,
                       stderr=subprocess.STDOUT, check=True)
    print(f"Completed isolated middle 300: {scenario}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true")
    group.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--zone", choices=("use2-az1", "use2-az2", "use2-az3"),
                        default="use2-az1")
    parser.add_argument("--workflow-dir", type=Path,
                        default=ROOT / "test_workflows" / "workflows")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "outputs" / "cewb_ohio_2026")
    parser.add_argument("--offset-sec", type=int)
    parser.add_argument("--reclamation-hourly", type=float, default=0.0,
                        help="one-hour capacity reclamation probability; choose 0.10 for the single sensitivity setting")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--classes-dir", type=Path,
                        help="separately compiled classes, useful during a paired batch")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1)
    parser.add_argument("--resume", action="store_true",
                        help="skip completed middle-300 configurations")
    args = parser.parse_args()
    if not 0 <= args.reclamation_hourly < 1:
        parser.error("--reclamation-hourly must be in [0, 1)")
    source = args.workflow_dir.resolve()
    if not source.is_dir():
        parser.error(f"Missing workflow dataset: {source}")
    classes = (args.classes_dir.resolve() if args.classes_dir
               else ROOT / "build_classes" if args.skip_compile
               else compile_sources())
    if not classes.is_dir():
        parser.error(f"Compiled classes missing: {classes}")
    pairs = SCENARIOS if args.all else [args.scenario]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        jobs = [pool.submit(run_one, args, scenario, classes, source)
                for scenario in pairs]
        for job in as_completed(jobs):
            job.result()


if __name__ == "__main__":
    main()
