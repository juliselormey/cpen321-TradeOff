#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'lib/run-e2e-classes.ps1') -ReportPrefix 'portfolio' -Suites @('PortfolioE2ETest', 'ViewTransactionHistoryE2ETest')
