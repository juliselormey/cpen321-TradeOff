#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/lib/run-e2e-classes.sh" trading TradingE2ETest PlaceLimitOrderE2ETest CancelPendingOrderE2ETest SellSharesE2ETest
