#!/usr/bin/env pwsh
param([Parameter(Mandatory=$true)][string[]]$Suites, [string]$ReportPrefix = 'all')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportRoot = Join-Path $root ('frontend/app/build/reports/e2e-' + $ReportPrefix + '/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$failed = @()
Push-Location (Join-Path $root 'frontend')
try {
    foreach ($suite in $Suites) {
        Write-Host "`n=== $suite ==="
        $started = [DateTime]::UtcNow
        & .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.$suite" --console=plain
        if ($LASTEXITCODE -ne 0) { $failed += $suite }
        $source = 'app/build/reports/androidTests/connected/debug'
        $index = Join-Path $source 'index.html'
        if ((Test-Path -LiteralPath $index) -and (Get-Item -LiteralPath $index).LastWriteTimeUtc -ge $started) {
            $destination = Join-Path $reportRoot $suite
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Copy-Item (Join-Path $source '*') $destination -Recurse -Force
        }
    }
} finally { Pop-Location }
Write-Host "Reports: $reportRoot"
if ($failed.Count -gt 0) { throw "E2E failures: $($failed -join ', '). See Gradle output and saved reports." }
