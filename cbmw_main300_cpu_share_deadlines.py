#!/usr/bin/env python3
"""Recover CBMW main-300 CPU work shares and deadline misses from task_execution.csv.

Run: python3 cbmw_main300_cpu_share_deadlines.py /path/to/task_execution.csv
Output: /path/to/main300_cpu_share_deadlines.txt

The full-500 trace uses workflow IDs 100..399 for its measured 300.
Shares reproduce CBMWAccounting.getCpuWorkShare: task cores multiplied by
'Actual Runtime Sample (s)' for completed tasks, grouped by final VM type.
For CBMW's static plan, the largest LFT among an accepted workflow's tasks
is its workflow deadline (terminal tasks have LFT = workflow deadline).
The task CSV rounds LFT, runtime, and finish to four decimal places, so
the report flags workflows near their deadline as potentially ambiguous.
"""

import argparse
import csv
from dataclasses import dataclass, field
import math
from pathlib import Path


REQUIRED = {
    "Algorithm", "Scenario", "Run", "Workflow ID", "Workflow Disposition",
    "Actual VM Type", "Task Cores", "Actual Runtime Sample (s)",
    "Finish Time (s)", "LFT (s)", "Status", "Spot Interruptions",
}
ROUNDING_MARGIN_SECONDS = 0.0001
MISS_THRESHOLD_SECONDS = 1.0


@dataclass
class Workflow:
    disposition: str = ""
    finish: float = -math.inf
    deadline: float = -math.inf
    completed_tasks: int = 0


@dataclass
class Scenario:
    workflows: dict[int, Workflow] = field(default_factory=dict)
    reserved_work: float = 0.0
    on_demand_work: float = 0.0
    other_work: float = 0.0


def finite_float(raw: str, field_name: str, row_number: int) -> float:
    try:
        value = float(raw)
    except (ValueError, TypeError) as exc:
        raise ValueError(f"Row {row_number}: missing or invalid {field_name}: {raw!r}") from exc
    if not math.isfinite(value):
        raise ValueError(f"Row {row_number}: non-finite {field_name}: {raw!r}")
    return value


def calculate(csv_path: Path):
    scenarios = {}
    with csv_path.open(newline="", encoding="utf-8-sig") as source:
        reader = csv.DictReader(source)
        missing = REQUIRED - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"Missing task_execution.csv columns: {', '.join(sorted(missing))}")
        for row_number, row in enumerate(reader, start=2):
            if row["Algorithm"] != "CBMW" or not row["Scenario"].endswith("_full500"):
                continue
            workflow_id = int(row["Workflow ID"])
            if not 100 <= workflow_id < 400:
                continue
            key = (row["Scenario"], row["Run"], row.get("Seed", ""))
            scenario = scenarios.setdefault(key, Scenario())
            workflow = scenario.workflows.setdefault(workflow_id, Workflow())
            disposition = row["Workflow Disposition"]
            if workflow.disposition and workflow.disposition != disposition:
                raise ValueError(f"Row {row_number}: inconsistent workflow disposition")
            workflow.disposition = disposition

            interruptions = int(row["Spot Interruptions"])
            if interruptions:
                raise ValueError(f"Row {row_number}: interrupted task in {key}; "
                                 "the task CSV cannot reconstruct its complete work history")
            if disposition != "ACCEPTED":
                continue
            if row["Status"] != "SUCCESS":
                raise ValueError(f"Row {row_number}: accepted workflow {workflow_id} "
                                 "has an unfinished task")

            finish = finite_float(row["Finish Time (s)"], "Finish Time (s)", row_number)
            lft = finite_float(row["LFT (s)"], "LFT (s)", row_number)
            runtime = finite_float(row["Actual Runtime Sample (s)"],
                                   "Actual Runtime Sample (s)", row_number)
            cores = finite_float(row["Task Cores"], "Task Cores", row_number)
            if runtime < 0 or cores < 0:
                raise ValueError(f"Row {row_number}: negative runtime or core count")
            workflow.completed_tasks += 1
            workflow.finish = max(workflow.finish, finish)
            workflow.deadline = max(workflow.deadline, lft)

            work = runtime * cores
            if row["Actual VM Type"] == "Reserved":
                scenario.reserved_work += work
            elif row["Actual VM Type"] == "On-Demand":
                scenario.on_demand_work += work
            else:
                scenario.other_work += work

    if not scenarios:
        raise ValueError("No CBMW _full500 task records found")
    return scenarios


def report(scenarios: dict, output: Path):
    with output.open("w", encoding="utf-8") as destination:
        destination.write("CBMW: main 300 workflows (IDs 100..399)\n")
        destination.write("CPU shares use task cores x Actual Runtime Sample (s).\n")
        destination.write("Deadline misses count only accepted, completed workflows "
                          "with lateness >= 1.0 s.\n")
        destination.write("Deadlines are inferred from the largest task LFT (s).\n\n")
        for key, scenario in sorted(scenarios.items()):
            if len(scenario.workflows) != 300:
                raise ValueError(f"{key}: found {len(scenario.workflows)} of 300 "
                                 "main workflow IDs; task CSV may be incomplete")
            total_work = scenario.reserved_work + scenario.on_demand_work + scenario.other_work
            if total_work <= 0:
                raise ValueError(f"{key}: no completed main-workflow CPU work")
            accepted = [w for w in scenario.workflows.values()
                        if w.disposition == "ACCEPTED"]
            for workflow in accepted:
                if workflow.completed_tasks == 0 or not math.isfinite(workflow.deadline):
                    raise ValueError(f"{key}: accepted main workflow is incomplete")
            misses = sum(w.finish - w.deadline >= MISS_THRESHOLD_SECONDS
                         for w in accepted)
            borderline = sum(abs((w.finish - w.deadline) - MISS_THRESHOLD_SECONDS)
                             <= ROUNDING_MARGIN_SECONDS
                             for w in accepted)
            scenario_name, run, seed = key
            destination.write(f"Scenario: {scenario_name} | Run: {run}")
            if seed:
                destination.write(f" | Seed: {seed}")
            destination.write("\n")
            destination.write(f"  Accepted main workflows: {len(accepted)} / 300\n")
            destination.write(f"  Reserved CPU work share:  "
                              f"{scenario.reserved_work / total_work:.8f} "
                              f"({100 * scenario.reserved_work / total_work:.4f}%)\n")
            destination.write(f"  On-demand CPU work share: "
                              f"{scenario.on_demand_work / total_work:.8f} "
                              f"({100 * scenario.on_demand_work / total_work:.4f}%)\n")
            if scenario.other_work:
                destination.write(f"  Other CPU work share:     "
                                  f"{scenario.other_work / total_work:.8f}\n")
            destination.write(f"  Missed-deadline main workflows: {misses}\n")
            destination.write(f"  Within {ROUNDING_MARGIN_SECONDS:g} s of 1 s miss cutoff: "
                              f"{borderline}\n")
            if borderline:
                destination.write("  Note: task CSV times are rounded to 0.0001 s; "
                                  "near-cutoff classifications should be checked "
                                  "against the workflow summary.\n")
            destination.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task_execution", type=Path, help="CBMW task_execution.csv")
    parser.add_argument("--output", type=Path,
                        help="Text output (default: main300_cpu_share_deadlines.txt "
                             "next to task_execution.csv)")
    args = parser.parse_args()
    output = args.output or args.task_execution.with_name("main300_cpu_share_deadlines.txt")
    try:
        report(calculate(args.task_execution), output)
    except (OSError, ValueError) as exc:
        parser.exit(2, f"Error: {exc}\n")
    print(f"Saved {output}")


if __name__ == "__main__":
    main()
