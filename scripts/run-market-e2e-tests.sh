#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/lib/run-e2e-classes.sh" market SearchStocksE2ETest ViewStockDetailsE2ETest ManageFavoritesE2ETest BrowseStocksE2ETest
