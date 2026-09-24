#!/usr/bin/env python3
"""Run the CBMW text configuration on Linux with bounded process memory."""
import argparse
import csv
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = Path(__file__).resolve().parents[1]
JARS = ":".join(str(path) for path in sorted((ROOT / "lib").glob("*.jar")))
SUPPORTED = {15, 30, 45, 60, 75, 90}
FACTORS = {1.2, 2.0, 4.0}


def read_config(path):
    lines = [line.split("#", 1)[0].strip()
             for line in path.read_text().splitlines()]
    lines = [line for line in lines if line]
    if len(lines) < 3:
        raise ValueError("Expected algorithm, thread limit, and at least one scenario")
    algorithm = lines[0]
    if algorithm != "CBMW":
        raise ValueError("This batch launcher implements CBMW; baselines are separate")
    workers = int(lines[1])
    if workers < 1:
        raise ValueError("Concurrent experiment limit must be positive")
    scenarios = []
    for line in lines[2:]:
        fields = line.split()
        if len(fields) != 2:
            raise ValueError(f"Expected <mean seconds> <deadline factor>: {line}")
        mean, factor = int(fields[0]), float(fields[1])
        if mean not in SUPPORTED or factor not in FACTORS:
            raise ValueError(f"Unsupported mean/factor: {line}")
        if (mean, factor) in scenarios:
            raise ValueError(f"Duplicate scenario: {line}")
        scenarios.append((mean, factor))
    return algorithm, workers, scenarios


def compile_java(base):
    files = [str(path) for parent in ("sources", "examples")
             for path in (ROOT / parent).rglob("*.java")]
    with tempfile.TemporaryDirectory(prefix="cbmw_classes_") as staged:
        executable = shutil.which("javac")
        if executable:
            cmd = [executable, "-J-Xmx2g", "-classpath", JARS,
                   "-d", staged] + files
        else:
            cmd = ["java", "-Xmx2g", "com.sun.tools.javac.Main",
                   "-classpath", JARS, "-d", staged] + files
        subprocess.run(cmd, cwd=ROOT, check=True)
        dest = base / "build_classes"
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(staged, dest)
        return dest



def run_scenario(algorithm, mean, factor, base, classes,
                 max_workflows, heap_mib):
    label = f"arrival{mean}_alpha{factor:g}"
    dest = base / label
    dest.mkdir(parents=True, exist_ok=True)
    command = [
        "java", "-Xms128m", f"-Xmx{heap_mib}m",
        f"-Dcbmw.algorithms={algorithm}",
        f"-Dcbmw.scenarios={label}_full500,{label}_edge200",
        f"-Dcbmw.output.dir={dest}",
        "-Dcbmw.export.details=false",
        "-Dcbmw.detail.log=false",
        "-Dcbmw.quiet=true",
        "-Dcbmw.generate.gantt=false",
        "-Dcbmw.generate.comparison=false",
        "-Dcbmw.perf.metrics=true",
        f"-Dcbmw.perf.metrics.file={dest / 'performance_metrics.csv'}",
        "-Dcbmw.repetitions=1",
    ]
    if max_workflows is not None:
        command.append(f"-Dcbmw.max.workflows={max_workflows}")
    command += ["-cp", str(classes) + ":" + JARS,
                "org.workflowsim.examples.cbmw.CBMWSimulation"]
    with (dest / "run.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log,
                       stderr=subprocess.STDOUT, check=True)
    return dest


def combine_csv(paths, target):
    with target.open("w", newline="") as out:
        writer = None
        header = None
        for path in paths:
            with path.open(newline="") as source:
                reader = csv.reader(source)
                incoming = next(reader)
                if header is None:
                    header = incoming
                    writer = csv.writer(out)
                    writer.writerow(header)
                elif incoming != header:
                    raise ValueError(f"Inconsistent CSV columns in {path}")
                writer.writerows(reader)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "Output" / "batch")
    parser.add_argument("--max-workflows", type=int,
                        help="Smoke test cap (production: omit)")
    args = parser.parse_args()
    algorithm, requested, scenarios = read_config(args.config)
    # Full traces retain hundreds of thousands of task records. Reserve
    # memory for the OS and native JVM use on an 8 GiB Linux machine.
    production = args.max_workflows is None or args.max_workflows > 100
    heap_mib = 5120 if production else 1280
    workers = min(requested, 1 if production else 4, os.cpu_count() or 1)
    base = args.output.resolve()
    base.mkdir(parents=True, exist_ok=True)
    print(f"Compiling; then running {len(scenarios)} scenarios, "
          f"up to {workers} concurrent JVMs (requested {requested}); "
          f"heap {heap_mib} MiB per JVM.", flush=True)
    classes = compile_java(base)
    completed = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(run_scenario, algorithm, mean, factor,
                                   base, classes, args.max_workflows,
                                   heap_mib): (mean, factor)
                   for mean, factor in scenarios}
        for future in as_completed(futures):
            mean, factor = futures[future]
            try:
                completed.append(future.result())
            except subprocess.CalledProcessError:
                print(f"Scenario {mean} {factor:g} failed; see "
                      f"{base / f'arrival{mean}_alpha{factor:g}' / 'run.log'}",
                      file=sys.stderr)
                raise
            print(f"Finished {mean} {factor:g}", flush=True)
    completed.sort(key=lambda path: scenarios.index(
        tuple((int(path.name.split("_")[0][7:]),
               float(path.name.split("_")[1][5:])))))
    merged = base / "combined"
    merged.mkdir(exist_ok=True)
    for filename in ("results.csv", "results_aggregate.csv"):
        combine_csv([folder / "algorithms" / algorithm / filename
                     for folder in completed], merged / filename)
    combine_csv([folder / "performance_metrics.csv" for folder in completed],
                merged / "performance_metrics.csv")
    print(f"Results: {merged}", flush=True)


if __name__ == "__main__":
    main()
