#!/usr/bin/env python3
"""Summarize paired 500/200 CEWB runs and the attributed middle 300."""
import argparse
import csv
import json
from pathlib import Path


def read_rows(path):
    with path.open(newline="") as source:
        return list(csv.DictReader(source))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="runner output directory")
    parser.add_argument("--workflow-dir", type=Path,
                        help="original workflow manifests, to label individual costs")
    args = parser.parse_args()
    records = []
    namedCosts = []
    for directory in sorted(args.output.glob("arrival*_alpha*")):
        results = directory / "algorithms" / "CEWB" / "results.csv"
        costs = directory / "algorithms" / "CEWB" / "workflow_costs.csv"
        if not results.exists() or not costs.exists():
            print("Awaiting pair:", directory.name)
            continue
        log = directory / "run.log"
        if not log.exists() or not all(
                f"Completed: {directory.name} {mode}" in log.read_text()
                for mode in ("FULL_500", "EDGE_200")):
            print("Awaiting complete pair:", directory.name)
            continue
        rows = {row["scenario"].split("_")[-1]: row for row in read_rows(results)}
        if set(rows) != {"full500", "edge200"}:
            print("Awaiting matched pair:", directory.name)
            continue
        full, edge = rows["full500"], rows["edge200"]
        workflows = read_rows(costs)
        middle = [r for r in workflows if r["inMiddle300"] == "true"]
        if len(workflows) != 700 or len(middle) != 300:
            raise ValueError(f"Incomplete per-workflow accounting: {directory}")
        for label, record in (("full500", full), ("edge200", edge)):
            attributed = sum(float(r["attributedTotalCost"]) for r in workflows
                             if r["scenario"].endswith(label))
            if abs(attributed - float(record["totalCost"])) > 0.01:
                raise ValueError(f"Physical rental cost is not conserved in {directory} {label}:")
        marginal = float(full["totalCost"]) - float(edge["totalCost"])
        direct = sum(float(r["attributedTotalCost"]) for r in middle)
        isolatedFile = directory / "middle300" / "algorithms" / "CEWB" / "results.csv"
        middleLog = directory / "middle300" / "run.log"
        middleComplete = middleLog.exists() and (
            f"Completed: {directory.name} MIDDLE_300" in middleLog.read_text())
        isolated = read_rows(isolatedFile)[0] if isolatedFile.exists() and middleComplete else None
        if args.workflow_dir is not None:
            mean = directory.name.split("_")[0].removeprefix("arrival")
            root = args.workflow_dir
            fullNames = [r["workflow_name"] for r in json.loads(
                (root / f"dax_poisson_arrivals_mean{mean}s_500workflows.json")
                .read_text())]
            edgeNames = [r["workflow_name"] for r in json.loads(
                (root / f"dax_poisson_arrivals_mean{mean}s_edge200.json")
                .read_text())]
            if len(fullNames) != 500 or edgeNames != fullNames[:100] + fullNames[400:]:
                raise ValueError(f"Edge manifest is not the paired workflow subset: {mean}")
            allCosts = list(workflows)
            middleCosts = directory / "middle300" / "algorithms" / "CEWB" / "workflow_costs.csv"
            if isolated is not None and middleCosts.exists():
                allCosts.extend(read_rows(middleCosts))
            for row in allCosts:
                position = int(row["workflowPosition"])
                if row["scenario"].endswith("_full500"):
                    sourcePosition = position
                elif row["scenario"].endswith("_edge200"):
                    sourcePosition = position if position <= 100 else position + 300
                else:
                    sourcePosition = position + 100
                namedCosts.append({"pair": directory.name,
                                   "originalFull500Position": sourcePosition,
                                   "workflowName": fullNames[sourcePosition - 1],
                                   **row})
        if isolated is not None and (isolated["total"] != "300"
                                     or not isolated["scenario"].endswith("_middle300")):
            raise ValueError(f"Invalid isolated middle 300 result: {isolatedFile}")
        records.append({
            "pair": directory.name,
            "full500Usd": full["totalCost"],
            "edge200Usd": edge["totalCost"],
            "middle300MarginalUsd": f"{marginal:.4f}",
            "middle300AttributedUsd": f"{direct:.4f}",
            "sharedCapacityInteractionUsd": f"{marginal - direct:.4f}",
            "isolatedMiddle300Usd": isolated["totalCost"] if isolated else "",
            "isolatedMiddle300OnDemandUsd": isolated["onDemandCost"] if isolated else "",
            "isolatedMiddle300SpotUsd": isolated["spotCost"] if isolated else "",
            "isolatedMiddle300MetDeadline": isolated["metDeadline"] if isolated else "",
            "full500OnDemandUsd": full["onDemandCost"],
            "full500SpotUsd": full["spotCost"],
            "edge200OnDemandUsd": edge["onDemandCost"],
            "edge200SpotUsd": edge["spotCost"],
            "full500MetDeadline": full["metDeadline"],
            "edge200MetDeadline": edge["metDeadline"],
        })
    if records:
        destination = args.output / "paired_300_cost_summary.csv"
        with destination.open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=records[0])
            writer.writeheader()
            writer.writerows(records)
        print(f"Summarized {len(records)} matched pairs in {destination}")
    if namedCosts:
        namedDestination = args.output / "all_workflow_costs_named.csv"
        with namedDestination.open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=namedCosts[0])
            writer.writeheader()
            writer.writerows(namedCosts)
        print(f"Labeled {len(namedCosts)} individual workflow costs in {namedDestination}")


if __name__ == "__main__":
    main()
