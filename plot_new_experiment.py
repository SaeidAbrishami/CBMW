"""
New experiment comparison plots.

Reads Output/results.csv from CBMWSimulation and writes one figure per load
scenario. Each figure compares CBMW, StaticGreedy, and DynamicGreedy across
tight/medium/loose deadlines for:
  - total cost
  - deadline success rate
  - reserved utilization
  - on-demand usage ratio
"""

import csv
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


CSV_FILE = sys.argv[1] if len(sys.argv) > 1 else "Output/results.csv"
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "Output"

ALGORITHMS = ["CBMW", "NOSF", "CEWB", "StaticGreedy", "DynamicGreedy"]
DEADLINES = ["tight", "medium", "loose"]
METRICS = [
    ("totalCost", "Total Cost", "Cost ($)", "dollar"),
    ("deadlineRate", "Deadline Success Rate", "Rate", "rate"),
    ("reservedUtil", "Reserved Utilization", "Utilization (%)", "percent"),
    ("onDemandUsageRatio", "On-Demand Usage Ratio", "Ratio", "rate"),
]
COLORS = {
    "CBMW": "#2563EB",
    "NOSF": "#7C3AED",
    "CEWB": "#DC2626",
    "StaticGreedy": "#F97316",
    "DynamicGreedy": "#16A34A",
}


def load_rows(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def numeric(row, key):
    if key == "totalCost" and key not in row:
        return float(row.get("onDemandCost", 0)) + float(row.get("reservedCost", 0))
    return float(row.get(key, 0) or 0)


def value_for(rows, load, deadline, algorithm, metric):
    for row in rows:
        if (row.get("load") == load
                and row.get("deadlineClass") == deadline
                and row.get("algorithm") == algorithm):
            value = numeric(row, metric)
            if metric == "reservedUtil":
                value *= 100.0
            return value
    return 0.0


def label_for(value, fmt):
    if fmt == "dollar":
        return f"${value:,.1f}"
    if fmt == "percent":
        return f"{value:.1f}%"
    if fmt == "rate":
        return f"{value:.3f}"
    return f"{value:,.1f}"


def plot_load(rows, load):
    fig, axes = plt.subplots(2, 2, figsize=(15, 9))
    fig.suptitle(f"New Experiment - {load.title()} Load", fontsize=15)

    x = np.arange(len(DEADLINES))
    width = 0.14
    offsets = np.linspace(-2 * width, 2 * width, len(ALGORITHMS))

    for ax, (metric, title, ylabel, fmt) in zip(axes.flat, METRICS):
        max_value = 0.0
        for offset, algorithm in zip(offsets, ALGORITHMS):
            values = [value_for(rows, load, d, algorithm, metric) for d in DEADLINES]
            max_value = max(max_value, max(values or [0]))
            bars = ax.bar(
                x + offset,
                values,
                width,
                label=algorithm,
                color=COLORS.get(algorithm, "#666666"),
                edgecolor="black",
                linewidth=0.5,
            )
            for bar, value in zip(bars, values):
                if value <= 0:
                    continue
                ax.text(
                    bar.get_x() + bar.get_width() / 2,
                    bar.get_height() + max(max_value, 1.0) * 0.015,
                    label_for(value, fmt),
                    ha="center",
                    va="bottom",
                    fontsize=8,
                    rotation=0,
                )

        ax.set_title(title, fontsize=11)
        ax.set_ylabel(ylabel, fontsize=10)
        ax.set_xticks(x)
        ax.set_xticklabels(DEADLINES)
        ax.grid(axis="y", linestyle="--", alpha=0.35)
        if fmt == "rate":
            ax.set_ylim(0, 1.05)
        elif fmt == "percent":
            ax.set_ylim(0, max(max_value * 1.25, 5))
        else:
            ax.set_ylim(0, max(max_value * 1.25, 1))

    axes[0, 0].legend(loc="upper left", fontsize=8)
    plt.tight_layout()
    os.makedirs(OUT_DIR, exist_ok=True)
    out_file = os.path.join(OUT_DIR, f"new_experiment_{load}.png")
    plt.savefig(out_file, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"[new-experiment] Chart saved to {out_file}")


def main():
    rows = load_rows(CSV_FILE)
    if not rows:
        print(f"[new-experiment] No rows found in {CSV_FILE}")
        return 1
    loads = [load for load in ["low", "moderate", "heavy"]
             if any(row.get("load") == load for row in rows)]
    for load in loads:
        plot_load(rows, load)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
