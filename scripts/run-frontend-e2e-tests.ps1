#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'lib/run-e2e-classes.ps1') -ReportPrefix 'all' -Suites @('SearchStocksE2ETest', 'ViewStockDetailsE2ETest', 'ManageFavoritesE2ETest', 'TradingE2ETest', 'PlaceLimitOrderE2ETest', 'CancelPendingOrderE2ETest', 'SellSharesE2ETest', 'PortfolioE2ETest', 'ViewTransactionHistoryE2ETest', 'LeaderboardE2ETest', 'SendFriendRequestE2ETest', 'BrowseStocksE2ETest')
