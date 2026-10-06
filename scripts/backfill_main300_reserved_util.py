#!/usr/bin/env python3
"""Add main-cohort reserved utilization to existing CBMW result CSVs.

Reads the full-500 task_execution.csv and the matching arrival manifest.
Writes new *_main300.csv files; existing experiment outputs are untouched.
"""

import argparse
import csv
import json
import math
from pathlib import Path
import re


SCENARIO_MEAN = re.compile(r"^arrival(\d+)_.*_full500$")


def manifest_window(workflow_dir, mean, cache):
    if mean not in cache:
        manifest = workflow_dir / f"dax_poisson_arrivals_mean{mean}s_500workflows.json"
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            arrivals = [(str(name), float(time)) for name, time in data.items()]
        else:
            arrivals = [(entry["workflow_name"], float(entry["arrival_time_seconds"]))
                        for entry in data]
        if len(arrivals) != 500 or any(not math.isfinite(t) for _, t in arrivals):
            raise ValueError(f"Expected 500 finite arrivals in {manifest}")
        # WorkflowLoader orders by arrival time and then DAX path.
        arrivals.sort(key=lambda entry: (entry[1], entry[0]))
        cache[mean] = (arrivals[100][1], arrivals[399][1])
    return cache[mean]


def task_totals(path):
    totals = {}
    with path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            scenario = row["Scenario"]
            if SCENARIO_MEAN.match(scenario) is None:
                continue
            workflow_id = int(row["Workflow ID"])
            if not 100 <= workflow_id < 400:
                continue
            if row["Workflow Disposition"] != "ACCEPTED":
                continue
            key = (scenario, int(row["Run"]))
            state = totals.setdefault(key, [0.0, float("-inf"), False])
            try:
                finish = float(row["Finish Time (s)"])
            except ValueError:
                state[2] = True
                continue
            if not math.isfinite(finish):
                state[2] = True
                continue
            state[1] = max(state[1], finish)
            if row["Actual VM Type"] != "Reserved":
                continue
            try:
                submitted = float(row["Submit Time (s)"])
                cores = int(row["Task Cores"])
            except ValueError:
                state[2] = True
                continue
            if not math.isfinite(submitted) or finish < submitted or cores < 0:
                state[2] = True
                continue
            state[0] += cores * (finish - submitted)
    return totals


def add_column(fields, column, after):
    if column in fields:
        return list(fields)
    result = list(fields)
    result.insert(result.index(after) + 1, column)
    return result


def backfill(results, workflow_dir):
    totals = task_totals(results.with_name("task_execution.csv"))
    windows = {}
    with results.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        fields = add_column(reader.fieldnames, "reservedUtilMain300", "reservedUtil")
        rows = list(reader)
    grouped = {}
    for row in rows:
        match = SCENARIO_MEAN.match(row["scenario"])
        if match is None or int(row["total"]) != 500:
            row["reservedUtilMain300"] = ""
            continue
        start, last_arrival = manifest_window(workflow_dir, match.group(1), windows)
        state = totals.get((row["scenario"], int(row["run"])))
        if state is not None and state[2]:
            raise ValueError(f"Incomplete measured tasks in {row['scenario']} run {row['run']}")
        latest_finish = state[1] if state is not None else float("-inf")
        end = max(last_arrival, latest_finish)
        capacity = int(row["reservedTotalCores"])
        if capacity == 0 and row["algorithm"].startswith("CBMW"):
            # Existing ablation CSVs omit their reserved capacity columns.
            raise ValueError("Pass a result with reservedTotalCores set for " + row["algorithm"])
        if end <= start:
            raise ValueError(f"Empty measured window in {row['scenario']}")
        utilization = (state[0] if state else 0.0) / (capacity * (end - start)) if capacity else 0.0
        row["reservedUtilMain300"] = f"{utilization:.4f}"
        group = (row["scenario"], row["load"], row["deadlineClass"],
                 row["algorithm"], row["nosfProfile"])
        grouped.setdefault(group, []).append(utilization)
    target = results.with_name("results_main300.csv")
    with target.open("w", newline="", encoding="utf-8") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    print(target)

    aggregate = results.with_name("results_aggregate.csv")
    if aggregate.exists():
        with aggregate.open(newline="", encoding="utf-8") as source:
            reader = csv.DictReader(source)
            aggregate_fields = add_column(reader.fieldnames, "avgReservedUtilMain300",
                                          "avgReservedUtil")
            aggregate_rows = list(reader)
        for row in aggregate_rows:
            group = (row["scenario"], row["load"], row["deadlineClass"],
                     row["algorithm"], row["nosfProfile"])
            values = grouped.get(group, [])
            row["avgReservedUtilMain300"] = (
                f"{sum(values) / len(values):.4f}" if values else "")
        aggregate_target = results.with_name("results_aggregate_main300.csv")
        with aggregate_target.open("w", newline="", encoding="utf-8") as destination:
            writer = csv.DictWriter(destination, fieldnames=aggregate_fields)
            writer.writeheader()
            writer.writerows(aggregate_rows)
        print(aggregate_target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", required=True, type=Path,
                        help="Experiment output folder containing algorithms/*/results.csv")
    parser.add_argument("--workflow-dir", required=True, type=Path,
                        help="Folder containing the full-500 arrival JSON manifests")
    args = parser.parse_args()
    candidates = sorted(args.output_root.rglob("results.csv"))
    if args.output_root.name == "results.csv":
        candidates = [args.output_root]
    found = 0
    for results in candidates:
        if (results.parent / "task_execution.csv").exists():
            backfill(results, args.workflow_dir)
            found += 1
    if not found:
        parser.error("No results.csv with a sibling task_execution.csv was found")


if __name__ == "__main__":
    main()
