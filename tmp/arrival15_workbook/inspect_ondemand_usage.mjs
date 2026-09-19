import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const path = "P:/University/CBMW_Workflow_Simulation/Output/arrival15_cbmw_20260918_retry1/algorithms/CBMW/arrival15_alpha2_full500_CBMW_t2.0_r0_details/ON_DEMAND_INSTANCE_USAGE.xlsx";
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(path));
console.log((await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 12000,
  tableMaxRows: 8,
  tableMaxCols: 16,
  tableMaxCellChars: 80,
})).ndjson);
