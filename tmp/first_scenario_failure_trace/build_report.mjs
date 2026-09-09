import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const inputPath = path.resolve(process.argv[2]);
const outputPath = path.resolve(process.argv[3]);
const previewDir = `${outputPath}.previews`;
const report = JSON.parse(await fs.readFile(inputPath, "utf8"));

const workbook = Workbook.create();
const summary = workbook.worksheets.add("Summary");
const failures = workbook.worksheets.add("Failed Workflows");
const method = workbook.worksheets.add("Method");
const font = "Arial";
const navy = "#1F4E78";
const blue = "#D9EAF7";
const light = "#F4F7FA";
const border = "#D5DCE3";

summary.showGridLines = false;
summary.getRange("A1:N1").format.font = { name: font, size: 11 };
summary.getRange("A1").values = [["First-scenario workflow failure trace"]];
summary.getRange("A1").format.font = { name: font, size: 16, bold: true, color: "#1F1F1F" };
summary.getRange("A2").values = [[
  `Scenario: ${report.metadata.scenario}. Source: ${report.metadata.source_file}.`
]];
summary.getRange("A2").format.font = { name: font, size: 10, italic: true, color: "#5B6573" };

summary.getRange("A4:B4").values = [["Outcome", "Value"]];
const s = report.summary;
const summaryRows = [
  ["Workflows", s.workflows],
  ["Accepted", s.accepted],
  ["Met deadline", s.met_deadline],
  ["Failed total", s.failed_total],
  ["Accepted but missed", s.accepted_missed],
  ["Rejected during planning", s.rejected_planning],
  ["Success rate", s.success_rate],
  ["Median lateness (s)", s.median_lateness_s],
  ["Mean lateness (s)", s.mean_lateness_s],
  ["Maximum lateness (s)", s.max_lateness_s],
];
summary.getRange(`A5:B${4 + summaryRows.length}`).values = summaryRows;
summary.getRange("B5:B10").format.numberFormat = "#,##0";
summary.getRange("B11").format.numberFormat = "0.0%";
summary.getRange("B12:B14").format.numberFormat = "#,##0.00";

const causes = Object.entries(report.cause_counts);
summary.getRange("D4:E4").values = [["Failure cause", "Workflows"]];
summary.getRange(`D5:E${4 + causes.length}`).values = causes;
summary.getRange(`E5:E${4 + causes.length}`).format.numberFormat = "#,##0";

const familyStart = 17;
summary.getRange(`A${familyStart}:F${familyStart}`).values = [[
  "Workflow family", "Total", "Accepted", "Met", "Accepted misses", "Rejected"
]];
const familyRows = report.family_counts.map(row => [
  row.family, row.total, row.accepted, row.met, row.missed, row.rejected,
]);
summary.getRange(`A${familyStart + 1}:F${familyStart + familyRows.length}`).values = familyRows;
summary.getRange(`B${familyStart + 1}:F${familyStart + familyRows.length}`).format.numberFormat = "#,##0";

for (const range of ["A4:B4", "D4:E4", `A${familyStart}:F${familyStart}`]) {
  summary.getRange(range).format = {
    fill: navy,
    font: { name: font, bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    borders: { preset: "outside", style: "thin", color: navy },
  };
}
summary.getRange(`A5:B${4 + summaryRows.length}`).format.borders = {
  insideHorizontal: { style: "thin", color: border },
};
summary.getRange(`D5:E${4 + causes.length}`).format.borders = {
  insideHorizontal: { style: "thin", color: border },
};
summary.getRange(`A${familyStart + 1}:F${familyStart + familyRows.length}`).format.borders = {
  insideHorizontal: { style: "thin", color: border },
};
summary.getRange("A4:F30").format.font = { name: font, size: 10 };
summary.getRange("A:A").format.columnWidth = 29;
summary.getRange("B:B").format.columnWidth = 15;
summary.getRange("C:C").format.columnWidth = 3;
summary.getRange("D:D").format.columnWidth = 40;
summary.getRange("E:E").format.columnWidth = 13;

if (causes.length > 0) {
  const chart = summary.charts.add("bar", summary.getRange(`D4:E${4 + causes.length}`));
  chart.title = "Failed workflows by traced cause";
  chart.titleTextStyle.typeface = font;
  chart.titleTextStyle.fontSize = 12;
  chart.hasLegend = false;
  chart.xAxis = { textStyle: { typeface: font, fontSize: 9 } };
  chart.yAxis = { numberFormatCode: "0", numberFormatSourceLinked: false,
    textStyle: { typeface: font, fontSize: 9 } };
  chart.setPosition("G4", "N15");
}

const headers = [
  "Workflow ID", "Workflow", "Family", "Failure type", "Cause category", "Tasks",
  "Arrival (s)", "Deadline (s)", "Completion (s)", "Lateness (s)", "Late tasks",
  "Reserved tasks", "On-demand tasks", "Rescheduled tasks", "Runtime-overrun tasks",
  "Max start deviation (s)", "Max finish deviation (s)", "Root task ID", "Root task name",
  "Root VM type", "Root delay (s)", "Root resource wait (s)", "Root dependency delay (s)",
  "Root scheduler delay (s)", "Root order delay (s)", "Root runtime overrun (s)",
  "Trace length", "Terminal task ID", "Terminal task name", "Terminal VM type",
  "Terminal finish deviation (s)", "Cause detail", "Trace path"
];
const detailRows = report.failed_workflows.map(w => [
  w.workflow_id, w.workflow, w.family, w.failure_type, w.cause_category, w.tasks,
  w.arrival_s, w.deadline_s, w.completion_s, w.lateness_s, w.late_tasks,
  w.reserved_tasks, w.on_demand_tasks, w.rescheduled_tasks, w.runtime_overrun_tasks,
  w.max_start_deviation_s, w.max_finish_deviation_s, w.root_task_id, w.root_task_name,
  w.root_vm_type, w.root_delay_s, w.root_resource_wait_s, w.root_dependency_delay_s,
  w.root_scheduler_delay_s, w.root_order_delay_s, w.root_runtime_overrun_s,
  w.trace_length, w.terminal_task_id, w.terminal_task_name, w.terminal_vm_type,
  w.terminal_finish_deviation_s, w.cause_detail, w.trace_path,
]);
failures.showGridLines = false;
failures.getRange("A1:AG1").values = [headers];
failures.getRange(`A2:AG${detailRows.length + 1}`).values = detailRows;
failures.getRange("A1:AG1").format = {
  fill: navy,
  font: { name: font, size: 10, bold: true, color: "#FFFFFF" },
  wrapText: true,
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#FFFFFF" },
};
failures.getRange(`A2:AG${detailRows.length + 1}`).format = {
  font: { name: font, size: 9, color: "#1F1F1F" },
  verticalAlignment: "top",
  borders: { insideHorizontal: { style: "thin", color: border } },
};
failures.getRange(`A2:A${detailRows.length + 1}`).format.numberFormat = "0";
failures.getRange(`F2:F${detailRows.length + 1}`).format.numberFormat = "#,##0";
failures.getRange(`G2:J${detailRows.length + 1}`).format.numberFormat = "#,##0.000";
failures.getRange(`K2:Q${detailRows.length + 1}`).format.numberFormat = "#,##0.000";
failures.getRange(`U2:Z${detailRows.length + 1}`).format.numberFormat = "#,##0.000";
failures.getRange(`AE2:AE${detailRows.length + 1}`).format.numberFormat = "#,##0.000";
failures.getRange("A:A").format.columnWidth = 12;
failures.getRange("B:B").format.columnWidth = 24;
failures.getRange("C:C").format.columnWidth = 18;
failures.getRange("D:E").format.columnWidth = 32;
failures.getRange("F:Q").format.columnWidth = 15;
failures.getRange("R:T").format.columnWidth = 17;
failures.getRange("U:AE").format.columnWidth = 17;
failures.getRange("AF:AG").format.columnWidth = 55;
failures.getRange(`AF2:AG${detailRows.length + 1}`).format.wrapText = true;
failures.freezePanes.freezeRows(1);
failures.freezePanes.freezeColumns(1);
failures.tables.add(`A1:AG${detailRows.length + 1}`, true, "FailedWorkflowTraceTable");

method.showGridLines = false;
method.getRange("A1").values = [["Trace method"]];
method.getRange("A1").format.font = { name: font, size: 16, bold: true, color: "#1F1F1F" };
method.getRange("A3:B3").values = [["Item", "Definition"]];
const methodRows = [
  ["Scope", `Only ${report.metadata.scenario}; one row per workflow that did not meet the deadline.`],
  ["Direct planning failures", "The export records REJECTED_PLANNING_FAILED. The planner has one rejection branch: an entry task cannot fit reserved capacity and arrival + 90-second on-demand provisioning + planned runtime exceeds that task's LFT."],
  ["Accepted deadline failures", "Workflow completion is the latest task finish. Workflow deadline is reconstructed as the maximum task LFT. A workflow fails when completion exceeds the deadline."],
  ["Expected execution start", "Reserved: Scheduled Start SST. On-demand: Scheduled Start SST + 90 seconds provisioning."],
  ["Dependency tracing", "Start at the terminal task. When upstream dependency delay dominates local delay, follow the latest-finishing parent. Repeat until a local timing component dominates."],
  ["Local timing components", "Resource-capacity wait, scheduler observation delay, late on-demand order, and actual runtime above the planning runtime."],
  ["Cause category", "The largest local component at the traced root task. It is an evidence-based attribution from exported timings, not a simulator-generated error code."],
  ["Trace path", "Terminal task followed by selected latest-finishing parents, shown with <- toward the traced root."],
  ["Reproducibility", `Classification version ${report.metadata.classification_version}; provisioning delay ${report.metadata.provisioning_delay_s} seconds.`],
];
method.getRange(`A4:B${3 + methodRows.length}`).values = methodRows;
method.getRange("A3:B3").format = {
  fill: navy,
  font: { name: font, size: 10, bold: true, color: "#FFFFFF" },
  horizontalAlignment: "center",
  verticalAlignment: "center",
};
method.getRange(`A4:B${3 + methodRows.length}`).format = {
  font: { name: font, size: 10 },
  wrapText: true,
  verticalAlignment: "top",
  borders: { insideHorizontal: { style: "thin", color: border } },
};
method.getRange("A:A").format.columnWidth = 28;
method.getRange("B:B").format.columnWidth = 105;
method.getRange(`A4:A${3 + methodRows.length}`).format.fill = light;

await fs.mkdir(path.dirname(outputPath), { recursive: true });
const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(outputPath);

const summaryCheck = await workbook.inspect({
  kind: "table", sheetId: "Summary", range: "A1:N30", include: "values,formulas",
  tableMaxRows: 30, tableMaxCols: 14, maxChars: 9000,
});
console.log(summaryCheck.ndjson);
const detailCheck = await workbook.inspect({
  kind: "table", sheetId: "Failed Workflows", range: "A1:AG8", include: "values,formulas",
  tableMaxRows: 8, tableMaxCols: 33, maxChars: 12000,
});
console.log(detailCheck.ndjson);
const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
});
console.log(errors.ndjson);

await fs.mkdir(previewDir, { recursive: true });
for (const [sheetName, range] of [
  ["Summary", "A1:N30"],
  ["Failed Workflows", "A1:AG25"],
  ["Method", `A1:B${3 + methodRows.length}`],
]) {
  const preview = await workbook.render({ sheetName, range, scale: 1, format: "png" });
  await fs.writeFile(path.join(previewDir, `${sheetName.replaceAll(" ", "_")}.png`),
    new Uint8Array(await preview.arrayBuffer()));
}
console.log(JSON.stringify({ outputPath, failedWorkflows: detailRows.length, previewDir }));
