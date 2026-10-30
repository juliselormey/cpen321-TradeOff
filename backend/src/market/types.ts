export interface PricePoint {
  timestamp: string;
  price: number;
}

export interface ProviderQuote {
  price: number;
  change: number | null;
  percentageChange: number | null;
  quoteAsOf: string;
}

export interface QuoteProvider {
  fetchQuote(symbol: string): Promise<ProviderQuote>;
  fetchHistory(symbol: string): Promise<PricePoint[]>;
  fetchQuotes?(symbols: readonly string[]): Promise<Record<string, ProviderQuote | MarketProviderError>>;
  fetchHistories?(symbols: readonly string[]): Promise<Record<string, PricePoint[] | MarketProviderError>>;
}

export type MarketIssueCode =
  | 'MARKET_NOT_CONFIGURED'
  | 'PROVIDER_AUTH_ERROR'
  | 'PROVIDER_RATE_LIMITED'
  | 'PROVIDER_UNAVAILABLE'
  | 'PROVIDER_INVALID_DATA';

export interface MarketIssue {
  code: MarketIssueCode;
  message: string;
}

export class MarketProviderError extends Error {
  constructor(
    public readonly code: MarketIssueCode,
    message: string,
    public readonly retryAfterMs = 30_000,
  ) {
    super(message);
    this.name = 'MarketProviderError';
  }
}

export interface MarketHistory {
  symbol: string;
  feed: 'iex';
  points: PricePoint[];
  dailyBarsUpdatedAt: string | null;
  status: 'ok' | 'stale' | 'unavailable';
  retryAt: string | null;
  error: MarketIssue | null;
}

export interface MarketStock {
  ticker: string;
  companyName: string;
  currency: 'USD';
  currentPrice: string | null;
  change: string | null;
  percentageChange: number | null;
  performancePoints: readonly number[];
  historySource: 'mock' | 'alpaca' | 'unavailable';
  history: MarketHistory | null;
  isFavorite: boolean;
  source: 'mock' | 'alpaca';
  feed: 'iex' | null;
  status: 'mock' | 'ok' | 'stale' | 'unavailable';
  quoteAsOf: string | null;
  fetchedAt: string | null;
  quoteAgeSeconds: number | null;
  retryAt: string | null;
  error: MarketIssue | null;
}
