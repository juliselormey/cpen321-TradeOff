export const REFRESH_INTERVAL_MS = 8_000;
export const HISTORY_TTL_MS = 15 * 60_000;
export const HISTORY_LOOKBACK_DAYS = 45;
export const MAX_DAILY_POINTS = 30;
export const MAX_ROLLING_POINTS = 120;

// App metadata only. Every stock's prices and chart come from Alpaca IEX.
export const STOCK_CATALOG = [
  { ticker: 'AAPL', companyName: 'Apple Inc.', isFavorite: true },
  { ticker: 'MSFT', companyName: 'Microsoft Corporation', isFavorite: false },
  { ticker: 'TSLA', companyName: 'Tesla, Inc.', isFavorite: true },
  { ticker: 'NVDA', companyName: 'NVIDIA Corporation', isFavorite: true },
  { ticker: 'GOOGL', companyName: 'Alphabet Inc.', isFavorite: false },
  { ticker: 'AMZN', companyName: 'Amazon.com, Inc.', isFavorite: false },
] as const;
export const LIVE_SYMBOLS: readonly string[] = STOCK_CATALOG.map(stock => stock.ticker);
