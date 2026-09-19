import fs from "node:fs/promises";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const input = "P:/University/CBMW_Workflow_Simulation/Output/arrival15_cbmw_20260918_retry1/algorithms/CBMW/results.csv";
const outputDir = "P:/University/CBMW_Workflow_Simulation/outputs/arrival15_cbmw_20260918";
const output = `${outputDir}/CBMW_arrival15_results.xlsx`;

function parseCsv(text) {
  const rows = [];
  let row = [];
  let value = "";
  let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        value += '"';
        index++;
      } else if (char === '"') {
        quoted = false;
      } else {
        value += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(value);
      value = "";
    } else if (char === '\n') {
      row.push(value.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      value = "";
    } else {
      value += char;
    }
  }
  if (value.length || row.length) {
    row.push(value.replace(/\r$/, ""));
    rows.push(row);
  }
  return rows;
}

const csvRows = parseCsv(await fs.readFile(input, "utf8"));
const headers = csvRows.shift();
const records = csvRows.filter(row => row.some(value => value !== ""))
  .map(row => Object.fromEntries(headers.map((header, index) => [header, row[index] ?? ""])));
if (records.length !== 6 || records.some(record => record.load !== "arrival15" || record.algorithm !== "CBMW")) {
  throw new Error("Expected exactly six completed CBMW arrival15 scenarios");
}

const numeric = value => value === "" ? null : Number(value);
const workbook = Workbook.create();
const summary = workbook.worksheets.add("Summary");
const metrics = workbook.worksheets.add("Scenario metrics");

summary.showGridLines = false;
summary.getRange("A1:K1").merge();
summary.getRange("A1").values = [["CBMW simulation results"]];
summary.getRange("A2:K2").merge();
summary.getRange("A2").values = [["Arrival interval: 15 seconds. One run per tightness and dataset mode."]];
summary.getRange("A4:K4").values = [[
  "Dataset", "Tightness", "Workflows", "Accepted", "Met deadline",
  "Deadline rate", "Overall success", "On-demand cost", "Reserved cost",
  "Total cost", "Makespan (s)"
]];
summary.getRange(`A5:K${4 + records.length}`).values = records.map(record => [
  record.load === "arrival15" && numeric(record.total) === 500 ? "FULL_500" : "EDGE_200",
  numeric(record.tightness), numeric(record.total), numeric(record.accepted),
  numeric(record.metDeadline), numeric(record.deadlineRate),
  numeric(record.overallSuccessRate), numeric(record.onDemandCost),
  numeric(record.reservedCost), numeric(record.totalCost), numeric(record.makespan)
]);
summary.getRange("A13:K13").merge();
summary.getRange("A13").values = [["Source: CBMW simulation results.csv. Workflow deadlines include the configured on-demand provisioning delay."]];

summary.getRange("A1:K1").format = { font: { name: "Arial", size: 15, bold: true, color: "#1F2937" } };
summary.getRange("A2:K2").format = { font: { name: "Arial", size: 10, italic: true, color: "#4B5563" } };
summary.getRange("A4:K4").format = { fill: "#1F4E78", font: { name: "Arial", bold: true, color: "#FFFFFF" }, horizontalAlignment: "center", verticalAlignment: "center", wrapText: true };
summary.getRange("A5:K10").format.font = { name: "Arial", size: 10, color: "#1F2937" };
summary.getRange("A4:K10").format.borders = { preset: "outside", style: "thin", color: "#B7C9D6" };
summary.getRange("A4:K10").format.borders = { preset: "inside", style: "thin", color: "#D9E2F3" };
summary.getRange("F5:G10").format.numberFormat = "0.0%";
summary.getRange("H5:J10").format.numberFormat = "#,##0.00";
summary.getRange("K5:K10").format.numberFormat = "#,##0.00";
summary.getRange("B5:E10").format.numberFormat = "#,##0.0";
summary.getRange("C5:E10").format.numberFormat = "#,##0";
summary.getRange("A13:K13").format = { font: { name: "Arial", size: 9, italic: true, color: "#4B5563" }, wrapText: true };
summary.getRange("A:K").format.autofitColumns();
summary.getRange("A:A").format.columnWidth = 15;
summary.getRange("B:B").format.columnWidth = 11;
summary.getRange("H:J").format.columnWidth = 15;
summary.getRange("K:K").format.columnWidth = 14;
summary.getRange("A1").format.rowHeight = 26;
summary.getRange("A4:K4").format.rowHeight = 30;
summary.freezePanes.freezeRows(4);

const metricRows = [headers, ...records.map(record => headers.map(header => {
  const value = record[header];
  return value !== "" && /^-?\d+(\.\d+)?$/.test(value) ? Number(value) : value;
}))];
metrics.getRangeByIndexes(0, 0, metricRows.length, headers.length).values = metricRows;
metrics.getRangeByIndexes(0, 0, 1, headers.length).format = { fill: "#1F4E78", font: { name: "Arial", bold: true, color: "#FFFFFF" }, wrapText: true, horizontalAlignment: "center", verticalAlignment: "center" };
metrics.getRangeByIndexes(1, 0, records.length, headers.length).format.font = { name: "Arial", size: 10, color: "#1F2937" };
metrics.getRangeByIndexes(0, 0, metricRows.length, headers.length).format.borders = { preset: "outside", style: "thin", color: "#B7C9D6" };
metrics.getRange("A1:BM7").format.autofitColumns();
metrics.getRange("A1:BM1").format.rowHeight = 36;
metrics.freezePanes.freezeRows(1);
metrics.freezePanes.freezeColumns(3);

await fs.mkdir(outputDir, { recursive: true });
const check = await workbook.inspect({ kind: "table", range: "Summary!A1:K13", include: "values,formulas", tableMaxRows: 13, tableMaxCols: 11 });
console.log(check.ndjson);
const errors = await workbook.inspect({ kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!", options: { useRegex: true, maxResults: 100 }, summary: "formula error scan" });
console.log(errors.ndjson);
const preview = await workbook.render({ sheetName: "Summary", range: "A1:K13", scale: 1.5 });
await fs.writeFile(`${outputDir}/summary_preview.png`, new Uint8Array(await preview.arrayBuffer()));
const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(output);
console.log(`Saved ${output}`);
