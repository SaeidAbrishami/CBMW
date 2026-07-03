$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
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

    $output = 'Output/validation_cewb_noncore_fix'
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
    $tasks = Get-Item (Join-Path $output 'algorithms/CEWB/task_execution.csv')
    if ($tasks.Length -le 0) {
        throw 'CEWB smoke task_execution.csv is empty'
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
    Write-Host 'CEWB build, invariant tests, smoke simulation, outputs, and diagnostics: PASS'
} finally {
    Pop-Location
}
