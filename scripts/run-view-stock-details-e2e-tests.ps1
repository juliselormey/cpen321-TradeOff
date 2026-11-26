#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'frontend')
try {
    & .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.ViewStockDetailsE2ETest' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'View Stock Details E2E failed. See frontend/app/build/reports/androidTests/connected/debug/index.html.' }
} finally { Pop-Location }
