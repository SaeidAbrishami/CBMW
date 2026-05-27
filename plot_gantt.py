"""
CBMW VM Gantt Chart
-------------------
Parses cbmw_detail.log and draws a two-panel Gantt chart:

  Top panel    — reserved VMs (IDs 0 .. NUM_RESERVED-1), fixed rows
  Bottom panel — on-demand VMs, rows numbered in order of first use

Both panels share the same x-axis (simulation time) and the same
colour scheme (one colour per workflow, cycling through tab20).
Hatched bars indicate workflows that missed their deadline.

Usage:
    python plot_gantt.py                          # uses cbmw_detail.log
    python plot_gantt.py my_run.log out.png       # custom log + output file
    python plot_gantt.py log.log out.png 0 2000   # restrict x-axis to [0, 2000]
"""

import re
import sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

# ── args ────────────────────────────────────────────────────────────────────
LOG_FILE     = sys.argv[1] if len(sys.argv) > 1 else "cbmw_detail.log"
OUT_FILE     = sys.argv[2] if len(sys.argv) > 2 else "vm_gantt.png"
T_MIN        = float(sys.argv[3]) if len(sys.argv) > 4 else None
T_MAX        = float(sys.argv[4]) if len(sys.argv) > 4 else None
NUM_RESERVED = 50
MISSED_HATCH = "////"
# ────────────────────────────────────────────────────────────────────────────

dispatch_re   = re.compile(r'\[t=\s*([\d.]+)\].*\[DISPATCH\s*\]\s*wf=(\S+)\s+task=(\d+).*?vm=(\d+)')
complete_re   = re.compile(r'\[t=\s*([\d.]+)\].*\[TASK-COMPLETE\s*\]\s*task=(\d+)\s+wf=(\d+)\s+vm=(\d+)\((.*?)\)')
wfcomplete_re = re.compile(r'\[t=\s*([\d.]+)\].*\[WF-COMPLETE\s*\]\s*wf=(\d+).*->\s*(MET|MISSED)')

tasks   = {}   # task_id -> {wf, vm, start, end, vm_type}
wf_fate = {}   # wf_id   -> "MET" | "MISSED"

with open(LOG_FILE, encoding="utf-8") as f:
    for line in f:
        m = dispatch_re.search(line)
        if m:
            tasks[int(m.group(3))] = {
                "wf": m.group(2), "vm": int(m.group(4)), "start": float(m.group(1))
            }
            continue
        m = complete_re.search(line)
        if m:
            tid = int(m.group(2))
            if tid in tasks:
                tasks[tid]["end"]     = float(m.group(1))
                tasks[tid]["vm_type"] = m.group(5)
            continue
        m = wfcomplete_re.search(line)
        if m:
            wf_fate[int(m.group(2))] = m.group(3)

# ── split bars into reserved / on-demand ────────────────────────────────────
complete_tasks = [d for d in tasks.values() if "start" in d and "end" in d and d["end"] > d["start"]]
reserved_bars  = [d for d in complete_tasks if d["vm"] < NUM_RESERVED]
od_bars        = [d for d in complete_tasks if d["vm"] >= NUM_RESERVED]

if not reserved_bars and not od_bars:
    print("No task intervals found in", LOG_FILE)
    sys.exit(1)

# ── colour map — shared across both panels ───────────────────────────────────
all_wf_ids = sorted({int(d["wf"]) for d in complete_tasks if d["wf"].isdigit()})
palette    = plt.colormaps["tab20"].colors
wf_color   = {wf: palette[i % len(palette)] for i, wf in enumerate(all_wf_ids)}

# ── on-demand row assignment via slot packing ───────────────────────────────
# Each task is placed in the earliest display row that is free at its start.
# A row is free once the previous task on it has ended.
# This compresses 1000+ ephemeral VMs down to max-concurrency rows.
od_row = {}          # task_id (by index in od_bars) -> row index
slot_free_at = []    # slot_free_at[row] = time when that row next becomes free

for d in sorted(od_bars, key=lambda x: x["start"]):
    start = d["start"]
    end   = d["end"]
    row   = next((i for i, t in enumerate(slot_free_at) if t <= start), None)
    if row is None:
        row = len(slot_free_at)
        slot_free_at.append(end)
    else:
        slot_free_at[row] = end
    d["_od_row"] = row

num_od_rows = len(slot_free_at)

# ── figure layout: two panels sharing x-axis ────────────────────────────────
reserved_height = max(6,  NUM_RESERVED * 0.18)
od_height       = max(3,  num_od_rows  * 0.18) if num_od_rows else 2
fig_height      = reserved_height + od_height + 1.5   # +gap for title/labels

fig, (ax_res, ax_od) = plt.subplots(
    2, 1,
    figsize=(22, fig_height),
    sharex=True,
    gridspec_kw={"height_ratios": [reserved_height, od_height], "hspace": 0.08},
)

def draw_bars(ax, bars, y_fn):
    for d in bars:
        wf    = int(d["wf"]) if d["wf"].isdigit() else -1
        t0    = d["start"]
        dur   = d["end"] - d["start"]
        row   = y_fn(d)
        color = wf_color.get(wf, "#cccccc")
        hatch = MISSED_HATCH if wf_fate.get(wf) == "MISSED" else None
        ax.broken_barh([(t0, dur)], (row - 0.45, 0.9),
                       facecolors=color, edgecolors="black",
                       linewidth=0.3, hatch=hatch)

# ── reserved panel ───────────────────────────────────────────────────────────
draw_bars(ax_res, reserved_bars, lambda d: d["vm"])
ax_res.set_ylabel("Reserved VM", fontsize=11)
ax_res.set_yticks(range(NUM_RESERVED))
ax_res.set_yticklabels([str(i) for i in range(NUM_RESERVED)], fontsize=6)
ax_res.set_ylim(-0.5, NUM_RESERVED - 0.5)
ax_res.invert_yaxis()
ax_res.grid(axis="x", linestyle="--", alpha=0.35)
ax_res.set_title(
    f"{OUT_FILE.replace('_gantt.png','')}  —  Reserved & On-demand VM utilisation"
    f"  (hatched = deadline missed)",
    fontsize=12, pad=8
)

# ── on-demand panel ──────────────────────────────────────────────────────────
if num_od_rows:
    draw_bars(ax_od, od_bars, lambda d: d["_od_row"])
    ax_od.set_ylabel(f"On-demand slot\n(max concurrency: {num_od_rows})", fontsize=11)
    ax_od.set_yticks(range(num_od_rows))
    ax_od.set_yticklabels([str(i) for i in range(num_od_rows)], fontsize=6)
    ax_od.set_ylim(-0.5, num_od_rows - 0.5)
    ax_od.invert_yaxis()
    ax_od.grid(axis="x", linestyle="--", alpha=0.35)
else:
    ax_od.text(0.5, 0.5, "No on-demand VMs used", transform=ax_od.transAxes,
               ha="center", va="center", fontsize=11, color="gray")
    ax_od.set_ylabel("On-demand VM", fontsize=11)

ax_od.set_xlabel("Simulation time (s)", fontsize=11)

if T_MIN is not None:
    ax_od.set_xlim(T_MIN, T_MAX)

# ── shared legend (up to 20 workflows) ──────────────────────────────────────
legend_wfs = all_wf_ids[:20]
handles = [mpatches.Patch(facecolor=wf_color[w], edgecolor="black", label=f"wf {w}")
           for w in legend_wfs]
if len(all_wf_ids) > 20:
    handles.append(mpatches.Patch(facecolor="#aaaaaa", label=f"… +{len(all_wf_ids)-20} more"))
ax_res.legend(handles=handles, loc="upper right", fontsize=7,
              ncol=2, title="Workflow", title_fontsize=8)

plt.savefig(OUT_FILE, dpi=150, bbox_inches="tight")
print(f"Chart saved to {OUT_FILE}  "
      f"({len(reserved_bars)} reserved bars, {len(od_bars)} on-demand bars, "
      f"max {num_od_rows} concurrent on-demand slots, {len(all_wf_ids)} workflows)")
