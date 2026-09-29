#!/usr/bin/env python3
"""Run NOSF on the same isolated middle-300 workflow views as Ohio CEWB."""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

from run_cewb_ohio_2026 import ROOT, JARS, SCENARIOS, compile_sources

PROGRESS = re.compile(r"elapsed=(\d+)s started=(\d+) completed=(\d+)/(\d+)"
                      r" \((\d+(?:\.\d+)?)%\) rejectedWorkflows=(\d+)")


def show_progress(line, scenario, position, total, previous_width):
    match = PROGRESS.search(line)
    if match is None:
        print(line, end="", flush=True)
        return 0
    elapsed, started, completed, eligible, percent, rejected = match.groups()
    fraction = min(1.0, max(0.0, float(percent) / 100.0))
    filled = round(30 * fraction)
    run = re.search(r"_r(\d+)(?:\s|$)", line)
    label = f"{position}/{total} {scenario}"
    if run is not None:
        label += f" run={run.group(1)}"
    status = (f"{label} [{'#' * filled}{'-' * (30 - filled)}]"
              f" {float(percent):5.1f}% ({completed}/{eligible} tasks,"
              f" {started} started)"
              f" elapsed={elapsed}s rejected={rejected}")
    if sys.stdout.isatty():
        print("\r" + status + " " * max(0, previous_width - len(status)),
              end="", flush=True)
    else:
        print(status, flush=True)
    return len(status)


def run_one(scenario, source, output, classes, repetitions,
            progress_interval, position, total):
    mean = scenario.split("_")[0].removeprefix("arrival")
    original = json.loads((source / f"dax_poisson_arrivals_mean{mean}s_500workflows.json")
                          .read_text())
    if len(original) != 500:
        raise ValueError(f"Expected 500 original workflows for {scenario}")
    middle = original[100:400]
    shift = middle[0]["arrival_time_seconds"]
    rebased = [{**row, "arrival_time_seconds":
                round(row["arrival_time_seconds"] - shift, 6)} for row in middle]
    destination = output / scenario / "middle300"
    destination.mkdir(parents=True, exist_ok=True)
    manifest = destination / "middle300_manifest.json"
    manifest.write_text(json.dumps(rebased, indent=2) + "\n")
    (destination / "run_config.json").write_text(json.dumps({
        "scenario": scenario + "_middle300",
        "source_positions_1_based": [101, 400],
        "source_arrival_shift_seconds": shift,
        "region": "us-east-2",
        "pricing": "historical NOSF type capacities, Ohio on-demand proxy rates",
        "task_runtime": "shared CBMW TXT sample, rigid across eligible VM types",
        "priority": "EFT",
        "vm_billing_seconds": 3600,
        "vm_provisioning_seconds": 60,
        "repetitions": repetitions,
        "progress_interval_seconds": progress_interval,
    }, indent=2) + "\n")
    command = [
        "java", "-Xms128m", "-Xmx3g", "-Dcbmw.algorithms=NOSF",
        "-Dnosf.profile=COMMON_MARKET", "-Dnosf.priority.policy=EFT",
        "-Dnosf.billing.quantum.sec=3600",
        "-Dcbmw.ondemand.delay.sec=60", "-Dcbmw.runtime.resample=false",
        f"-Dcbmw.progress.interval.sec={progress_interval}",
        f"-Dcbmw.scenarios={scenario}_middle300",
        "-Dcbmw.workflow.dataset.mode=MIDDLE_300",
        f"-Dcbmw.workflow.manifest.{mean}.MIDDLE_300={manifest}",
        f"-Dcbmw.workflow.dir={source}", f"-Dcbmw.output.dir={destination}",
        "-Dcbmw.export.details=false", "-Dcbmw.detail.log=false",
        "-Dcbmw.quiet=true", "-Dcbmw.generate.gantt=false",
        "-Dcbmw.generate.comparison=false",
        f"-Dcbmw.repetitions={repetitions}", "-cp", f"{classes}:{JARS}",
        "org.workflowsim.examples.cbmw.CBMWSimulation",
    ]
    print(f"Starting NOSF middle 300 ({position}/{total}): {scenario}", flush=True)
    width = 0
    with (destination / "run.log").open("w", buffering=1) as log:
        with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True,
                              bufsize=1) as process:
            for line in process.stdout:
                log.write(line)
                if line.startswith("[progress]"):
                    width = show_progress(line, scenario, position, total, width)
            exit_code = process.wait()
    if width and sys.stdout.isatty():
        print()
    if exit_code:
        raise RuntimeError(f"NOSF {scenario} failed (exit {exit_code}); "
                           f"see {destination / 'run.log'}")
    print(f"Completed NOSF middle 300: {scenario}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true")
    group.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--workflow-dir", type=Path,
                        default=ROOT / "test_workflows" / "workflows")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "outputs" / "nosf_ohio_2026")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--progress-interval-sec", type=int, default=10,
                        help="wall-clock interval between task-progress updates (default: 10)")
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--classes-dir", type=Path)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.progress_interval_sec < 1:
        parser.error("--progress-interval-sec must be positive")
    source = args.workflow_dir.resolve()
    if not source.is_dir():
        parser.error(f"Missing workflow dataset: {source}")
    if args.classes_dir is None and not args.skip_compile:
        print("Compiling Java sources...", flush=True)
    classes = (args.classes_dir.resolve() if args.classes_dir else
               ROOT / "build_classes" if args.skip_compile else compile_sources())
    if not classes.is_dir():
        parser.error(f"Compiled classes missing: {classes}")
    output = args.output.resolve()
    scenarios = SCENARIOS if args.all else [args.scenario]
    for position, scenario in enumerate(scenarios, start=1):
        run_one(scenario, source, output, classes, args.repetitions,
                args.progress_interval_sec, position, len(scenarios))


if __name__ == "__main__":
    main()
