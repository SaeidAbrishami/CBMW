#!/usr/bin/env python3
"""
Merge per-algorithm VM outputs into the comparison folder.

Expected layout:
    Output/
      algorithms/
        CBMW/results.csv
        NOSF/results.csv
        CEWB/results.csv
        StaticGreedy/results.csv
        DynamicGreedy/results.csv

Usage:
    python scripts/merge_algorithm_outputs.py
    python scripts/merge_algorithm_outputs.py Output
"""

import csv
import os
import subprocess
import sys
from collections import OrderedDict


CSV_HEADER = [
    "scenario",
    "load",
    "deadlineClass",
    "algorithm",
    "arrivalScale",
    "tightness",
    "run",
    "total",
    "accepted",
    "deadlineRate",
    "onDemandCost",
    "spotCost",
    "reservedCost",
    "totalCost",
    "makespan",
    "reservedUtil",
    "onDemandUsageRatio",
    "spotUsageRatio",
]

AGGREGATE_HEADER = [
    "scenario",
    "load",
    "deadlineClass",
    "algorithm",
    "arrivalScale",
    "tightness",
    "runs",
    "avgTotal",
    "avgAccepted",
    "avgDeadlineRate",
    "avgOnDemandCost",
    "avgSpotCost",
    "avgReservedCost",
    "avgTotalCost",
    "avgMakespan",
    "avgReservedUtil",
    "avgOnDemandUsageRatio",
    "avgSpotUsageRatio",
]

NUMERIC_FIELDS = [
    "total",
    "accepted",
    "deadlineRate",
    "onDemandCost",
    "spotCost",
    "reservedCost",
    "totalCost",
    "makespan",
    "reservedUtil",
    "onDemandUsageRatio",
    "spotUsageRatio",
]


def read_rows(algorithms_dir):
    rows = []
    if not os.path.isdir(algorithms_dir):
        return rows

    for algorithm in sorted(os.listdir(algorithms_dir)):
        path = os.path.join(algorithms_dir, algorithm, "results.csv")
        if not os.path.isfile(path):
            continue
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                rows.append(row)
        print(f"[merge] read {path}")
    return rows


def write_csv(path, header, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(f"[merge] wrote {path}")


def as_float(row, key):
    value = row.get(key, "")
    return float(value) if value not in ("", None) else 0.0


def aggregate_rows(rows):
    groups = OrderedDict()
    for row in rows:
        key = (
            row.get("scenario", ""),
            row.get("load", ""),
            row.get("deadlineClass", ""),
            row.get("algorithm", ""),
        )
        if key not in groups:
            groups[key] = {
                "scenario": row.get("scenario", ""),
                "load": row.get("load", ""),
                "deadlineClass": row.get("deadlineClass", ""),
                "algorithm": row.get("algorithm", ""),
                "arrivalScale": as_float(row, "arrivalScale"),
                "tightness": as_float(row, "tightness"),
                "runs": 0,
                **{field: 0.0 for field in NUMERIC_FIELDS},
            }
        group = groups[key]
        group["runs"] += 1
        for field in NUMERIC_FIELDS:
            group[field] += as_float(row, field)

    aggregate = []
    for group in groups.values():
        runs = max(group["runs"], 1)
        aggregate.append({
            "scenario": group["scenario"],
            "load": group["load"],
            "deadlineClass": group["deadlineClass"],
            "algorithm": group["algorithm"],
            "arrivalScale": f"{group['arrivalScale']:.4f}",
            "tightness": f"{group['tightness']:.1f}",
            "runs": str(group["runs"]),
            "avgTotal": f"{group['total'] / runs:.2f}",
            "avgAccepted": f"{group['accepted'] / runs:.2f}",
            "avgDeadlineRate": f"{group['deadlineRate'] / runs:.4f}",
            "avgOnDemandCost": f"{group['onDemandCost'] / runs:.4f}",
            "avgSpotCost": f"{group['spotCost'] / runs:.4f}",
            "avgReservedCost": f"{group['reservedCost'] / runs:.2f}",
            "avgTotalCost": f"{group['totalCost'] / runs:.4f}",
            "avgMakespan": f"{group['makespan'] / runs:.2f}",
            "avgReservedUtil": f"{group['reservedUtil'] / runs:.4f}",
            "avgOnDemandUsageRatio": f"{group['onDemandUsageRatio'] / runs:.4f}",
            "avgSpotUsageRatio": f"{group['spotUsageRatio'] / runs:.4f}",
        })
    return aggregate


def run_charts(root_dir, aggregate_path, comparison_dir):
    script = os.path.join(root_dir, "plot_new_experiment.py")
    if not os.path.isfile(script):
        script = "plot_new_experiment.py"
    result = subprocess.run(
        [sys.executable, script, aggregate_path, comparison_dir],
        check=False,
        text=True,
    )
    if result.returncode != 0:
        print(f"[merge] chart generation exited with code {result.returncode}")


def main():
    output_root = sys.argv[1] if len(sys.argv) > 1 else "Output"
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    algorithms_dir = os.path.join(output_root, "algorithms")
    comparison_dir = os.path.join(output_root, "comparison")

    rows = read_rows(algorithms_dir)
    if not rows:
        print(f"[merge] no algorithm results found under {algorithms_dir}", file=sys.stderr)
        return 1

    rows.sort(key=lambda row: (
        row.get("load", ""),
        row.get("deadlineClass", ""),
        row.get("algorithm", ""),
        row.get("scenario", ""),
    ))
    aggregate = aggregate_rows(rows)

    results_path = os.path.join(comparison_dir, "results.csv")
    aggregate_path = os.path.join(comparison_dir, "results_aggregate.csv")
    write_csv(results_path, CSV_HEADER, rows)
    write_csv(aggregate_path, AGGREGATE_HEADER, aggregate)
    run_charts(repo_root, aggregate_path, comparison_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
