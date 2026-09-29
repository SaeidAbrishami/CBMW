#!/usr/bin/env python3
"""Replay Ohio CEWB on the paired 500/200 workflow manifests."""
import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "data" / "spot_history"
JARS = ":".join(str(path) for path in sorted((ROOT / "lib").glob("*.jar")))
SCENARIOS = [f"arrival{mean}_alpha{alpha:g}"
             for mean in (15, 30, 45, 60, 75, 90)
             for alpha in (1.2, 2.0, 4.0)]
PRICE_PER_HOUR = 1.536


def default_offset_for_scenario(scenario):
    # Hold the price path fixed while varying deadline tightness.
    return (SCENARIOS.index(scenario) // 3) * 5 * 86400


def compile_sources():
    target = ROOT / "build_classes"
    target.mkdir(exist_ok=True)
    files = [str(path) for folder in ("sources", "examples")
             for path in (ROOT / folder).rglob("*.java")]
    subprocess.run(["java", "-Xmx2g", "com.sun.tools.javac.Main",
                    "-classpath", JARS, "-d", str(target), *files],
                   cwd=ROOT, check=True)
    return target


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_pair(args, scenario, classes, source, july, august):
    offset = args.offset_sec if args.offset_sec is not None else (
        default_offset_for_scenario(scenario) if args.all else 0)
    if offset < 0 or offset > 29 * 86400:
        raise ValueError("August replay offset must be within the first 30 days")
    destination = args.output.resolve() / scenario
    destination.mkdir(parents=True, exist_ok=True)
    metadata = {
        "scenario": scenario,
        "workflow_manifests": [scenario + "_full500", scenario + "_edge200"],
        "region": "us-east-2", "availability_zone_id": args.zone,
        "instance": "m5.8xlarge Linux/UNIX, 32 vCPU, 131072 MiB",
        "on_demand_usd_per_hour": PRICE_PER_HOUR,
        "on_demand_sku": "8X9B68EH66TVHPDD",
        "on_demand_versions": ["20260728175247", "20260831181331"],
        "spot_source": "https://zenodo.org/records/22647367",
        "spot_july_sha256": checksum(july),
        "spot_august_sha256": checksum(august),
        "spot_replay_utc": (datetime(2026, 8, 1, tzinfo=timezone.utc)
                            + timedelta(seconds=offset)).isoformat(),
        "spot_training": "July 2026 minimum by instance type and AZ",
        "spot_maximum_prices": "July minimum + [0.25, 0.5, 0.75] * (OnDemand - July minimum)",
        "capacity_reclamation": "unobserved; price-threshold interruptions only",
        "spot_vm_provision_seconds": 60,
        "on_demand_vm_provision_seconds": 60,
        "checkpoint_seconds": 90,
        "container_deploy_seconds": 0.4,
        "provision_cycle_seconds": 100,
        "baseline_random_seed": 20260716,
    }
    (destination / "run_config.json").write_text(json.dumps(metadata, indent=2) + "\n")
    command = [
        "java", "-Xms128m", "-Xmx3g", "-Dcbmw.algorithms=CEWB",
        f"-Dcbmw.scenarios={scenario}_full500,{scenario}_edge200",
        f"-Dcbmw.workflow.dir={source}",
        f"-Dcbmw.output.dir={destination}",
        "-Dcbmw.export.details=false", "-Dcbmw.detail.log=false",
        "-Dcbmw.cewb.export.task.csv=false", "-Dcbmw.quiet=true",
        "-Dcbmw.generate.gantt=false", "-Dcbmw.generate.comparison=false",
        "-Dcbmw.repetitions=1", "-Dcbmw.cewb.spot.startup.sec=60",
        "-Dcbmw.cewb.ondemand.vm.provisioning.sec=60",
        "-Dcbmw.cewb.snapshot.delay.sec=90",
        "-Dcbmw.cewb.ondemand.vm.ram.mb=131072",
        f"-Dcbmw.cewb.ondemand.vm.price.per.sec={PRICE_PER_HOUR / 3600}",
        f"-Dcbmw.cewb.spot.trace.july={july}",
        f"-Dcbmw.cewb.spot.trace.august={august}",
        f"-Dcbmw.cewb.spot.trace.offset.sec={offset}",
        "-cp", f"{classes}:{JARS}",
        "org.workflowsim.examples.cbmw.CBMWSimulation",
    ]
    print(f"Starting {scenario}: 500 + 200, {args.zone}, "
          f"August offset {offset}s", flush=True)
    with (destination / "run.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log,
                       stderr=subprocess.STDOUT, check=True)
    print(f"Completed {scenario}: {destination}", flush=True)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="run all 18 paired configurations")
    group.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--zone", choices=("use2-az1", "use2-az2", "use2-az3"),
                        default="use2-az1")
    parser.add_argument("--workflow-dir", type=Path,
                        default=ROOT / "test_workflows" / "workflows")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "outputs" / "cewb_ohio_2026")
    parser.add_argument("--offset-sec", type=int, help="fixed August UTC offset for a single pair")
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1,
                        help="pair processes in parallel (2 requires at least 8 GiB RAM)")
    args = parser.parse_args()
    source = args.workflow_dir.resolve()
    if not source.is_dir():
        parser.error(f"Workflow manifests and XML/TXT files are missing: {source}")
    classes = ROOT / "build_classes" if args.skip_compile else compile_sources()
    if not classes.is_dir():
        parser.error("Compiled classes are missing; omit --skip-compile")
    pairs = SCENARIOS if args.all else [args.scenario]
    july = HISTORY / f"2026-07_{args.zone}_m5.8xlarge.tsv"
    august = HISTORY / f"2026-08_{args.zone}_m5.8xlarge.tsv"

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        jobs = [pool.submit(run_pair, args, scenario, classes, source, july, august)
                for scenario in pairs]
        for job in as_completed(jobs):
            job.result()


if __name__ == "__main__":
    main()
