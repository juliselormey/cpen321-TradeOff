#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'lib/run-e2e-classes.ps1') -ReportPrefix 'market' -Suites @('SearchStocksE2ETest', 'ViewStockDetailsE2ETest', 'ManageFavoritesE2ETest', 'BrowseStocksE2ETest')
