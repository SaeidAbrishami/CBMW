"""
CBMW Algorithm Comparison Charts
---------------------------------
Reads the combined comparison results CSV and produces a 2x3 bar-chart figure comparing
CBMW, StaticGreedy, and DynamicGreedy on five metrics:
  - Overall deadline satisfaction rate (met deadline / total submitted)
  - On-demand cost ($)
  - Total cost (on-demand + reserved) ($)
  - Makespan (simulation seconds)
  - Reserved VM utilization (%)

Usage:
    python plot_comparison.py                        # uses Output/comparison/results.csv
    python plot_comparison.py results.csv out.png    # custom input / output
"""

import sys
import csv
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

CSV_FILE = sys.argv[1] if len(sys.argv) > 1 else "Output/comparison/results.csv"
OUT_FILE = sys.argv[2] if len(sys.argv) > 2 else "Output/comparison/comparison.png"

# ── load CSV ─────────────────────────────────────────────────────────────────
rows = []
try:
    with open(CSV_FILE, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
except FileNotFoundError:
    print(f"[comparison] CSV not found: {CSV_FILE}")
    sys.exit(1)

if not rows:
    print(f"[comparison] No data rows in {CSV_FILE}")
    sys.exit(1)

# ── extract metrics per algorithm ────────────────────────────────────────────
ALGO_ORDER = ["CBMW", "StaticGreedy", "DynamicGreedy"]
data = {}
for row in rows:
    algo = row["algorithm"]
    reserved_util = float(row.get("reservedUtil", 0))
    data[algo] = {
        "overallSuccessRate": float(row["overallSuccessRate"]),
        "onDemandCost":  float(row["onDemandCost"]),
        "totalCost":     float(row["onDemandCost"]) + float(row["reservedCost"]),
        "makespan":      float(row["makespan"]),
        "reservedUtil":  reserved_util * 100,  # convert to percent
    }

algos  = [a for a in ALGO_ORDER if a in data]
colors = ["#2196F3", "#FF9800", "#4CAF50"][:len(algos)]
x      = np.arange(len(algos))
bar_w  = 0.5

def vals(key):
    return [data[a][key] for a in algos]

# ── figure: 2x3 subplots (5 used, last cell hidden) ──────────────────────────
fig, axes = plt.subplots(2, 3, figsize=(16, 9))
fig.suptitle("Algorithm Comparison  (TIGHTNESS = 2.0)", fontsize=14, y=1.01)

metrics = [
    (axes[0, 0], "overallSuccessRate", "Deadline Satisfaction Rate", "Rate", "rate"),
    (axes[0, 1], "onDemandCost",  "On-Demand Cost",               "Cost ($)",   "dollar"),
    (axes[0, 2], "totalCost",     "Total Cost",                   "Cost ($)",   "dollar"),
    (axes[1, 0], "makespan",      "Makespan",                     "Time (s)",   "plain"),
    (axes[1, 1], "reservedUtil",  "Reserved VM Utilization",      "Util (%)",   "percent"),
]

for ax, key, title, ylabel, fmt in metrics:
    v = vals(key)
    bars = ax.bar(x, v, width=bar_w, color=colors, edgecolor="black", linewidth=0.6)
    ax.set_title(title, fontsize=11)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_xticks(x)
    ax.set_xticklabels(algos, fontsize=10)
    ax.grid(axis="y", linestyle="--", alpha=0.4)
    if fmt == "rate":
        ax.set_ylim(0, 1.05)
    elif fmt == "percent":
        ax.set_ylim(0, max(max(v) * 1.2, 5))
    for bar, val in zip(bars, v):
        if fmt == "rate":
            label = f"{val:.3f}"
        elif fmt == "percent":
            label = f"{val:.1f}%"
        elif fmt == "dollar":
            label = f"${val:,.1f}"
        else:
            label = f"{val:,.1f}"
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.01 * max(v or [1]),
                label, ha="center", va="bottom", fontsize=9)

# Hide the unused 6th subplot
axes[1, 2].set_visible(False)

plt.tight_layout()
plt.savefig(OUT_FILE, dpi=150, bbox_inches="tight")
print(f"[comparison] Chart saved to {OUT_FILE}  ({len(algos)} algorithms compared)")
