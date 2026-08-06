import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "C:/Users/AsiaLapTop.Com/Downloads/Telegram Desktop/results.xlsx";
const outputDir = "P:/University/CBMW_Workflow_Simulation/.codex/tmp/results_workbook_inspection/rendered";

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const summary = await workbook.inspect({
  kind: "workbook,sheet,table,drawing,definedName",
  maxChars: 12000,
  tableMaxRows: 8,
  tableMaxCols: 16,
  tableMaxCellChars: 120,
});
console.log("=== WORKBOOK SUMMARY ===");
console.log(summary.ndjson);

await fs.mkdir(outputDir, { recursive: true });
for (const sheet of workbook.worksheets.items) {
  const used = sheet.getUsedRange();
  console.log(`=== SHEET ${sheet.name} USED RANGE ===`);
  console.log(used ? used.address : "(empty)");
  if (used) {
    const region = await workbook.inspect({
      kind: "region",
      sheetId: sheet.name,
      range: used.address.split("!").pop(),
      include: "values,formulas",
      maxChars: 30000,
      tableMaxRows: 80,
      tableMaxCols: 40,
      tableMaxCellChars: 160,
    });
    console.log(region.ndjson);

    const styles = await workbook.inspect({
      kind: "computedStyle",
      sheetId: sheet.name,
      range: used.address.split("!").pop(),
      maxChars: 10000,
      options: { maxResults: 100 },
    });
    console.log(`=== SHEET ${sheet.name} STYLES ===`);
    console.log(styles.ndjson);

    const preview = await workbook.render({
      sheetName: sheet.name,
      autoCrop: "all",
      scale: 1.5,
      format: "png",
    });
    const safeName = sheet.name.replace(/[^a-zA-Z0-9_-]+/g, "_");
    await fs.writeFile(`${outputDir}/${safeName}.png`, new Uint8Array(await preview.arrayBuffer()));
  }
}
