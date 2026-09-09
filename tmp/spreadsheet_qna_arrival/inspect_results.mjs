import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath =
  "P:/University/CBMW_Workflow_Simulation/outputs/019fd728-bbc4-7e50-ab4a-78f25bc7008a/results.xlsx";
const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const summary = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 12000,
  tableMaxRows: 12,
  tableMaxCols: 20,
  tableMaxCellChars: 120,
});
console.log("SUMMARY");
console.log(summary.ndjson);

const matches = await workbook.inspect({
  kind: "match",
  searchTerm: "arrival",
  options: { useRegex: false, maxResults: 200 },
  maxChars: 12000,
});
console.log("ARRIVAL_MATCHES");
console.log(matches.ndjson);
