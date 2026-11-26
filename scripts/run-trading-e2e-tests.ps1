#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'lib/run-e2e-classes.ps1') -ReportPrefix 'trading' -Suites @('TradingE2ETest', 'PlaceLimitOrderE2ETest', 'CancelPendingOrderE2ETest', 'SellSharesE2ETest')
