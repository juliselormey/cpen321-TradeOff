import axios from 'axios';
import { MarketProviderError, PricePoint, ProviderQuote, QuoteProvider } from './types';

function object(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

// Dynamic, ticker-keyed lookups go through a Map built from own enumerable
// properties only, so keys like "constructor" or "__proto__" can't reach the prototype.
function entries(value: unknown): Map<string, unknown> {
  return new Map(Object.entries(object(value)));
}

function timestamp(value: unknown): string | null {
  const str = typeof value === 'string' ? value : '';
  const ms = Date.parse(str);
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null;
}

function positive(value: unknown): number | null {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? num : null;
}

export function parseAlpacaSnapshot(data: unknown): ProviderQuote {
  const root = object(data);
  const trade = object(root.latestTrade);
  const bar = object(root.minuteBar ?? root.dailyBar);
  const price = positive(trade.p ?? bar.c);
  const time = timestamp(trade.t ?? bar.t);
  if (!price || !time) {
    throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an unsupported stock snapshot.');
  }
  const prevClose = positive(object(root.prevDailyBar).c);
  const change = prevClose != null ? Number((price - prevClose).toFixed(4)) : null;
  const percentageChange = prevClose != null ? Number(((price - prevClose) / prevClose * 100).toFixed(4)) : null;
  return { price, change, percentageChange, quoteAsOf: time };
}

export function parseAlpacaBars(data: unknown): PricePoint[] {
  const root = object(data);
  if (root.bars === null || root.bars === undefined || (Array.isArray(root.bars) && root.bars.length === 0)) {
    return [];
  }
  if (!Array.isArray(root.bars)) {
    throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an invalid historical bar.');
  }
  const points = new Map<string, PricePoint>();
  for (const item of root.bars) {
    const bar = object(item);
    const time = timestamp(bar.t);
    const p = positive(bar.c);
    if (!time || p == null) {
      throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an invalid historical bar.');
    }
    points.set(time, { timestamp: time, price: p });
  }
  return [...points.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
    .slice(-30);
}

function failure(error: unknown): MarketProviderError {
  if (error instanceof MarketProviderError) return error;
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401 || status === 403) {
      return new MarketProviderError('PROVIDER_AUTH_ERROR', 'Alpaca rejected the credentials or IEX access.', 60_000);
    }
    if (status === 429) {
      const headers = error.response?.headers;
      const raw: unknown = headers?.['retry-after'];
      const retry = typeof raw === 'string' ? raw : '';
      const seconds = Number(retry);
      const delay = retry && Number.isFinite(seconds) ? seconds * 1_000 : Date.parse(retry) - Date.now();
      const reset = Number(headers?.['x-ratelimit-reset']) * 1_000 - Date.now();
      const wait = Math.max(60_000, Number.isFinite(delay) ? delay : 0, Number.isFinite(reset) ? reset : 0);
      return new MarketProviderError('PROVIDER_RATE_LIMITED', 'Alpaca rate limit reached.', wait);
    }
  }
  return new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.', 8_000);
}

export class AlpacaProvider implements QuoteProvider {
  constructor(
    private readonly credentials = () => ({
      key: process.env.ALPACA_API_KEY_ID?.trim() ?? '',
      secret: process.env.ALPACA_API_SECRET_KEY?.trim() ?? '',
    }),
    private readonly now: () => number = Date.now,
  ) {}

  async fetchQuote(symbol: string): Promise<ProviderQuote> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      throw new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
    }
    try {
      // eslint-disable-next-line security/detect-non-literal-uri
      const res = await axios.get(`https://data.alpaca.markets/v2/stocks/${symbol}/snapshot`, {
        params: { feed: 'iex' },
        headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
        timeout: 4_000,
        maxRedirects: 0,
      });
      return parseAlpacaSnapshot(res.data);
    } catch (error: unknown) {
      throw failure(error);
    }
  }

  async fetchHistory(symbol: string): Promise<PricePoint[]> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      throw new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
    }
    const end = new Date(this.now());
    end.setUTCHours(0, 0, 0, 0);
    const start = new Date(end.getTime() - 45 * 24 * 60 * 60 * 1000);
    try {
      // eslint-disable-next-line security/detect-non-literal-uri
      const res = await axios.get(`https://data.alpaca.markets/v2/stocks/${symbol}/bars`, {
        params: {
          timeframe: '1Day',
          feed: 'iex',
          start: start.toISOString(),
          end: end.toISOString(),
          limit: 30,
          sort: 'desc',
          adjustment: 'raw',
        },
        headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
        timeout: 4_000,
        maxRedirects: 0,
      });
      return parseAlpacaBars(res.data);
    } catch (error: unknown) {
      throw failure(error);
    }
  }

  async fetchQuotes(tickers: readonly string[]): Promise<Record<string, ProviderQuote | MarketProviderError>> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      const err = new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
      return Object.fromEntries(tickers.map(t => [t, err]));
    }
    if (tickers.length === 0) return {};
    try {
      const res = await axios.get('https://data.alpaca.markets/v2/stocks/snapshots', {
        params: { symbols: tickers.join(','), feed: 'iex' },
        headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
        timeout: 4_000,
        maxRedirects: 0,
      });
      const data = entries(res.data);
      const result = new Map<string, ProviderQuote | MarketProviderError>();
      for (const ticker of tickers) {
        const item = data.get(ticker) ?? data.get(ticker.toUpperCase()) ?? data.get(ticker.toLowerCase());
        if (!item) {
          result.set(ticker, new MarketProviderError('PROVIDER_INVALID_DATA', `No snapshot returned for ${ticker}`));
        } else {
          try {
            result.set(ticker, parseAlpacaSnapshot(item));
          } catch (err: unknown) {
            result.set(ticker, err instanceof MarketProviderError ? err : failure(err));
          }
        }
      }
      return Object.fromEntries(result);
    } catch (error: unknown) {
      throw failure(error);
    }
  }

  async fetchHistories(tickers: readonly string[]): Promise<Record<string, PricePoint[] | MarketProviderError>> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      const err = new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
      return Object.fromEntries(tickers.map(t => [t, err]));
    }
    if (tickers.length === 0) return {};
    const end = new Date(this.now());
    end.setUTCHours(0, 0, 0, 0);
    const start = new Date(end.getTime() - 45 * 24 * 60 * 60 * 1000);
    // A ticker with no entry here parses as "no bars" (empty history), same as before.
    const barsBySymbol = new Map<string, unknown>();
    let pageToken: string | null = null;
    const seenTokens = new Set<string>();

    try {
      do {
        const params: Record<string, unknown> = {
          symbols: tickers.join(','),
          timeframe: '1Day',
          feed: 'iex',
          start: start.toISOString(),
          end: end.toISOString(),
          limit: 1000,
          sort: 'desc',
          adjustment: 'raw',
        };
        if (pageToken) params.page_token = pageToken;
        const res = await axios.get('https://data.alpaca.markets/v2/stocks/bars', {
          params,
          headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
          timeout: 4_000,
          maxRedirects: 0,
        });
        const root = object(res.data);
        const barsMap = entries(root.bars);
        for (const ticker of tickers) {
          const raw = barsMap.get(ticker) ?? barsMap.get(ticker.toUpperCase());
          if (raw !== undefined) {
            if (Array.isArray(raw)) {
              const previous = barsBySymbol.get(ticker);
              const current: unknown[] = Array.isArray(previous) ? previous : [];
              barsBySymbol.set(ticker, current.concat(raw));
            } else {
              barsBySymbol.set(ticker, raw);
            }
          }
        }
        const nextToken = typeof root.next_page_token === 'string' && root.next_page_token ? root.next_page_token : null;
        if (nextToken && seenTokens.has(nextToken)) {
          throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned a repeating pagination token.');
        }
        if (nextToken) seenTokens.add(nextToken);
        pageToken = nextToken;
      } while (pageToken);

      const result = new Map<string, PricePoint[] | MarketProviderError>();
      for (const ticker of tickers) {
        try {
          result.set(ticker, parseAlpacaBars({ bars: barsBySymbol.get(ticker) }));
        } catch (err: unknown) {
          result.set(ticker, err instanceof MarketProviderError ? err : failure(err));
        }
      }
      return Object.fromEntries(result);
    } catch (error: unknown) {
      throw failure(error);
    }
  }
}
