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
    if ($logText -notmatch 'estimator=MU_PLUS_SIGMA') { throw 'NOSF did not log the paper runtime estimator' }
    if ($logText -notmatch 'priority=EST') { throw 'NOSF did not use the documented paper priority policy' }
    if ($logText -notmatch 'provisioningDelay=60\.0') { throw 'NOSF did not inherit the default CBMW provisioning delay' }

    $paperOutput = 'Output/smoke_tests/NOSF_paper_aligned'
    $paperOutputPath = Join-Path $projectRoot $paperOutput
    $resolvedPaperOutput = [System.IO.Path]::GetFullPath($paperOutputPath)
    if (-not $resolvedPaperOutput.StartsWith($resolvedRootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean paper smoke output outside project root: $paperOutputPath"
    }
    if (Test-Path -LiteralPath $paperOutputPath) {
        Remove-Item -LiteralPath $paperOutputPath -Recurse -Force
    }
    & java `
        '-Dcbmw.algorithms=NOSF' `
        '-Dnosf.profile=PAPER_ALIGNED' `
        '-Dcbmw.max.workflows=2' `
        '-Dcbmw.max.scenarios=2' `
        "-Dcbmw.output.dir=$paperOutput" `
        '-Dcbmw.export.details=false' `
        '-Dcbmw.detail.log=true' `
        '-Dcbmw.quiet=true' `
        '-Dcbmw.generate.gantt=false' `
        '-Dcbmw.generate.comparison=false' `
        -cp "bin;lib/*" org.workflowsim.examples.cbmw.CBMWSimulation
    if ($LASTEXITCODE -ne 0) { throw "NOSF paper smoke failed with exit code $LASTEXITCODE" }

    $paperResults = @(Import-Csv (Join-Path $paperOutput 'algorithms/NOSF/results.csv'))
    if ($paperResults.Count -ne 2) { throw 'Paper smoke did not emit two replicate rows' }
    if (@($paperResults.run | Sort-Object -Unique) -join ',' -ne '0,1') {
        throw 'Paper smoke run indices are not 0 and 1'
    }
    if (@($paperResults.runSeed | Sort-Object -Unique).Count -ne 2) {
        throw 'Paper smoke replicate seeds are not independent'
    }
    if (@($paperResults.nosfProfile | Sort-Object -Unique) -ne 'PAPER_ALIGNED') {
        throw 'Paper smoke did not record the paper-aligned profile'
    }
    $paperAggregate = @(Import-Csv (Join-Path $paperOutput 'algorithms/NOSF/results_aggregate.csv'))[0]
    if ([int]$paperAggregate.runs -ne 2) { throw 'Paper aggregate did not combine two runs' }

    $paperTasks = @(Import-Csv (Join-Path $paperOutput 'algorithms/NOSF/task_execution.csv'))
    $paperVmNames = @('m2.4xlarge', 'm2.2xlarge', 'm1.xlarge', 'm2.xlarge',
        'm1.large', 'm1.medium', 'm1.small')
    $invalidVmNames = @($paperTasks | Where-Object {
        $_.'Actual VM Name' -and $_.'Actual VM Name' -notin $paperVmNames
    })
    if ($invalidVmNames.Count -ne 0) { throw 'Paper smoke used a VM outside Table 2' }
    $sampleRows = @($paperTasks | Where-Object { $_.'Task ID' -eq 'ID00001' })
    if ($sampleRows.Count -ne 2 -or
            $sampleRows[0].'Actual Runtime Sample (s)' -eq $sampleRows[1].'Actual Runtime Sample (s)') {
        throw 'Paper smoke did not create distinct run-specific runtime samples'
    }
    $paperLog = Get-ChildItem -Path $paperOutput -Recurse -Filter '*_detail.log' |
        Select-Object -First 1
    $paperLogText = Get-Content -Raw $paperLog.FullName
    if ($paperLogText -notmatch 'profile=PAPER_ALIGNED types=7') {
        throw 'Paper smoke did not activate the seven paper VM rankings'
    }
    if ($paperLogText -notmatch 'billingQuantum=3600\.0') {
        throw 'Paper smoke did not activate hourly billing'
    }
    if ($paperLogText -notmatch 'transferMode=PAPER_NETWORK') {
        throw 'Paper smoke did not activate the 100 Mbps paper network'
    }
    Write-Host 'NOSF equations, profiles, PCP, feedback, VM rankings, billing, transfer, repetitions, reuse, accounting, and diagnostics: PASS'
} finally {
    Pop-Location
}
