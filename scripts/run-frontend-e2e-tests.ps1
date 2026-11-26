#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$suites = @('BrowseStocksE2ETest', 'TradingE2ETest', 'PortfolioE2ETest', 'LeaderboardE2ETest', 'SendFriendRequestE2ETest')
$reportRoot = Join-Path $root ('frontend/app/build/reports/e2e-all/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$failed = @()
Push-Location (Join-Path $root 'frontend')
try {
    foreach ($suite in $suites) {
        Write-Host "`n=== $suite ==="
        $started = [DateTime]::UtcNow
        & .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.$suite" --console=plain
        if ($LASTEXITCODE -ne 0) { $failed += $suite }
        # Each Gradle invocation replaces its default report. Preserve fresh reports.
        $source = 'app/build/reports/androidTests/connected/debug'
        $index = Join-Path $source 'index.html'
        if ((Test-Path $index) -and (Get-Item $index).LastWriteTimeUtc -ge $started) {
            $destination = Join-Path $reportRoot $suite
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Copy-Item (Join-Path $source '*') $destination -Recurse -Force
        }
    }
} finally { Pop-Location }
Write-Host "Reports: $reportRoot"
if ($failed.Count -gt 0) { throw "E2E failures: $($failed -join ', '). See Gradle output and saved reports." }
