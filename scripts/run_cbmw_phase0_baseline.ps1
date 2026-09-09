[CmdletBinding()]
param(
    [int[]] $WorkflowCounts = @(5, 20, 50),
    [string] $OutputRoot = '',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot

function ConvertTo-RelativePath([string] $BasePath, [string] $TargetPath) {
    $baseFull = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\') + '\'
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)
    $baseUri = [System.Uri]::new($baseFull)
    $targetUri = [System.Uri]::new($targetFull)
    return [System.Uri]::UnescapeDataString(
        $baseUri.MakeRelativeUri($targetUri).ToString()).Replace('/', '\')
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $OutputRoot = Join-Path $projectRoot "Output/performance_baseline/phase0_$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputRoot)) {
    $OutputRoot = Join-Path $projectRoot $OutputRoot
}

if ($WorkflowCounts.Count -eq 0 -or @($WorkflowCounts | Where-Object { $_ -le 0 }).Count -gt 0) {
    throw 'WorkflowCounts must contain positive integers.'
}

if ((Test-Path -LiteralPath $OutputRoot) -and
        @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) {
    throw "Baseline output directory is not empty: $OutputRoot"
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

if (-not $SkipBuild) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (
        Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}

$manifestRows = [System.Collections.Generic.List[object]]::new()
$summaryRows = [System.Collections.Generic.List[object]]::new()

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

        Write-Host "[phase0] Running CBMW with $count workflows..."
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        & java @javaArgs 1> $stdoutFile 2> $stderrFile
        $exitCode = $LASTEXITCODE
        $stopwatch.Stop()
        if ($exitCode -ne 0) {
            throw "CBMW baseline with $count workflows failed (exit $exitCode). See $stderrFile"
        }

        $coreFiles = @(
            (Join-Path $runRoot 'algorithms/CBMW/results.csv'),
            (Join-Path $runRoot 'algorithms/CBMW/results_aggregate.csv'),
            (Join-Path $runRoot 'algorithms/CBMW/task_execution.csv')
        )
        foreach ($file in $coreFiles) {
            if (-not (Test-Path -LiteralPath $file)) {
                throw "Expected baseline output is missing: $file"
            }
            $item = Get-Item -LiteralPath $file
            $manifestRows.Add([pscustomobject]@{
                workflowCount = $count
                relativePath = ConvertTo-RelativePath $OutputRoot $item.FullName
                bytes = $item.Length
                sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
            })
        }

        $metric = Import-Csv -LiteralPath $metricsFile | Select-Object -Last 1
        $summaryRows.Add([pscustomobject]@{
            workflowCount = $count
            wrapperWallSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 6)
            simulationWallSeconds = $metric.wallSeconds
            processCpuSeconds = $metric.processCpuSeconds
            peakHeapBytes = $metric.peakHeapBytes
            cloudletUpdateEvents = $metric.cloudletUpdateEvents
            blockedBeforeVmAck = $metric.blockedBeforeVmAck
            periodicDeferrals = $metric.periodicDeferrals
            periodicWakeSchedules = $metric.periodicWakeSchedules
            schedulingPasses = $metric.schedulingPasses
            readyQueueScanCalls = $metric.readyQueueScanCalls
            readyQueueScanItems = $metric.readyQueueScanItems
            preemptionSortCalls = $metric.preemptionSortCalls
            preemptionSortItems = $metric.preemptionSortItems
            dynamicSortCalls = $metric.dynamicSortCalls
            dynamicSortItems = $metric.dynamicSortItems
            maxReadyQueue = $metric.maxReadyQueue
        })
        Write-Host ("[phase0] Completed {0} workflows in {1:N2}s" -f
            $count, $stopwatch.Elapsed.TotalSeconds)
    }
} finally {
    Pop-Location
}

$manifestPath = Join-Path $OutputRoot 'baseline_manifest.csv'
$summaryPath = Join-Path $OutputRoot 'phase0_summary.csv'
$manifestRows | Export-Csv -LiteralPath $manifestPath -NoTypeInformation -Encoding UTF8
$summaryRows | Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding UTF8

Write-Host "[phase0] Baseline root: $OutputRoot"
Write-Host "[phase0] Hash manifest: $manifestPath"
Write-Host "[phase0] Performance summary: $summaryPath"
