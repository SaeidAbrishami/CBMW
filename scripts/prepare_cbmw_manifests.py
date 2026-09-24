#!/usr/bin/env python3
"""Create deterministic 75/90-second traces and consistent 200-workflow views."""
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "test_workflows" / "workflows"
reference = json.loads((ROOT / "dax_poisson_arrivals_mean15s_500workflows.json").read_text())
names = [item["workflow_name"] for item in reference]
assert len(names) == len(set(names)) == 500

for mean in (75, 90):
    rng = random.Random(20260924 + mean)
    arrival = 0.0
    rows = []
    for name in names:
        arrival += rng.expovariate(1.0 / mean)
        rows.append({"workflow_name": name,
                     "arrival_time_seconds": round(arrival, 6)})
    path = ROOT / f"dax_poisson_arrivals_mean{mean}s_500workflows.json"
    path.write_text(json.dumps(rows, indent=2) + "\n")

for mean in (15, 30, 45, 60, 75, 90):
    full = json.loads((ROOT / f"dax_poisson_arrivals_mean{mean}s_500workflows.json").read_text())
    assert len(full) == 500
    # Compress out workflows 101-400, retaining the original 400->401 gap.
    offset = full[399]["arrival_time_seconds"] - full[99]["arrival_time_seconds"]
    edge = full[:100] + [
        {"workflow_name": item["workflow_name"],
         "arrival_time_seconds": round(item["arrival_time_seconds"] - offset, 6)}
        for item in full[400:]
    ]
    path = ROOT / f"dax_poisson_arrivals_mean{mean}s_edge200.json"
    path.write_text(json.dumps(edge, indent=2) + "\n")
