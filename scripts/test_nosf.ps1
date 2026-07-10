$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }

    & java -ea -cp "bin;lib/*" org.workflowsim.cbmw.baselines.NOSFValidationTest
    if ($LASTEXITCODE -ne 0) { throw "NOSF invariant tests failed with exit code $LASTEXITCODE" }

    $output = 'Output/smoke_tests/NOSF'
    $outputPath = Join-Path $projectRoot $output
    $resolvedRoot = (Resolve-Path -LiteralPath $projectRoot).Path
    $resolvedRootWithSeparator = $resolvedRoot.TrimEnd('\') + '\'
    $resolvedOutput = [System.IO.Path]::GetFullPath($outputPath)
    if (-not $resolvedOutput.StartsWith($resolvedRootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean smoke output outside project root: $outputPath"
    }
    if (Test-Path -LiteralPath $outputPath) {
        Remove-Item -LiteralPath $outputPath -Recurse -Force
    }
    & java `
        '-Dcbmw.algorithms=NOSF' `
        '-Dcbmw.max.workflows=2' `
        '-Dcbmw.max.scenarios=1' `
        "-Dcbmw.output.dir=$output" `
        '-Dcbmw.export.details=false' `
        '-Dcbmw.detail.log=true' `
        '-Dcbmw.quiet=true' `
        '-Dcbmw.generate.gantt=false' `
        '-Dcbmw.generate.comparison=false' `
        -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
    if ($LASTEXITCODE -ne 0) { throw "NOSF smoke failed with exit code $LASTEXITCODE" }

    $row = @(Import-Csv (Join-Path $output 'algorithms/NOSF/results.csv'))[0]
    $tasks = @(Import-Csv (Join-Path $output 'algorithms/NOSF/task_execution.csv'))
    if ([double]$row.reservedUtil -ne 0.0) { throw 'NOSF used or accounted reserved capacity' }
    if ([int]$row.provisionedOnDemandVms -ge $tasks.Count) {
        throw 'NOSF did not reuse on-demand VMs'
    }
    if ([double]$row.onDemandVmUtilization -le 0.0) {
        throw 'NOSF on-demand VM utilization was not reported'
    }
    $nonOnDemand = @($tasks | Where-Object {
        $_.'Workflow Disposition' -eq 'ACCEPTED' -and $_.'Actual VM Type' -ne 'On-Demand'
    })
    if ($nonOnDemand.Count -ne 0) { throw 'NOSF executed a task outside on-demand VMs' }
    $detailLog = Get-ChildItem -Path $output -Recurse -Filter '*_detail.log' | Select-Object -First 1
    $logText = Get-Content -Raw $detailLog.FullName
    if ($logText -notmatch 'reused=yes') { throw 'NOSF smoke did not demonstrate VM reuse' }
    Write-Host 'NOSF build, invariants, smoke, reuse, accounting, and diagnostics: PASS'
} finally {
    Pop-Location
}
