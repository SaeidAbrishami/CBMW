"""
New experiment comparison plots.

Reads Output/comparison/results_aggregate.csv from CBMWSimulation and writes
one figure per load scenario. If the aggregate file is not available, it falls
back to Output/comparison/results.csv. Each figure compares all experiment
algorithms across
tight/medium/loose deadlines for:
  - total cost
  - overall deadline success rate (met deadline / total submitted)
  - reserved utilization
  - on-demand usage ratio
  - simulated workload duration
"""

import csv
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


DEFAULT_CSV = "Output/comparison/results_aggregate.csv"
FALLBACK_CSV = "Output/comparison/results.csv"
CSV_FILE = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CSV
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "Output/comparison"

ALGORITHMS = ["CBMW", "NOSF", "CEWB", "StaticGreedy", "DynamicGreedy"]
DEADLINES = ["alpha1.2", "alpha2", "alpha4"]
METRICS = [
    ("totalCost", "Total Cost", "Cost ($)", "dollar"),
    ("overallSuccessRate", "Deadline Success Rate", "Success (%)", "percent_ratio"),
    ("reservedUtil", "Reserved Utilization", "Utilization (%)", "percent_ratio"),
    ("onDemandUsageRatio", "On-Demand Usage Ratio", "Usage (%)", "percent_ratio"),
    ("simulationDurationHours", "Simulation Duration", "Simulated Hours", "hours"),
]
COLORS = {
    "CBMW": "#2563EB",
    "NOSF": "#7C3AED",
    "CEWB": "#DC2626",
    "StaticGreedy": "#F97316",
    "DynamicGreedy": "#16A34A",
}
MARKERS = {
    "CBMW": "o",
    "NOSF": "s",
    "CEWB": "^",
    "StaticGreedy": "D",
    "DynamicGreedy": "P",
}
AGGREGATE_KEYS = {
    "totalCost": "avgTotalCost",
    "overallSuccessRate": "avgOverallSuccessRate",
    "reservedUtil": "avgReservedUtil",
    "onDemandUsageRatio": "avgOnDemandUsageRatio",
    "simulationDurationHours": "avgSimulationDurationHours",
}


def load_rows(path):
    if not os.path.exists(path) and path == DEFAULT_CSV and os.path.exists(FALLBACK_CSV):
        print(f"[new-experiment] {DEFAULT_CSV} not found; using {FALLBACK_CSV}")
        path = FALLBACK_CSV
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def numeric(row, key):
    aggregate_key = AGGREGATE_KEYS.get(key)
    if aggregate_key and aggregate_key in row:
        return float(row.get(aggregate_key, 0) or 0)
    if key == "totalCost" and key not in row:
        return float(row.get("onDemandCost", 0)) + float(row.get("reservedCost", 0))
    return float(row.get(key, 0) or 0)


def value_for(rows, load, deadline, algorithm, metric, mode):
    for row in rows:
        if (row.get("load") == load
                and row.get("deadlineClass") == deadline
                and row.get("algorithm") == algorithm
                and row.get("scenario", "").endswith("_" + mode)):
            value = numeric(row, metric)
            if metric in {"overallSuccessRate", "reservedUtil", "onDemandUsageRatio"}:
                value *= 100.0
            return value
    return float("nan")


def plot_load(rows, load, mode):
    fig, axes = plt.subplots(3, 2, figsize=(15, 12))
    fig.suptitle(f"New Experiment - {load} / {mode}", fontsize=16, fontweight="bold")

    x = np.arange(len(DEADLINES))

    for ax, (metric, title, ylabel, fmt) in zip(axes.flat, METRICS):
        max_value = 0.0
        for algorithm in ALGORITHMS:
            if not any(r.get("algorithm") == algorithm for r in rows):
                continue
            values = [value_for(rows, load, d, algorithm, metric, mode) for d in DEADLINES]
            max_value = max(max_value, max([v for v in values if np.isfinite(v)] or [0]))
            ax.plot(
                x,
                values,
                label=algorithm,
                color=COLORS.get(algorithm, "#666666"),
                linewidth=2.2,
                marker=MARKERS.get(algorithm, "o"),
                markersize=7,
            )

        ax.set_title(title, fontsize=12)
        ax.set_ylabel(ylabel, fontsize=10)
        ax.set_xticks(x)
        ax.set_xticklabels([d.replace("alpha", "Alpha ") for d in DEADLINES])
        ax.grid(axis="both", linestyle="--", alpha=0.25)
        if fmt == "percent_ratio":
            ax.set_ylim(0, max(min(max_value * 1.18, 110), 5))
            ax.yaxis.set_major_formatter(lambda v, _: f"{v:.0f}%")
        elif fmt == "dollar":
            ax.set_ylim(0, max(max_value * 1.18, 1))
            ax.yaxis.set_major_formatter(lambda v, _, digits=2 if max_value < 10 else 0: f"${v:,.{digits}f}")
        else:
            ax.set_ylim(0, max(max_value * 1.18, 1))
            ax.yaxis.set_major_formatter(lambda v, _: f"{v:.1f} h")

    for ax in axes.flat[len(METRICS):]:
        ax.set_visible(False)

    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=len(ALGORITHMS), fontsize=9)
    plt.tight_layout(rect=[0, 0.06, 1, 0.96])
    os.makedirs(OUT_DIR, exist_ok=True)
    out_file = os.path.join(OUT_DIR, f"new_experiment_{load}_{mode}.png")
    plt.savefig(out_file, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"[new-experiment] Chart saved to {out_file}")


def main():
    rows = load_rows(CSV_FILE)
    if not rows:
        print(f"[new-experiment] No rows found in {CSV_FILE}")
        return 1
    loads = [load for load in ["arrival15", "arrival30", "arrival45", "arrival60"]
             if any(row.get("load") == load for row in rows)]
    for load in loads:
        for mode in ("full500", "edge200"):
            if any(r.get("load") == load and r.get("scenario", "").endswith("_" + mode) for r in rows):
                plot_load(rows, load, mode)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
