$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-ReferenceCewbSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$Algorithm,
        [Parameter(Mandatory = $true)][string]$ExpectedMode,
        [Parameter(Mandatory = $true)][string]$Output
    )

    $outputPath = Join-Path $projectRoot $Output
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
        "-Dcbmw.algorithms=$Algorithm" `
        '-Dcbmw.max.workflows=2' `
        '-Dcbmw.max.scenarios=1' `
        "-Dcbmw.output.dir=$Output" `
        '-Dcbmw.export.details=false' `
        '-Dcbmw.detail.log=true' `
        '-Dcbmw.quiet=true' `
        '-Dcbmw.generate.gantt=false' `
        '-Dcbmw.generate.comparison=false' `
        -cp "bin;lib/*" `
        org.workflowsim.examples.cbmw.CBMWSimulation
    if ($LASTEXITCODE -ne 0) {
        throw "$Algorithm smoke simulation failed with exit code $LASTEXITCODE"
    }

    $resultPath = Join-Path $outputPath "algorithms/$Algorithm/results.csv"
    $results = @(Import-Csv $resultPath)
    if ($results.Count -ne 1 -or [int]$results[0].total -ne 2) {
        throw "$Algorithm smoke output did not contain one two-workflow scenario"
    }
    $detailLog = Get-ChildItem -Path $outputPath -Recurse -Filter '*_detail.log' |
        Select-Object -First 1
    if ($null -eq $detailLog) {
        throw "$Algorithm smoke detail log was not created"
    }
    $logText = Get-Content -Raw $detailLog.FullName
    if ($logText -notmatch "mode=$ExpectedMode") {
        throw "$Algorithm detail log did not report mode=$ExpectedMode"
    }
    if ($logText -notmatch 'taskPolicy=reference-pcp-absolute-slack') {
        throw "$Algorithm did not use the reference PCP/slack policy"
    }
    if ($ExpectedMode -eq 'REFERENCE_ADAPTED' -and
            $logText -notmatch 'progressRetained=true') {
        throw 'Reference-adapted smoke did not exercise partial-progress recovery'
    }
}

Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }

    & java -ea -cp "bin;lib/*" `
        org.workflowsim.cbmw.baselines.CEWBValidationTest
    if ($LASTEXITCODE -ne 0) {
        throw "CEWB invariant tests failed with exit code $LASTEXITCODE"
    }

    $output = 'Output/smoke_tests/CEWB'
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
        '-Dcbmw.algorithms=CEWB' `
        '-Dcbmw.max.workflows=2' `
        '-Dcbmw.max.scenarios=1' `
        "-Dcbmw.output.dir=$output" `
        '-Dcbmw.export.details=false' `
        '-Dcbmw.detail.log=true' `
        '-Dcbmw.quiet=true' `
        '-Dcbmw.generate.gantt=false' `
        '-Dcbmw.generate.comparison=false' `
        -cp "bin;lib/*" `
        org.workflowsim.examples.cbmw.CBMWSimulation
    if ($LASTEXITCODE -ne 0) {
        throw "CEWB smoke simulation failed with exit code $LASTEXITCODE"
    }

    $results = @(Import-Csv (Join-Path $output 'algorithms/CEWB/results.csv'))
    if ($results.Count -ne 1 -or [int]$results[0].total -ne 2) {
        throw 'CEWB smoke output did not contain one two-workflow scenario'
    }
    $taskCsv = Join-Path $output 'algorithms/CEWB/task_execution.csv'
    $taskFile = Get-Item $taskCsv
    if ($taskFile.Length -le 0) {
        throw 'CEWB smoke task_execution.csv is empty'
    }
    $tasks = @(Import-Csv $taskCsv)
    $badPlans = @($tasks | Where-Object {
        $_.'Workflow Disposition' -eq 'ACCEPTED' -and
        ($_.'Planned VM Type' -ne 'Spot Candidate' -or
         -not [string]::IsNullOrWhiteSpace($_.'Planned VM ID'))
    })
    if ($badPlans.Count -ne 0) {
        throw 'CEWB tasks must be exported as Spot Candidate with no fake planned VM ID'
    }
    $badSpotRows = @($tasks | Where-Object {
        $_.'Actual VM Type' -eq 'Spot' -and
        ($_.'Rescheduled' -ne 'NO' -or
         $_.'Scheduling Reason' -ne 'CEWB_SPOT_SELECTION')
    })
    if ($badSpotRows.Count -ne 0) {
        throw 'Intended CEWB spot selections must not be classified as rescheduling'
    }
    $badFallbackRows = @($tasks | Where-Object {
        $_.'Actual VM Type' -eq 'On-Demand' -and
        ($_.'Rescheduled' -ne 'YES' -or
         $_.'Scheduling Reason' -ne 'CEWB_ON_DEMAND_FALLBACK')
    })
    if ($badFallbackRows.Count -ne 0) {
        throw 'CEWB on-demand fallbacks must be classified explicitly'
    }
    $detailLog = Get-ChildItem -Path $output -Recurse -Filter '*_detail.log' |
        Select-Object -First 1
    if ($null -eq $detailLog) {
        throw 'CEWB smoke detail log was not created'
    }
    $logText = Get-Content -Raw $detailLog.FullName
    foreach ($tag in 'CEWB-CONFIG', 'CEWB-SUMMARY') {
        if ($logText -notmatch [regex]::Escape($tag)) {
            throw "CEWB detail log is missing $tag"
        }
    }
    Invoke-ReferenceCewbSmoke `
        -Algorithm 'CEWB-ReferencePolicy' `
        -ExpectedMode 'REFERENCE_POLICY' `
        -Output 'Output/smoke_tests/CEWB-ReferencePolicy'
    Invoke-ReferenceCewbSmoke `
        -Algorithm 'CEWB-ReferenceAdapted' `
        -ExpectedMode 'REFERENCE_ADAPTED' `
        -Output 'Output/smoke_tests/CEWB-ReferenceAdapted'
    Write-Host 'CEWB current/common-market reference modes, outputs, recovery, and diagnostics: PASS'
} finally {
    Pop-Location
}
