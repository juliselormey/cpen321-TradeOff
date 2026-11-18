Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
$localProperties = Join-Path $frontend 'local.properties'

if (-not (Test-Path -LiteralPath $localProperties)) {
    throw 'Create frontend/local.properties first (Android Studio normally creates it when the project is opened).'
}
$originalProperties = [System.IO.File]::ReadAllText($localProperties)
$marketPropertyPattern = '(?m)^[ \t]*MARKET_API_BASE_URL[ \t]*=.*$'
$testProperties = [regex]::Replace(
    $originalProperties,
    $marketPropertyPattern,
    'MARKET_API_BASE_URL=http://10.0.2.2:3003/'
)
if (-not [regex]::IsMatch($originalProperties, $marketPropertyPattern)) {
    $separator = if ($originalProperties.EndsWith("`n")) { '' } else { "`r`n" }
    $testProperties = $originalProperties + $separator + 'MARKET_API_BASE_URL=http://10.0.2.2:3003/' + "`r`n"
}

try {
    $existingFixture = $null
    try { $existingFixture = Invoke-RestMethod -Uri 'http://127.0.0.1:3003/__e2e/health' -TimeoutSec 1 } catch { }
    if ($null -eq $existingFixture -or $existingFixture.suite -ne 'market' -or -not $existingFixture.ready) {
        throw 'Market fixture is not running. In another terminal, run `cd backend` then `npm.cmd run dev:e2e:market`.'
    }

    [System.IO.File]::WriteAllText($localProperties, $testProperties)
    Push-Location $frontend
    try {
        & .\gradlew.bat :app:connectedDebugAndroidTest `
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' `
            '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.MarketFlowTest' `
            --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw 'Market E2E tests failed. Inspect app/build/reports/androidTests/connected/debug/index.html.'
        }
    } finally { Pop-Location }
} finally {
    [System.IO.File]::WriteAllText($localProperties, $originalProperties)
}
