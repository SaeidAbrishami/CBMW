[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PhaseName,
    [string] $BaselineRoot = 'Output/performance_baseline/phase0_20260809_baseline',
    [int[]] $WorkflowCounts = @(5, 20, 50),
    [string] $OutputRoot = '',
    [switch] $SkipBuild,
    [switch] $SkipValidationTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-ProjectPath([string] $Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Path))
}

function ConvertTo-RelativePath([string] $BasePath, [string] $TargetPath) {
    $baseFull = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\') + '\'
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)
    $baseUri = [System.Uri]::new($baseFull)
    $targetUri = [System.Uri]::new($targetFull)
    return [System.Uri]::UnescapeDataString(
        $baseUri.MakeRelativeUri($targetUri).ToString()).Replace('/', '\')
}

$BaselineRoot = Resolve-ProjectPath $BaselineRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $projectRoot ("Output/performance_optimization/{0}" -f $PhaseName)
} else {
    $OutputRoot = Resolve-ProjectPath $OutputRoot
}

$baselineManifestPath = Join-Path $BaselineRoot 'baseline_manifest.csv'
if (-not (Test-Path -LiteralPath $baselineManifestPath)) {
    throw "Baseline manifest does not exist: $baselineManifestPath"
}
if ((Test-Path -LiteralPath $OutputRoot) -and
        @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) {
    throw "Phase output directory is not empty: $OutputRoot"
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

if (-not $SkipBuild) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (
        Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}

if (-not $SkipValidationTests) {
    $validationClasses = @(
        'org.workflowsim.cbmw.CBMWPreemptionPolicyValidationTest',
        'org.workflowsim.cbmw.CBMWPlannedReservedWaitValidationTest',
        'org.workflowsim.cbmw.CBMWEntryOnDemandDeadlineValidationTest',
        'org.workflowsim.cbmw.ResourceAccountingValidationTest',
        'org.workflowsim.cbmw.RequestedReportFieldsValidationTest',
        'org.workflowsim.cbmw.MarginalCostValidationTest',
        'org.workflowsim.cbmw.WorkflowLoaderValidationTest'
    )
    Push-Location $projectRoot
    try {
        foreach ($class in $validationClasses) {
            Write-Host "[$PhaseName] Validating $class"
            & java -ea -cp 'bin;lib/*' $class
            if ($LASTEXITCODE -ne 0) {
                throw "$class failed with exit code $LASTEXITCODE"
            }
        }
    } finally {
        Pop-Location
    }
}

$baselineRows = Import-Csv -LiteralPath $baselineManifestPath
$comparisonRows = [System.Collections.Generic.List[object]]::new()
$summaryRows = [System.Collections.Generic.List[object]]::new()
$mismatches = [System.Collections.Generic.List[string]]::new()

Push-Location $projectRoot
try {
    foreach ($count in $WorkflowCounts) {
        $runRoot = Join-Path $OutputRoot ("workflows_{0}" -f $count)
        New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
        $metricsFile = Join-Path $runRoot 'performance_metrics.csv'
        $stdoutFile = Join-Path $runRoot 'stdout.log'
        $stderrFile = Join-Path $runRoot 'stderr.log'
        $relativeRunRoot = ConvertTo-RelativePath $projectRoot $runRoot

        $javaArgs = @(
            '-Dcbmw.algorithms=CBMW',
            '-Dcbmw.workflow.dataset.mode=FULL_500',
            "-Dcbmw.max.workflows=$count",
            '-Dcbmw.max.scenarios=1',
            "-Dcbmw.output.dir=$relativeRunRoot",
            '-Dcbmw.export.details=false',
            '-Dcbmw.detail.log=false',
            '-Dcbmw.quiet=true',
            '-Dcbmw.generate.gantt=false',
            '-Dcbmw.generate.comparison=false',
            '-Dcbmw.repetitions=1',
            '-Dcbmw.run.start=0',
            '-Dcbmw.seed.base=20260716',
            '-Dcbmw.runtime.resample=false',
            '-Dcbmw.perf.metrics=true',
            "-Dcbmw.perf.metrics.file=$metricsFile",
            '-cp',
            'bin;lib/*',
            'org.workflowsim.examples.cbmw.CBMWSimulation'
        )

        Write-Host "[$PhaseName] Running CBMW with $count workflows"
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        & java @javaArgs 1> $stdoutFile 2> $stderrFile
        $exitCode = $LASTEXITCODE
        $stopwatch.Stop()
        if ($exitCode -ne 0) {
            throw "CBMW phase run with $count workflows failed (exit $exitCode). See $stderrFile"
        }

        $relativeCoreFiles = @(
            'algorithms\CBMW\results.csv',
            'algorithms\CBMW\results_aggregate.csv',
            'algorithms\CBMW\task_execution.csv'
        )
        foreach ($relativePath in $relativeCoreFiles) {
            $actualPath = Join-Path $runRoot $relativePath
            if (-not (Test-Path -LiteralPath $actualPath)) {
                throw "Expected phase output is missing: $actualPath"
            }
            $baseline = $baselineRows | Where-Object {
                [int]$_.workflowCount -eq $count -and
                $_.relativePath -eq ("workflows_{0}\{1}" -f $count, $relativePath)
            } | Select-Object -First 1
            if ($null -eq $baseline) {
                throw "No baseline hash for workflowCount=$count path=$relativePath"
            }
            $actualItem = Get-Item -LiteralPath $actualPath
            $actualHash = (Get-FileHash -LiteralPath $actualPath -Algorithm SHA256).Hash
            $matches = $actualHash -eq $baseline.sha256 -and
                    $actualItem.Length -eq [long]$baseline.bytes
            $comparisonRows.Add([pscustomobject]@{
                workflowCount = $count
                relativePath = $relativePath
                baselineBytes = [long]$baseline.bytes
                actualBytes = $actualItem.Length
                baselineSha256 = $baseline.sha256
                actualSha256 = $actualHash
                identical = $matches
            })
            if (-not $matches) {
                $mismatches.Add("workflowCount=$count path=$relativePath")
            }
        }

        $metric = Import-Csv -LiteralPath $metricsFile | Select-Object -Last 1
        $summaryRows.Add([pscustomobject]@{
            workflowCount = $count
            wrapperWallSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 6)
            simulationWallSeconds = $metric.wallSeconds
            processCpuSeconds = $metric.processCpuSeconds
            peakHeapBytes = $metric.peakHeapBytes
            cloudletUpdateEvents = $metric.cloudletUpdateEvents
            schedulingPasses = $metric.schedulingPasses
            readyQueueScanCalls = $metric.readyQueueScanCalls
            readyQueueScanItems = $metric.readyQueueScanItems
            preemptionSortCalls = $metric.preemptionSortCalls
            preemptionSortItems = $metric.preemptionSortItems
            dynamicSortCalls = $metric.dynamicSortCalls
            dynamicSortItems = $metric.dynamicSortItems
            maxReadyQueue = $metric.maxReadyQueue
        })
    }
} finally {
    Pop-Location
}

$comparisonRows | Export-Csv -LiteralPath (
    Join-Path $OutputRoot 'baseline_comparison.csv') -NoTypeInformation -Encoding UTF8
$summaryRows | Export-Csv -LiteralPath (
    Join-Path $OutputRoot 'performance_summary.csv') -NoTypeInformation -Encoding UTF8

if ($mismatches.Count -gt 0) {
    throw ("Phase outputs differ from Phase 0: " + ($mismatches -join '; '))
}

Write-Host "[$PhaseName] PASS: all core CSV files are byte-identical to Phase 0"
Write-Host "[$PhaseName] Output root: $OutputRoot"
