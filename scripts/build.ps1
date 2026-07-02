[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$binDir = Join-Path $projectRoot 'bin'
$libDir = Join-Path $projectRoot 'lib'
$argumentFile = Join-Path ([System.IO.Path]::GetTempPath()) (
    'cbmw-javac-{0}.args' -f [System.Guid]::NewGuid().ToString('N'))
$stagingDir = Join-Path ([System.IO.Path]::GetTempPath()) (
    'cbmw-build-{0}' -f [System.Guid]::NewGuid().ToString('N'))

function ConvertTo-JavacArgument([string] $value) {
    $normalized = $value.Replace('\', '/').Replace('"', '\"')
    return '"' + $normalized + '"'
}

try {
    if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
        throw 'javac was not found. Install a JDK and add javac to PATH.'
    }
    if (-not (Test-Path -LiteralPath $libDir)) {
        throw "Library directory not found: $libDir"
    }
    $libraries = Get-ChildItem -LiteralPath $libDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-sources.jar' } |
        Sort-Object FullName
    if ($libraries.Count -eq 0) {
        throw "No dependency jars were found in: $libDir"
    }
    $classPath = ($libraries.FullName | ForEach-Object {
        $_.Replace('\', '/')
    }) -join ';'

    $sources = Get-ChildItem -Path (
        (Join-Path $projectRoot 'sources'),
        (Join-Path $projectRoot 'examples')
    ) -Recurse -Filter '*.java' -File | Sort-Object FullName

    if ($sources.Count -eq 0) {
        throw 'No Java source files were found under sources/ or examples/.'
    }

    New-Item -ItemType Directory -Path $stagingDir | Out-Null

    $javacArguments = [System.Collections.Generic.List[string]]::new()
    $javacArguments.Add('-encoding')
    $javacArguments.Add('UTF-8')
    $javacArguments.Add('-cp')
    $javacArguments.Add((ConvertTo-JavacArgument $classPath))
    $javacArguments.Add('-d')
    $javacArguments.Add((ConvertTo-JavacArgument $stagingDir))
    foreach ($source in $sources) {
        $javacArguments.Add((ConvertTo-JavacArgument $source.FullName))
    }

    [System.IO.File]::WriteAllLines(
        $argumentFile,
        $javacArguments,
        [System.Text.UTF8Encoding]::new($false))

    Write-Host "[build] Compiling $($sources.Count) Java files in clean staging..."
    & javac "@$argumentFile"
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    if (-not (Test-Path -LiteralPath $binDir)) {
        New-Item -ItemType Directory -Path $binDir | Out-Null
    }
    Get-ChildItem -LiteralPath $binDir -Recurse -Filter '*.class' -File |
        Remove-Item -Force
    Copy-Item -Path (Join-Path $stagingDir '*') -Destination $binDir `
        -Recurse -Force
    Write-Host "[build] Complete: $binDir"
} finally {
    if (Test-Path -LiteralPath $argumentFile) {
        Remove-Item -LiteralPath $argumentFile -Force
    }
    if (Test-Path -LiteralPath $stagingDir) {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force
    }
}
