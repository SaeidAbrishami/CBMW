import csv
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict


SCENARIO = "arrival15_alpha2_full500"
PROVISIONING_SECONDS = 90.0
EPSILON = 1e-6


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return math.nan


def finite(value):
    return isinstance(value, (int, float)) and math.isfinite(value)


def positive(value):
    return max(0.0, value) if finite(value) else 0.0


def parent_ids(text):
    return re.findall(r"ID\d+", text or "")


def workflow_family(path):
    name = os.path.basename(path)
    match = re.match(r"(.+_\d+)_\d+\.xml$", name, flags=re.IGNORECASE)
    return match.group(1) if match else os.path.splitext(name)[0]


def expected_execution_start(task):
    sst = number(task.get("Scheduled Start SST (s)"))
    if not finite(sst):
        return math.nan
    if task.get("Planned VM Type") == "On-Demand":
        return sst + PROVISIONING_SECONDS
    return sst


def timing_components(task):
    expected = expected_execution_start(task)
    dependency = number(task.get("Dependency Ready Time (s)"))
    observed = number(task.get("Scheduler Observed Ready Time (s)"))
    start = number(task.get("Start Time (s)"))
    order = number(task.get("Provision Order Time (s)"))
    actual = number(task.get("Actual Runtime Sample (s)"))
    planning = number(task.get("Planning Runtime Used (s)"))

    dependency_delay = positive(dependency - expected) if finite(expected) else 0.0
    eligibility = max(v for v in (expected, dependency) if finite(v)) if any(
        finite(v) for v in (expected, dependency)
    ) else math.nan
    scheduler_delay = positive(observed - eligibility) if finite(observed) and finite(eligibility) else 0.0
    runnable = max(v for v in (expected, dependency, observed) if finite(v)) if any(
        finite(v) for v in (expected, dependency, observed)
    ) else math.nan
    resource_wait = positive(start - runnable) if finite(start) and finite(runnable) else 0.0
    order_delay = positive(order - number(task.get("Scheduled Start SST (s)"))) \
        if finite(order) and finite(number(task.get("Scheduled Start SST (s)"))) else 0.0
    runtime_overrun = positive(actual - planning) if finite(actual) and finite(planning) else 0.0
    return {
        "expected_start": expected,
        "dependency_delay": dependency_delay,
        "scheduler_delay": scheduler_delay,
        "resource_wait": resource_wait,
        "order_delay": order_delay,
        "runtime_overrun": runtime_overrun,
    }


def trace_root(terminal, by_id):
    current = terminal
    visited = set()
    chain = []
    while current and current.get("Task ID") not in visited:
        task_id = current.get("Task ID")
        visited.add(task_id)
        components = timing_components(current)
        chain.append(task_id)
        parents = [by_id[p] for p in parent_ids(current.get("Parents ID")) if p in by_id]
        parents_with_finish = [p for p in parents if finite(number(p.get("Finish Time (s)")))]
        local_max = max(
            components["scheduler_delay"],
            components["resource_wait"],
            components["order_delay"],
            components["runtime_overrun"],
        )
        if parents_with_finish and components["dependency_delay"] > local_max + 0.5:
            current = max(parents_with_finish, key=lambda p: number(p.get("Finish Time (s)")))
            continue
        break

    components = timing_components(current)
    candidates = {
        "Scheduler observation delay": components["scheduler_delay"],
        "Resource-capacity wait": components["resource_wait"],
        "Late on-demand order": components["order_delay"],
        "Runtime overrun": components["runtime_overrun"],
    }
    cause, magnitude = max(candidates.items(), key=lambda item: item[1])
    vm_type = current.get("Actual VM Type") or current.get("Planned VM Type") or "Unknown"
    if cause == "Resource-capacity wait":
        cause = "Reserved-capacity wait" if vm_type == "Reserved" else "On-demand execution wait"
    if magnitude <= 0.5:
        if components["dependency_delay"] > 0.5:
            cause = "Unresolved upstream dependency delay"
            magnitude = components["dependency_delay"]
        else:
            cause = "Combined sub-second timing effects"
    return current, components, chain, cause, magnitude


def aggregate_workflow(rows):
    workflow_id = int(rows[0]["Workflow ID"])
    path = rows[0]["Workflow"]
    disposition = rows[0]["Workflow Disposition"]
    family = workflow_family(path)
    by_id = {row["Task ID"]: row for row in rows}
    arrival = min(number(row["EST (s)"]) for row in rows if finite(number(row["EST (s)"])))
    deadline = max(number(row["LFT (s)"]) for row in rows if finite(number(row["LFT (s)"])))
    finishes = [number(row["Finish Time (s)"]) for row in rows if finite(number(row["Finish Time (s)"]))]
    completion = max(finishes) if finishes else math.nan
    accepted = disposition == "ACCEPTED"
    met = accepted and finite(completion) and completion <= deadline + EPSILON

    base = {
        "workflow_id": workflow_id,
        "workflow": os.path.basename(path),
        "family": family,
        "disposition": disposition,
        "tasks": len(rows),
        "arrival_s": arrival,
        "deadline_s": deadline,
        "completion_s": completion,
        "lateness_s": completion - deadline if finite(completion) else math.nan,
        "late_tasks": sum(positive(number(row["Finish Deviation from SubDeadline (s)"])) > 0 for row in rows),
        "reserved_tasks": sum(row.get("Actual VM Type") == "Reserved" for row in rows),
        "on_demand_tasks": sum(row.get("Actual VM Type") == "On-Demand" for row in rows),
        "rescheduled_tasks": sum(row.get("Rescheduled") == "YES" for row in rows),
        "runtime_overrun_tasks": sum(
            number(row["Actual Runtime Sample (s)"]) > number(row["Planning Runtime Used (s)"]) + EPSILON
            for row in rows
            if finite(number(row["Actual Runtime Sample (s)"]))
            and finite(number(row["Planning Runtime Used (s)"]))
        ),
        "max_start_deviation_s": max(
            [number(row["Start Deviation from SST (s)"]) for row in rows
             if finite(number(row["Start Deviation from SST (s)"]))] or [math.nan]
        ),
        "max_finish_deviation_s": max(
            [number(row["Finish Deviation from SubDeadline (s)"]) for row in rows
             if finite(number(row["Finish Deviation from SubDeadline (s)"]))] or [math.nan]
        ),
        "accepted": accepted,
        "met_deadline": met,
    }

    if met:
        return base

    if not accepted:
        triggers = [
            row for row in rows
            if not parent_ids(row.get("Parents ID")) and row.get("Planned VM Type") == "On-Demand"
        ]
        trigger = triggers[0] if triggers else None
        if trigger:
            planning = number(trigger["Planning Runtime Used (s)"])
            lft = number(trigger["LFT (s)"])
            earliest_finish = arrival + PROVISIONING_SECONDS + planning
            infeasible_by = earliest_finish - lft
            detail = (
                f"Entry task {trigger['Task ID']} required on-demand capacity; arrival + 90 s provisioning "
                f"+ {planning:.3f} s runtime exceeded its LFT by {infeasible_by:.3f} s."
            )
            root_id = trigger["Task ID"]
            root_name = trigger["Task Name"]
            root_vm = "On-Demand"
        else:
            earliest_finish = math.nan
            infeasible_by = math.nan
            detail = "Planner rejected the workflow; the triggering entry task was not recoverable from the export."
            root_id = ""
            root_name = ""
            root_vm = ""
        base.update({
            "failure_type": "Rejected before execution",
            "cause_category": "Entry-task on-demand provisioning infeasible",
            "cause_detail": detail,
            "root_task_id": root_id,
            "root_task_name": root_name,
            "root_vm_type": root_vm,
            "root_delay_s": infeasible_by,
            "root_resource_wait_s": 0.0,
            "root_dependency_delay_s": 0.0,
            "root_scheduler_delay_s": 0.0,
            "root_order_delay_s": 0.0,
            "root_runtime_overrun_s": 0.0,
            "trace_length": 1 if trigger else 0,
            "trace_path": root_id,
            "terminal_task_id": "",
            "terminal_task_name": "",
            "terminal_vm_type": "",
            "terminal_finish_s": math.nan,
            "terminal_finish_deviation_s": math.nan,
            "earliest_feasible_finish_s": earliest_finish,
        })
        return base

    terminal = max(
        (row for row in rows if finite(number(row["Finish Time (s)"]))),
        key=lambda row: number(row["Finish Time (s)"]),
    )
    root, components, chain, cause, magnitude = trace_root(terminal, by_id)
    root_vm = root.get("Actual VM Type") or root.get("Planned VM Type") or "Unknown"
    detail = (
        f"Delay traced from terminal task {terminal['Task ID']} through {len(chain) - 1} dependency link(s) "
        f"to {root['Task ID']} ({root['Task Name']}). Dominant local delay: {cause.lower()} "
        f"({magnitude:.3f} s); workflow finished {completion - deadline:.3f} s late."
    )
    base.update({
        "failure_type": "Accepted but missed deadline",
        "cause_category": cause,
        "cause_detail": detail,
        "root_task_id": root["Task ID"],
        "root_task_name": root["Task Name"],
        "root_vm_type": root_vm,
        "root_delay_s": magnitude,
        "root_resource_wait_s": components["resource_wait"],
        "root_dependency_delay_s": components["dependency_delay"],
        "root_scheduler_delay_s": components["scheduler_delay"],
        "root_order_delay_s": components["order_delay"],
        "root_runtime_overrun_s": components["runtime_overrun"],
        "trace_length": len(chain),
        "trace_path": " <- ".join(chain),
        "terminal_task_id": terminal["Task ID"],
        "terminal_task_name": terminal["Task Name"],
        "terminal_vm_type": terminal.get("Actual VM Type") or terminal.get("Planned VM Type") or "Unknown",
        "terminal_finish_s": number(terminal["Finish Time (s)"]),
        "terminal_finish_deviation_s": number(terminal["Finish Deviation from SubDeadline (s)"]),
        "earliest_feasible_finish_s": math.nan,
    })
    return base


def clean_json(value):
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {key: clean_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [clean_json(item) for item in value]
    return value


def main():
    source = sys.argv[1]
    output = sys.argv[2]
    workflows = []
    current_id = None
    current_rows = []
    saw_scenario = False

    with open(source, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            scenario = row["Scenario"]
            if scenario != SCENARIO:
                if saw_scenario:
                    break
                continue
            saw_scenario = True
            workflow_id = row["Workflow ID"]
            if current_id is not None and workflow_id != current_id:
                workflows.append(aggregate_workflow(current_rows))
                current_rows = []
            current_id = workflow_id
            current_rows.append(row)
    if current_rows:
        workflows.append(aggregate_workflow(current_rows))

    failures = [workflow for workflow in workflows if not workflow["met_deadline"]]
    cause_counts = Counter(workflow["cause_category"] for workflow in failures)
    type_counts = Counter(workflow["failure_type"] for workflow in failures)
    families = defaultdict(lambda: Counter(total=0, accepted=0, met=0, missed=0, rejected=0))
    for workflow in workflows:
        family = families[workflow["family"]]
        family["total"] += 1
        if workflow["accepted"]:
            family["accepted"] += 1
            if workflow["met_deadline"]:
                family["met"] += 1
            else:
                family["missed"] += 1
        else:
            family["rejected"] += 1

    accepted_misses = [w for w in failures if w["failure_type"] == "Accepted but missed deadline"]
    lateness = sorted(w["lateness_s"] for w in accepted_misses)
    median_lateness = lateness[len(lateness) // 2] if lateness else math.nan
    report = {
        "metadata": {
            "scenario": SCENARIO,
            "source_file": os.path.basename(source),
            "classification_version": "1.0",
            "provisioning_delay_s": PROVISIONING_SECONDS,
        },
        "summary": {
            "workflows": len(workflows),
            "accepted": sum(w["accepted"] for w in workflows),
            "met_deadline": sum(w["met_deadline"] for w in workflows),
            "failed_total": len(failures),
            "accepted_missed": len(accepted_misses),
            "rejected_planning": sum(not w["accepted"] for w in workflows),
            "success_rate": sum(w["met_deadline"] for w in workflows) / len(workflows),
            "median_lateness_s": median_lateness,
            "mean_lateness_s": sum(w["lateness_s"] for w in accepted_misses) / len(accepted_misses),
            "max_lateness_s": max(w["lateness_s"] for w in accepted_misses),
        },
        "failure_type_counts": dict(sorted(type_counts.items())),
        "cause_counts": dict(sorted(cause_counts.items(), key=lambda item: (-item[1], item[0]))),
        "family_counts": [
            {"family": name, **dict(counts)}
            for name, counts in sorted(families.items())
        ],
        "failed_workflows": sorted(
            failures,
            key=lambda workflow: (
                0 if workflow["failure_type"] == "Rejected before execution" else 1,
                workflow["workflow_id"],
            ),
        ),
    }
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(clean_json(report), handle, indent=2)
    print(json.dumps(clean_json({key: report[key] for key in (
        "summary", "failure_type_counts", "cause_counts", "family_counts"
    )}), indent=2))


if __name__ == "__main__":
    main()
