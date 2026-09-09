[CmdletBinding()]
param(
    [string] $Template = "$env:USERPROFILE/OneDrive/Desktop/results.xlsx",
    [string] $OutputRoot = "Output/results_24_$(Get-Date -Format yyyyMMdd_HHmmss)",
    [string] $WorkbookPath = "outputs/results_24_$(Get-Date -Format yyyyMMdd_HHmmss)/results.xlsx",
    [string] $RuntimeRoot = "$env:USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies",
    [ValidateRange(1, 8)][int] $Workers = 4,
    [switch] $SkipSimulation
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath $Template)) { throw "Missing template: $Template" }
    $node = Join-Path $RuntimeRoot 'node/bin/node.exe'
    if (-not (Test-Path -LiteralPath $node)) { throw "Bundled Node not found: $node" }
    $env:CBMW_NODE_MODULES = Join-Path $RuntimeRoot 'node/node_modules'
    if (-not $SkipSimulation) {
        if (Test-Path -LiteralPath $OutputRoot) { throw "Use a fresh OutputRoot to preserve earlier runs: $OutputRoot" }
        & "$PSScriptRoot/build.ps1"
        New-Item -ItemType Directory -Path $OutputRoot | Out-Null
        $manifests = Get-ChildItem -LiteralPath 'test_workflows/workflows' -Filter 'dax_poisson_arrivals_*.json' |
            ForEach-Object { [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash } }
        [ordered]@{
            algorithm = 'CBMW'; repetitions = 1; runtimeResample = $false
            workflowDirectory = 'test_workflows/workflows'
            meanInterArrivalSeconds = @(15, 30, 45, 60); tightness = @(1.2, 2, 4)
            datasetModes = @('FULL_500', 'EDGE_200'); manifests = @($manifests)
        } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputRoot 'run_config.json') -Encoding utf8
        & "$PSScriptRoot/run_results_scenarios.ps1" -OutputRoot $OutputRoot -Workers $Workers

    }
    $csv = Join-Path $OutputRoot 'algorithms/CBMW/results.csv'
    & $node "$PSScriptRoot/fill_results_workbook.mjs" $Template $csv $WorkbookPath
    if ($LASTEXITCODE -ne 0) { throw 'Workbook export failed' }
    Write-Host "Filled workbook: $WorkbookPath"
} finally { Pop-Location }
