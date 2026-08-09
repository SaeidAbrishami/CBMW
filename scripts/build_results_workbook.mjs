#!/usr/bin/env node

/**
 * Build the five-sheet experiment workbook from per-algorithm results.csv files.
 *
 * Usage:
 *   node scripts/build_results_workbook.mjs [output-root] [workbook-path]
 *
 * Expected input:
 *   <output-root>/algorithms/<algorithm>/results.csv
 */
import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const ALGORITHMS = ["CBMW", "StaticGreedy", "DynamicGreedy", "NOSF", "CEWB"];
const SCENARIOS = [
  { arrival: 15, alpha: 2.0 },
  { arrival: 30, alpha: 1.2 },
  { arrival: 30, alpha: 2.0 },
  { arrival: 30, alpha: 3.0 },
  { arrival: 30, alpha: 4.0 },
  { arrival: 45, alpha: 2.0 },
  { arrival: 60, alpha: 2.0 },
];
const FULL_ONLY = new Set(["NOSF", "CEWB"]);
const sourceRoot = path.resolve(process.argv[2] ?? "Output");
const outputPath = path.resolve(process.argv[3]
  ?? path.join(sourceRoot, "comparison", "results.xlsx"));

const workbook = Workbook.create();
for (const algorithm of ALGORITHMS) workbook.worksheets.add(algorithm);

for (const algorithm of ALGORITHMS) {
  const csvPath = path.join(sourceRoot, "algorithms", algorithm, "results.csv");
  const sourceRows = await readCsvIfPresent(csvPath);
  const sheetRows = orderedRows(algorithm, sourceRows);
  populateSheet(workbook.worksheets.getItem(algorithm), algorithm, sheetRows);
}

await fs.mkdir(path.dirname(outputPath), { recursive: true });
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
console.log(`[workbook] Saved ${outputPath}`);

for (const algorithm of ALGORITHMS) {
  const check = await workbook.inspect({
    kind: "table",
    sheetId: algorithm,
    range: FULL_ONLY.has(algorithm) ? "A1:AL9" : "A1:AL16",
    include: "values,formulas",
    tableMaxRows: 5,
    tableMaxCols: 14,
    maxChars: 3500,
  });
  console.log(check.ndjson);
}
const formulaErrors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
  options: { useRegex: true, maxResults: 100 },
  summary: "final formula error scan",
  maxChars: 3000,
});
console.log(formulaErrors.ndjson);
const formulaCheck = await workbook.inspect({
  kind: "formula",
  sheetId: "CBMW",
  range: "G3:T3",
  options: { maxResults: 20 },
  maxChars: 3000,
});
console.log(formulaCheck.ndjson);

const previewDir = `${outputPath}.previews`;
await fs.mkdir(previewDir, { recursive: true });
for (const algorithm of ALGORITHMS) {
  const preview = await workbook.render({
    sheetName: algorithm,
    autoCrop: "all",
    scale: 1.25,
    format: "png",
  });
  await fs.writeFile(path.join(previewDir, `${algorithm}.png`),
    new Uint8Array(await preview.arrayBuffer()));
}

function scenarioName(scenario, mode) {
  return `arrival${compact(scenario.arrival)}_alpha${compact(scenario.alpha)}_${mode}`;
}

function compact(value) {
  return Number.isInteger(value) ? String(value) : String(value);
}

function orderedRows(algorithm, sourceRows) {
  const byScenario = new Map();
  for (const row of sourceRows) {
    const rows = byScenario.get(row.scenario) ?? [];
    rows.push(row);
    byScenario.set(row.scenario, rows);
  }
  for (const rows of byScenario.values()) {
    rows.sort((left, right) => number(left.run) - number(right.run));
  }

  const output = [];
  let scenarioNumber = 0;
  for (const scenario of SCENARIOS) {
    const modes = FULL_ONLY.has(algorithm) ? ["full500"] : ["full500", "edge200"];
    for (const mode of modes) {
      scenarioNumber++;
      const expected = mode === "full500" ? 500 : 200;
      const matches = byScenario.get(scenarioName(scenario, mode)) ?? [];
      if (matches.length === 0) {
        output.push({ experiment: scenarioNumber, expected, scenario, mode, source: null });
        continue;
      }
      for (const source of matches) {
        output.push({
          experiment: matches.length === 1
            ? scenarioNumber : `${scenarioNumber}-r${source.run ?? "0"}`,
          expected,
          scenario,
          mode,
          source,
        });
      }
    }
  }
  return output;
}

function populateSheet(sheet, algorithm, rows) {
  sheet.showGridLines = false;
  sheet.freezePanes.freezeRows(2);
  sheet.freezePanes.freezeColumns(5);

  sheet.mergeCells("C1:E1");
  sheet.mergeCells("F1:G1");
  sheet.mergeCells("H1:M1");
  sheet.mergeCells("N1:Z1");
  sheet.mergeCells("AA1:AF1");
  sheet.mergeCells("AG1:AL1");
  sheet.getRange("A1:AL2").values = [
    ["", "", "Parameters", null, null, "Success", null,
      "Simulation", null, null, null, null, null,
      "Reserved Instances", null, null, null, null, null, null, null, null,
      null, null, null, null,
      "On-Demand Optimization", null, null, null, null, null,
      "Utilized Resources", null, null, null, null, null],
    ["Experiment", "", "Workflows", "Arrival rate", "Alpha", "No.", "Rate",
      "Time (sec)", "Reserved cost", "On-demand cost", "Spot cost",
      "Total cost", "Marginal cost", "Count", "Cores/instance",
      "Memory/instance (MB)", "Total cores", "Total memory (MB)",
      "Core-sec", "Memory MB-sec", "Mean core util", "Min core util",
      "Max core util", "Mean memory util", "Min memory util",
      "Max memory util", "Count", "Avg. uptime (sec)", "Total cores",
      "Total memory (MB)", "Core-sec", "Memory MB-sec", "Mean cores",
      "Min cores", "Max cores", "Mean memory (MB)", "Min memory (MB)",
      "Max memory (MB)"],
  ];

  const data = rows.map((item) => rowValues(item));
  const firstDataRow = 3;
  const lastDataRow = firstDataRow + data.length - 1;
  if (data.length > 0) {
    sheet.getRange(`A${firstDataRow}:AL${lastDataRow}`).values = data;
    for (let offset = 0; offset < rows.length; offset++) {
      if (!rows[offset].source) continue;
      const r = firstDataRow + offset;
      sheet.getRange(`G${r}`).formulas = [[`=IF(C${r}=0,0,F${r}/C${r})`]];
      sheet.getRange(`L${r}`).formulas = [[`=I${r}+J${r}+K${r}`]];
      sheet.getRange(`Q${r}`).formulas = [[`=N${r}*O${r}`]];
      sheet.getRange(`R${r}`).formulas = [[`=N${r}*P${r}`]];
      sheet.getRange(`S${r}`).formulas = [[`=Q${r}*H${r}`]];
      sheet.getRange(`T${r}`).formulas = [[`=R${r}*H${r}`]];
    }
  }

  const darkGreen = "#548235";
  const lightGreen = "#A9D18E";
  const groupHeader = sheet.getRange("C1:AL1");
  groupHeader.format = {
    fill: darkGreen,
    font: { bold: true, color: "#FFFFFF", typeface: "Calibri", fontSize: 11 },
    horizontalAlignment: "center",
    verticalAlignment: "center",
  };
  const columnHeader = sheet.getRange("A2:AL2");
  columnHeader.format = {
    fill: lightGreen,
    font: { bold: true, color: "#1F1F1F", typeface: "Calibri", fontSize: 10 },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "inside", style: "thin", color: "#8AA86B" },
  };
  sheet.getRange("A1:B1").format.fill = "#FFFFFF";
  sheet.getRange("A1:AL1").format.rowHeight = 22;
  sheet.getRange("A2:AL2").format.rowHeight = 38;

  if (data.length > 0) {
    const body = sheet.getRange(`A3:AL${lastDataRow}`);
    body.format = {
      font: { typeface: "Calibri", fontSize: 10, color: "#1F1F1F" },
      verticalAlignment: "center",
      borders: {
        insideHorizontal: { style: "thin", color: "#E2E8D9" },
      },
    };
    sheet.getRange(`A3:A${lastDataRow}`).format.horizontalAlignment = "center";
    sheet.getRange(`C3:F${lastDataRow}`).format.horizontalAlignment = "right";
    sheet.getRange(`H3:AL${lastDataRow}`).format.horizontalAlignment = "right";
    sheet.getRange(`A3:AL${lastDataRow}`).format.rowHeight = 19;
    sheet.getRange(`A3:A${lastDataRow}`).format.numberFormat = "0";
    sheet.getRange(`C3:C${lastDataRow}`).format.numberFormat = "#,##0";
    sheet.getRange(`D3:E${lastDataRow}`).format.numberFormat = "0.0";
    sheet.getRange(`F3:F${lastDataRow}`).format.numberFormat = "#,##0";
    sheet.getRange(`G3:G${lastDataRow}`).format.numberFormat = "0.00%";
    sheet.getRange(`H3:H${lastDataRow}`).format.numberFormat = "#,##0.00";
    sheet.getRange(`I3:M${lastDataRow}`).format.numberFormat = "$#,##0.0000";
    sheet.getRange(`N3:R${lastDataRow}`).format.numberFormat = "#,##0";
    sheet.getRange(`S3:T${lastDataRow}`).format.numberFormat = "#,##0.00";
    sheet.getRange(`U3:Z${lastDataRow}`).format.numberFormat = "0.00%";
    sheet.getRange(`AA3:AA${lastDataRow}`).format.numberFormat = "#,##0";
    sheet.getRange(`AB3:AB${lastDataRow}`).format.numberFormat = "#,##0.00";
    sheet.getRange(`AC3:AD${lastDataRow}`).format.numberFormat = "#,##0";
    sheet.getRange(`AE3:AF${lastDataRow}`).format.numberFormat = "#,##0.00";
    sheet.getRange(`AG3:AL${lastDataRow}`).format.numberFormat = "#,##0.00";
  }

  // Emphasize major sections without boxing every data cell.
  for (const column of ["C", "F", "H", "N", "AA", "AG"]) {
    sheet.getRange(`${column}1:${column}${Math.max(lastDataRow, 2)}`).format.borders = {
      left: { style: "medium", color: "#6B7D5C" },
    };
  }
  sheet.getRange(`AL1:AL${Math.max(lastDataRow, 2)}`).format.borders = {
    right: { style: "medium", color: "#6B7D5C" },
  };

  setWidth(sheet, "A:A", 12);
  setWidth(sheet, "B:B", 3);
  setWidth(sheet, "C:C", 11);
  setWidth(sheet, "D:D", 12);
  setWidth(sheet, "E:E", 8);
  setWidth(sheet, "F:G", 10);
  setWidth(sheet, "H:H", 12);
  setWidth(sheet, "I:M", 14);
  setWidth(sheet, "N:O", 12);
  setWidth(sheet, "P:P", 19);
  setWidth(sheet, "Q:Q", 12);
  setWidth(sheet, "R:R", 18);
  setWidth(sheet, "S:S", 14);
  setWidth(sheet, "T:T", 18);
  setWidth(sheet, "U:Z", 14);
  setWidth(sheet, "AA:AA", 10);
  setWidth(sheet, "AB:AB", 16);
  setWidth(sheet, "AC:AC", 12);
  setWidth(sheet, "AD:AD", 18);
  setWidth(sheet, "AE:AE", 14);
  setWidth(sheet, "AF:AF", 18);
  setWidth(sheet, "AG:AI", 12);
  setWidth(sheet, "AJ:AL", 17);
}

function rowValues(item) {
  const source = item.source;
  if (!source) {
    return [item.experiment, null, item.expected, item.scenario.arrival,
      item.scenario.alpha, ...Array(33).fill(null)];
  }
  const reservedTotalCores = number(source.reservedTotalCores);
  const reservedCoreSeconds = number(source.reservedCoreSeconds);
  const duration = reservedTotalCores > 0 && reservedCoreSeconds > 0
    ? reservedCoreSeconds / reservedTotalCores
    : number(source.simulationDuration);
  const reservedCost = Math.max(0, number(source.totalCost)
    - number(source.onDemandCost) - number(source.spotCost));
  return [
    item.experiment, null, number(source.total), item.scenario.arrival,
    item.scenario.alpha, number(source.metDeadline), null,
    duration, reservedCost,
    number(source.onDemandCost), number(source.spotCost), null,
    optionalNumber(source.marginalCost),
    number(source.reservedInstanceCount), number(source.reservedCoresPerInstance),
    number(source.reservedRamMbPerInstance), null, null, null, null,
    number(source.reservedMeanCoreUtil), number(source.reservedMinCoreUtil),
    number(source.reservedMaxCoreUtil), number(source.reservedMeanRamUtil),
    number(source.reservedMinRamUtil), number(source.reservedMaxRamUtil),
    number(source.provisionedOnDemandVms), number(source.onDemandAverageUptime),
    number(source.onDemandTotalCores), number(source.onDemandTotalRamMb),
    number(source.onDemandCoreSeconds), number(source.onDemandRamMbSeconds),
    number(source.meanUtilizedCores), number(source.minUtilizedCores),
    number(source.maxUtilizedCores), number(source.meanUtilizedRamMb),
    number(source.minUtilizedRamMb), number(source.maxUtilizedRamMb),
  ];
}

function setWidth(sheet, range, width) {
  sheet.getRange(range).format.columnWidth = width;
}

function number(value) {
  if (value === null || value === undefined || value === "") return 0;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function optionalNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

async function readCsvIfPresent(filePath) {
  try {
    return parseCsv(await fs.readFile(filePath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") {
      console.warn(`[workbook] Missing ${filePath}; creating a blank sheet template`);
      return [];
    }
    throw error;
  }
}

function parseCsv(text) {
  const records = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n") {
      row.push(field.replace(/\r$/, ""));
      if (row.some((value) => value !== "")) records.push(row);
      row = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field !== "" || row.length > 0) {
    row.push(field.replace(/\r$/, ""));
    records.push(row);
  }
  if (records.length === 0) return [];
  const headers = records[0];
  return records.slice(1).map((values) => Object.fromEntries(
    headers.map((header, index) => [header, values[index] ?? ""])));
}
