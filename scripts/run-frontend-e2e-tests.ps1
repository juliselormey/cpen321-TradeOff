#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'frontend')
try {
    & .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.PortfolioFlowTest" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Portfolio E2E failed. Inspect app/build/reports/androidTests/connected/debug/index.html.' }
} finally { Pop-Location }
