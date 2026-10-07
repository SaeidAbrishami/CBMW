#!/usr/bin/env python3
"""Run NOSF on the isolated middle 300; combine a deadline factor's arrivals."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
from threading import Lock
from pathlib import Path

from run_cewb_ohio_2026 import ROOT, JARS, SCENARIOS, compile_sources

PROGRESS = re.compile(r"elapsed=(\d+)s started=(\d+) completed=(\d+)/(\d+)"
                      r" \((\d+(?:\.\d+)?)%\) rejectedWorkflows=(\d+)")
PRINT_LOCK = Lock()
ARRIVAL_RATES = (15, 30, 45, 60)


def deadline_factor(value):
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("deadline factor must be 1.2, 2, or 4") from error
    for allowed in (1.2, 2.0, 4.0):
        if math.isclose(parsed, allowed, rel_tol=0.0, abs_tol=1e-9):
            return f"{allowed:g}"
    raise argparse.ArgumentTypeError("deadline factor must be 1.2, 2, or 4")


def report(message):
    with PRINT_LOCK:
        print(message, flush=True)


def show_progress(line, scenario, position, total):
    match = PROGRESS.search(line)
    if match is None:
        if line.strip():
            report(f"[{position}/{total} {scenario}] {line.rstrip()}")
        return
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
    report(status)


def run_one(scenario, source, output, classes, repetitions,
            progress_interval, position, total):
    mean = scenario.split("_")[0].removeprefix("arrival")
    original = json.loads((source / f"dax_poisson_arrivals_mean{mean}s_500workflows.json")
                          .read_text())
    if len(original) != 500:
        raise ValueError(f"Expected 500 original workflows for {scenario}")
    middle = original[100:400]
    if len(middle) != 300:
        raise ValueError(f"Expected exactly 300 main workflows for {scenario}")
    shift = middle[0]["arrival_time_seconds"]
    rebased = [{**row, "arrival_time_seconds":
                round(row["arrival_time_seconds"] - shift, 6)} for row in middle]
    destination = output / scenario / "middle300"
    destination.mkdir(parents=True, exist_ok=True)
    manifest = destination / "middle300_manifest.json"
    manifest.write_text(json.dumps(rebased, indent=2) + "\n")
    (destination / "run_config.json").write_text(json.dumps({
        "scenario": scenario + "_middle300",
        "workflow_count": 300,
        "source_positions_1_based": [101, 400],
        "source_arrival_shift_seconds": shift,
        "region": "us-east-2",
        "pricing": "historical NOSF type capacities, Ohio on-demand proxy rates",
        "task_runtime": "shared CBMW TXT sample, rigid across eligible VM types",
        "planning_runtime": "CBMW_CONSERVATIVE (mu * 1.20 by default)",
        "vm_selection": "minimum incremental hourly rental among subdeadline-feasible active and new VMs; ties by earliest finish; if none feasible, earliest finish",
        "waiting_tasks_per_vm": "unbounded FIFO, subject to subdeadline feasibility or earliest-finish fallback",
        "priority": "EFT",
        "vm_billing_seconds": 3600,
        "vm_billing_starts_at": "ready_time_after_provisioning",
        "vm_provisioning_seconds": 60,
        "repetitions": repetitions,
        "progress_interval_seconds": progress_interval,
    }, indent=2) + "\n")
    command = [
        "java", "-Xms128m", "-Xmx3g", "-Dcbmw.algorithms=NOSF",
        "-Dnosf.profile=COMMON_MARKET", "-Dnosf.priority.policy=EFT",
        "-Dnosf.runtime.estimator=CBMW_CONSERVATIVE",
        "-Dnosf.billing.quantum.sec=3600",
        "-Dcbmw.ondemand.delay.sec=60", "-Dcbmw.runtime.resample=false",
        f"-Dcbmw.progress.interval.sec={progress_interval}",
        f"-Dcbmw.scenarios={scenario}_middle300",
        "-Dcbmw.workflow.dataset.mode=MIDDLE_300",
        "-Dcbmw.max.workflows=300",
        f"-Dcbmw.workflow.manifest.{mean}.MIDDLE_300={manifest}",
        f"-Dcbmw.workflow.dir={source}", f"-Dcbmw.output.dir={destination}",
        "-Dcbmw.export.details=false", "-Dcbmw.detail.log=false",
        "-Dcbmw.quiet=true", "-Dcbmw.generate.gantt=false",
        "-Dcbmw.generate.comparison=false",
        f"-Dcbmw.repetitions={repetitions}", "-cp", f"{classes}:{JARS}",
        "org.workflowsim.examples.cbmw.CBMWSimulation",
    ]
    report(f"Starting NOSF middle 300 ({position}/{total}): {scenario}")
    with (destination / "run.log").open("w", buffering=1) as log:
        with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True,
                              bufsize=1) as process:
            for line in process.stdout:
                log.write(line)
                if line.startswith("[progress]"):
                    show_progress(line, scenario, position, total)
            exit_code = process.wait()
    if exit_code:
        raise RuntimeError(f"NOSF {scenario} failed (exit {exit_code}); "
                           f"see {destination / 'run.log'}")
    report(f"Completed NOSF middle 300: {scenario}")


def combine_results(output, scenarios, factor, repetitions):
    """Stream four independent outputs into factor-specific combined CSVs."""
    combined = output / "combined" / f"alpha{factor}"
    combined.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".staging-", dir=combined) as temporary:
        staging = Path(temporary)
        for filename, expected_rows in (("results.csv", repetitions),
                                        ("results_aggregate.csv", 1)):
            header = None
            row_count = 0
            with (staging / filename).open("w", newline="", encoding="utf-8") as destination:
                writer = None
                for scenario in scenarios:
                    source = output / scenario / "middle300" / "algorithms" / "NOSF" / filename
                    with source.open(newline="", encoding="utf-8") as handle:
                        reader = csv.DictReader(handle)
                        if not reader.fieldnames:
                            raise ValueError(f"Missing CSV header: {source}")
                        if header is None:
                            header = reader.fieldnames
                            writer = csv.DictWriter(destination, fieldnames=header)
                            writer.writeheader()
                        elif reader.fieldnames != header:
                            raise ValueError(f"Mismatched CSV columns: {source}")
                        count = 0
                        for row in reader:
                            if row.get("scenario") != scenario + "_middle300" or row.get("algorithm") != "NOSF":
                                raise ValueError(f"Unexpected scenario or algorithm in {source}")
                            writer.writerow(row)
                            count += 1
                            row_count += 1
                        if count != expected_rows:
                            raise ValueError(f"Expected {expected_rows} rows in {source}, got {count}")
            report(f"[combined] {filename}: {row_count} scenario rows")

        task_header = None
        with (staging / "task_execution.csv").open("wb") as destination:
            for scenario in scenarios:
                source = output / scenario / "middle300" / "algorithms" / "NOSF" / "task_execution.csv"
                with source.open("rb") as handle:
                    header = handle.readline()
                    if not header:
                        raise ValueError(f"Empty task CSV: {source}")
                    if task_header is None:
                        task_header = header
                        destination.write(header)
                    elif header != task_header:
                        raise ValueError(f"Mismatched task CSV columns: {source}")
                    shutil.copyfileobj(handle, destination)
                    handle.seek(-1, os.SEEK_END)
                    if handle.read(1) != b"\n":
                        destination.write(b"\n")

        (staging / "run_config.json").write_text(json.dumps({
            "deadline_factor": float(factor),
            "arrival_rates_seconds": list(ARRIVAL_RATES),
            "scenarios": [scenario + "_middle300" for scenario in scenarios],
            "workflows_per_scenario": 300,
            "repetitions": repetitions,
            "vm_selection": "minimum incremental hourly rental among subdeadline-feasible active and new VMs; ties by earliest finish; if none feasible, earliest finish",
            "provisioning_seconds": 60,
            "billing_seconds": 3600,
            "billing_starts_at": "ready_time_after_provisioning",
        }, indent=2) + "\n", encoding="utf-8")
        for filename in ("results.csv", "results_aggregate.csv",
                         "task_execution.csv", "run_config.json"):
            os.replace(staging / filename, combined / filename)
    report(f"[combined] Four arrivals at deadline factor {factor}: {combined}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true")
    group.add_argument("--scenario", choices=SCENARIOS)
    group.add_argument("--deadline-factor", type=deadline_factor,
                       help="run arrivals 15, 30, 45, and 60 for factor 1.2, 2, or 4")
    parser.add_argument("--workflow-dir", type=Path,
                        default=ROOT / "test_workflows" / "workflows")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "outputs" / "nosf_cost_aware_middle300")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--progress-interval-sec", type=int, default=10,
                        help="wall-clock interval between task-progress updates (default: 10)")
    parser.add_argument("--workers", type=int, choices=range(1, 5),
                        help="concurrent JVMs, 1 to 4 (default: 4 with --deadline-factor; otherwise 2)")
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--classes-dir", type=Path)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.progress_interval_sec < 1:
        parser.error("--progress-interval-sec must be positive")
    workers = args.workers if args.workers is not None else (4 if args.deadline_factor else 2)
    if workers >= 3:
        report(f"[run] {workers} JVMs can use up to "
               f"{workers * 3} GiB of Java heap, plus JVM/OS overhead")
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
    scenarios = ([f"arrival{mean}_alpha{args.deadline_factor}"
                  for mean in ARRIVAL_RATES] if args.deadline_factor else
                 SCENARIOS if args.all else [args.scenario])
    with ThreadPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(run_one, scenario, source, output, classes,
                            args.repetitions, args.progress_interval_sec,
                            position, len(scenarios)): scenario
                for position, scenario in enumerate(scenarios, start=1)}
        failures = []
        for job in as_completed(jobs):
            try:
                job.result()
            except Exception as error:
                failures.append(jobs[job])
                report(f"FAILED NOSF middle 300: {jobs[job]}: {error}")
    if failures:
        raise RuntimeError("NOSF scenarios failed: " + ", ".join(failures))
    if args.deadline_factor:
        combine_results(output, scenarios, args.deadline_factor,
                        args.repetitions)


if __name__ == "__main__":
    main()
