[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $OutputRoot,
    [ValidateRange(1, 8)][int] $Workers = 4,
    [ValidateSet(15,30,45,60)][int[]] $ArrivalRates = @(15,30,45,60)
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$root = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputRoot))
New-Item -ItemType Directory -Force -Path $root | Out-Null
$queue = [System.Collections.Generic.Queue[string]]::new()
foreach ($mean in $ArrivalRates) {
    foreach ($alpha in @('1.2', '2', '4')) {
        foreach ($mode in @('full500', 'edge200')) {
            $queue.Enqueue("arrival${mean}_alpha${alpha}_${mode}")
        }
    }
}
$expectedCount = $queue.Count
$java = (Get-Command java -ErrorAction Stop).Source
$running = [System.Collections.Generic.List[object]]::new()
$completed = [System.Collections.Generic.List[string]]::new()
try {
    while ($queue.Count -gt 0 -or $running.Count -gt 0) {
        while ($queue.Count -gt 0 -and $running.Count -lt $Workers) {
            $scenario = $queue.Dequeue()
            $dir = Join-Path $root $scenario
            $csv = Join-Path $dir 'algorithms/CBMW/results.csv'
            $done = Join-Path $dir 'completed.json'
            if (Test-Path -LiteralPath $done) {
                $rows = @(Import-Csv -LiteralPath $csv)
                if ($rows.Count -ne 1 -or $rows[0].scenario -ne $scenario) { throw "Invalid saved scenario: $scenario" }
                $completed.Add($scenario)
                continue
            }
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            $args = @('-Xmx3g', '-Dcbmw.algorithms=CBMW', "-Dcbmw.scenarios=$scenario",
                "`"-Dcbmw.output.dir=$dir`"", '-Dcbmw.export.details=false', '-Dcbmw.detail.log=false',
                '-Dcbmw.quiet=true', '-Dcbmw.generate.comparison=false', '-Dcbmw.repetitions=1',
                '-Dcbmw.runtime.resample=false', '-cp', '"bin;lib/*"',
                'org.workflowsim.examples.cbmw.CBMWSimulation')
            $process = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $projectRoot `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dir 'run.log') `
                -RedirectStandardError (Join-Path $dir 'error.log')
            $running.Add([pscustomobject]@{ Process = $process; Scenario = $scenario; Csv = $csv; Done = $done })
            Write-Host "Started $scenario (PID $($process.Id))"
        }
        foreach ($job in @($running.ToArray())) {
            if (-not $job.Process.HasExited) { continue }
            $job.Process.WaitForExit()
            if ($job.Process.ExitCode -ne 0) { throw "Failed $($job.Scenario), exit $($job.Process.ExitCode)" }
            $rows = @(Import-Csv -LiteralPath $job.Csv)
            if ($rows.Count -ne 1 -or $rows[0].scenario -ne $job.Scenario) { throw "Invalid scenario output: $($job.Scenario)" }
            [ordered]@{ scenario = $job.Scenario; completedAt = (Get-Date -Format o) } |
                ConvertTo-Json | Set-Content -LiteralPath $job.Done -Encoding utf8
            $completed.Add($job.Scenario)
            $running.Remove($job) | Out-Null
            Write-Host "Completed $($completed.Count)/${expectedCount}: $($job.Scenario)"
        }
        if ($running.Count -gt 0) { Start-Sleep -Seconds 5 }
    }
    $rows = @($completed | ForEach-Object { Import-Csv -LiteralPath (Join-Path $root "$_/algorithms/CBMW/results.csv") })
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    foreach ($row in $rows) {
        if ($row.scenario.EndsWith('_full500')) {
            $edgeName = $row.scenario.Replace('_full500', '_edge200')
            $edge = @($rows | Where-Object scenario -eq $edgeName)
            if ($edge.Count -ne 1 -or $edge[0].runSeed -ne $row.runSeed) { throw "Missing matched edge: $edgeName" }
            $difference = [double]::Parse($row.totalCost, $culture) - [double]::Parse($edge[0].totalCost, $culture)
            $row.marginalCost = $difference.ToString('F4', $culture)
        }
    }
    $destination = Join-Path $root 'algorithms/CBMW'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $rows | Sort-Object scenario | Export-Csv -LiteralPath (Join-Path $destination 'results.csv') -NoTypeInformation -Encoding utf8
    Write-Host "Combined all $expectedCount scenarios: $destination/results.csv"
    Write-Host 'Detailed task CSVs and aggregate CSVs remain in each scenario directory.'
} finally {
    foreach ($job in $running) {
        if (-not $job.Process.HasExited) {
            # Only the process tree started by this invocation is terminated.
            & taskkill /PID $job.Process.Id /T /F 2>$null | Out-Null
        }
    }
}
