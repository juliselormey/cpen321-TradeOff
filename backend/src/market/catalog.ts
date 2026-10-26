interface CatalogStock {
  ticker: string;
  companyName: string;
  currentPrice: string;
  percentageChange: number;
  performancePoints: readonly number[];
  isFavorite: boolean;
}

// Keep the five mock stocks aligned with the existing Android sample list.
// AAPL's quote comes exclusively from Alpaca IEX; its sample price is never served.
export const LIVE_SYMBOL = 'AAPL';
export const REFRESH_INTERVAL_MS = 8_000;
export const HISTORY_TTL_MS = 15 * 60_000;
export const HISTORY_LOOKBACK_DAYS = 45;
export const MAX_DAILY_POINTS = 30;
export const MAX_ROLLING_POINTS = 120;

export const STOCK_CATALOG: readonly CatalogStock[] = [
  {
    ticker: 'AAPL', companyName: 'Apple Inc.', currentPrice: '189.43',
    percentageChange: 1.25, performancePoints: [], isFavorite: true,
  },
  {
    ticker: 'MSFT', companyName: 'Microsoft Corporation', currentPrice: '415.26',
    percentageChange: 0.82, performancePoints: [407, 410, 409, 413, 415], isFavorite: false,
  },
  {
    ticker: 'TSLA', companyName: 'Tesla Inc.', currentPrice: '242.84',
    percentageChange: -2.14, performancePoints: [251, 248, 250, 245, 243], isFavorite: true,
  },
  {
    ticker: 'NVDA', companyName: 'NVIDIA Corporation', currentPrice: '138.07',
    percentageChange: 1.91, performancePoints: [132, 134, 133, 136, 138], isFavorite: true,
  },
  {
    ticker: 'GOOGL', companyName: 'Alphabet Inc.', currentPrice: '242.84',
    percentageChange: -1.03, performancePoints: [248, 247, 245, 244, 243], isFavorite: false,
  },
  {
    ticker: 'AMZN', companyName: 'Amazon.com, Inc.', currentPrice: '178.29',
    percentageChange: 2.19, performancePoints: [169, 171, 173, 177, 178], isFavorite: false,
  },
];
