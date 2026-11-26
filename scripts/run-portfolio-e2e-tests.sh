#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/lib/run-e2e-classes.sh" portfolio PortfolioE2ETest ViewTransactionHistoryE2ETest
