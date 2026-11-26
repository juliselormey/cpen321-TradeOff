#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/lib/run-e2e-classes.sh" all SearchStocksE2ETest ViewStockDetailsE2ETest ManageFavoritesE2ETest TradingE2ETest PlaceLimitOrderE2ETest CancelPendingOrderE2ETest SellSharesE2ETest PortfolioE2ETest ViewTransactionHistoryE2ETest LeaderboardE2ETest SendFriendRequestE2ETest BrowseStocksE2ETest
