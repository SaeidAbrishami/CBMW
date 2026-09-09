// Run with the bundled Node runtime; CBMW_NODE_MODULES points to its packages.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(path.join(process.env.CBMW_NODE_MODULES, '..', 'package.json'));
const { FileBlob, SpreadsheetFile, Workbook } = await import(
  pathToFileURL(require.resolve('@oai/artifact-tool')).href);
const [template, csvPath, output, mode = 'fill'] = process.argv.slice(2);
if (!template || !csvPath || !output) throw new Error('Usage: template.xlsx results.csv output.xlsx [inspect]');
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(template));
const sheet = workbook.worksheets.getItemAt(0);
const outputDir = path.dirname(output);
await fs.mkdir(outputDir, { recursive: true });
if (mode === 'inspect') {
  console.log((await workbook.inspect({ kind: 'table', range: `${sheet.name}!A1:AK5`, tableMaxCols: 37, tableMaxRows: 5, maxChars: 5000 })).ndjson);
  for (const [name, range] of [['left', 'A1:L10'], ['right', 'M1:AK10']]) {
    const preview = await workbook.render({ sheetName: sheet.name, range, scale: 1.5 });
    await fs.writeFile(path.join(outputDir, `template_${name}.png`), new Uint8Array(await preview.arrayBuffer()));
  }
  process.exit(0);
}

const csv = await Workbook.fromCSV(await fs.readFile(csvPath, 'utf8'), { sheetName: 'Data' });
const rows = csv.worksheets.getItemAt(0).getUsedRange().values;
const headers = rows.shift();
const byKey = new Map();
const runKeys = new Set();
const number = (r, field) => {
  if (r[field] === '' || r[field] == null || !Number.isFinite(Number(r[field]))) throw new Error(`Missing/invalid ${field}`);
  return Number(r[field]);
};
for (const values of rows) {
  if (values.every(v => v == null || v === '')) continue;
  const r = Object.fromEntries(headers.map((h, i) => [h, values[i]]));
  runKeys.add(`${r.run}|${r.runSeed}|${r.nosfProfile}`);
  if (r.algorithm !== 'CBMW') throw new Error('This template exporter expects CBMW only');
  const key = `${number(r, 'total')}|${String(r.load).replace(/^arrival/, '')}|${number(r, 'tightness')}`;
  if (byKey.has(key)) throw new Error(`Duplicate scenario ${key}; select one repetition`);
  if (number(r, 'arrivalScale') !== 1) throw new Error(`Rescaled arrivals in ${key}`);
  const expectedMode = number(r, 'total') === 500 ? 'full500' : 'edge200';
  if (!String(r.scenario).includes(expectedMode)) throw new Error(`Dataset/count mismatch ${key}`);
  byKey.set(key, r);
}
if (runKeys.size !== 1) throw new Error('Mixed run numbers, seeds, or profiles');
if (mode !== 'preview' && byKey.size !== 24) throw new Error(`Expected 24 completed scenarios, found ${byKey.size}`);

const fields = {
  F: 'metDeadline', H: 'simulationDuration', I: 'reservedCost', J: 'onDemandCost',
  M: 'reservedInstanceCount', N: 'reservedCoresPerInstance', O: 'reservedRamMbPerInstance',
  P: 'reservedTotalCores', Q: 'reservedTotalRamMb', R: 'reservedCoreSeconds', S: 'reservedRamMbSeconds',
  T: 'reservedMeanCoreUtil', U: 'reservedMinCoreUtil', V: 'reservedMaxCoreUtil',
  W: 'reservedMeanRamUtil', X: 'reservedMinRamUtil', Y: 'reservedMaxRamUtil',
  Z: 'provisionedOnDemandVms', AA: 'onDemandAverageUptime', AB: 'onDemandTotalCores',
  AC: 'onDemandTotalRamMb', AD: 'onDemandCoreSeconds', AE: 'onDemandRamMbSeconds',
  AF: 'meanUtilizedCores', AG: 'minUtilizedCores', AH: 'maxUtilizedCores',
  AI: 'meanUtilizedRamMb', AJ: 'minUtilizedRamMb', AK: 'maxUtilizedRamMb',
};
const selected = new Set();
for (let row = 3; row <= 26; row++) {
  const [count, mean, alpha] = sheet.getRange(`C${row}:E${row}`).values[0].map(Number);
  const key = `${count}|${mean}|${alpha}`;
  const r = byKey.get(key);
  if (!r && mode === 'preview') continue;
  if (!r || selected.has(key)) throw new Error(`Missing/duplicate template scenario ${key}`);
  selected.add(key);
  if (number(r, 'accepted') + number(r, 'rejected') !== count || number(r, 'metDeadline') > number(r, 'accepted')) throw new Error(`Invalid success count ${key}`);
  if (Math.abs(number(r, 'totalCost') - number(r, 'reservedCost') - number(r, 'onDemandCost')) > 0.02) throw new Error(`Cost mismatch ${key}`);
  sheet.getRange(`A${row}`).values = [[row - 2]];
  for (const [col, field] of Object.entries(fields)) sheet.getRange(`${col}${row}`).values = [[number(r, field)]];
  sheet.getRange(`G${row}`).formulas = [[`=F${row}/C${row}`]];
  sheet.getRange(`K${row}`).formulas = [[`=I${row}+J${row}`]];
  if (count === 500) {
    const next = sheet.getRange(`C${row + 1}:E${row + 1}`).values[0].map(Number);
    if (next[0] !== 200 || next[1] !== mean || next[2] !== alpha) throw new Error('Unpaired template rows');
    const edge = byKey.get(`200|${mean}|${alpha}`);
    if (!edge && mode === 'preview') { sheet.getRange(`L${row}`).values = [[null]]; continue; }
    if (Math.abs(number(r, 'marginalCost') - (number(r, 'totalCost') - number(edge, 'totalCost'))) > 0.02) throw new Error('Marginal cost mismatch');
    sheet.getRange(`L${row}`).formulas = [[`=K${row}-K${row + 1}`]];
  } else sheet.getRange(`L${row}`).values = [[null]];
}
sheet.getRange('G3:G26').setNumberFormat('0.0%');
sheet.getRange('T3:Y26').setNumberFormat('0.00%');
sheet.getRange('H3:H26').setNumberFormat('#,##0.00');
sheet.getRange('I3:L26').setNumberFormat('#,##0.00');
sheet.getRange('R3:S26').setNumberFormat('#,##0');
// Capacity-time values exceed the empty template column's width.
sheet.getRange('S:S').format.columnWidth = 24;
sheet.getRange('AA3:AA26').setNumberFormat('#,##0.00');
sheet.getRange('AD3:AE26').setNumberFormat('#,##0');
sheet.getRange('AF3:AK26').setNumberFormat('#,##0.00');
console.log((await workbook.inspect({ kind: 'match', searchTerm: '#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!', options: { useRegex: true, maxResults: 20 } })).ndjson);
for (const [name, range] of [['left', 'A1:L26'], ['reserved', 'M1:Y26'], ['resources', 'Z1:AK26']]) {
  const preview = await workbook.render({ sheetName: sheet.name, range, scale: 1.5 });
  await fs.writeFile(path.join(outputDir, `results_${name}.png`), new Uint8Array(await preview.arrayBuffer()));
}
if (mode === 'preview') { console.log(`Previewed ${selected.size} actual scenarios; no XLSX exported`); process.exit(0); }
await (await SpreadsheetFile.exportXlsx(workbook)).save(output);
console.log(`Filled and validated 24 CBMW scenarios: ${output}`);
