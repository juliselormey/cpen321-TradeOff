import axios from 'axios';
import { MarketProviderError, PricePoint, ProviderQuote, QuoteProvider } from './types';

const BASE_URL = 'https://data.alpaca.markets/v2/stocks';
const HISTORY_WINDOW_MS = 45 * 24 * 60 * 60 * 1000;

const invalid = (message: string) => new MarketProviderError('PROVIDER_INVALID_DATA', message);
const finiteOrZero = (n: number) => (Number.isFinite(n) ? n : 0);

function object(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

// Dynamic, ticker-keyed lookups go through a Map built from own enumerable
// properties only, so keys like "constructor" or "__proto__" can't reach the prototype.
function entries(value: unknown): Map<string, unknown> {
  return new Map(Object.entries(object(value)));
}

function timestamp(value: unknown): string | null {
  const ms = Date.parse(typeof value === 'string' ? value : '');
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
  if (!price || !time) throw invalid('Alpaca returned an unsupported stock snapshot.');
  const prevClose = positive(object(root.prevDailyBar).c);
  return {
    price,
    change: prevClose ? Number((price - prevClose).toFixed(4)) : null,
    percentageChange: prevClose ? Number(((price - prevClose) / prevClose * 100).toFixed(4)) : null,
    quoteAsOf: time,
  };
}

export function parseAlpacaBars(data: unknown): PricePoint[] {
  const root = object(data);
  if (!Object.hasOwn(root, 'bars')) throw invalid('Alpaca returned an invalid historical response.');
  if (root.bars == null) return [];
  if (!Array.isArray(root.bars)) throw invalid('Alpaca returned an invalid historical bar.');
  const points = new Map<string, PricePoint>();
  for (const item of root.bars) {
    const bar = object(item);
    const time = timestamp(bar.t);
    const price = positive(bar.c);
    if (!time || price == null) throw invalid('Alpaca returned an invalid historical bar.');
    points.set(time, { timestamp: time, price });
  }
  return [...points.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)).slice(-30);
}

function failure(error: unknown): MarketProviderError {
  if (error instanceof MarketProviderError) return error;
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401 || status === 403) {
      return new MarketProviderError('PROVIDER_AUTH_ERROR', 'Alpaca rejected the credentials or IEX access.', 60_000);
    }
    if (status === 429) {
      const headers = (error.response?.headers ?? {}) as Record<string, unknown>;
      const retryAfter = String(headers['retry-after'] ?? '');
      const seconds = Number(retryAfter);
      const delay = Number.isFinite(seconds) ? seconds * 1_000 : Date.parse(retryAfter) - Date.now();
      const reset = Number(headers['x-ratelimit-reset']) * 1_000 - Date.now();
      const wait = Math.max(60_000, finiteOrZero(delay), finiteOrZero(reset));
      return new MarketProviderError('PROVIDER_RATE_LIMITED', 'Alpaca rate limit reached.', wait);
    }
  }
  return new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.', 8_000);
}

/** Per-ticker parse: any failure becomes that ticker's error instead of failing the batch. */
function attempt<T>(parse: () => T): T | MarketProviderError {
  try {
    return parse();
  } catch (error: unknown) {
    return failure(error);
  }
}

export class AlpacaProvider implements QuoteProvider {
  constructor(
    private readonly credentials = () => ({
      key: process.env.ALPACA_API_KEY_ID?.trim() ?? '',
      secret: process.env.ALPACA_API_SECRET_KEY?.trim() ?? '',
    }),
    private readonly now: () => number = Date.now,
  ) {}

  /** The single place that talks to Alpaca: credentials, headers, timeout and error mapping. */
  private async get(path: string, params: Record<string, unknown>): Promise<unknown> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      throw new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
    }
    try {
      // eslint-disable-next-line security/detect-non-literal-uri
      const res = await axios.get(`${BASE_URL}${path}`, {
        params,
        headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
        timeout: 4_000,
        maxRedirects: 0,
      });
      return res.data;
    } catch (error: unknown) {
      throw failure(error);
    }
  }

  /** Empty input is a no-op; missing credentials become a per-ticker error; anything else propagates. */
  private async batch<T>(
    tickers: readonly string[],
    load: () => Promise<Record<string, T | MarketProviderError>>,
  ): Promise<Record<string, T | MarketProviderError>> {
    if (tickers.length === 0) return {};
    try {
      return await load();
    } catch (error: unknown) {
      const err = failure(error);
      if (err.code !== 'MARKET_NOT_CONFIGURED') throw err;
      return Object.fromEntries(tickers.map(t => [t, err]));
    }
  }

  private barParams(limit: number) {
    const end = new Date(this.now());
    end.setUTCHours(0, 0, 0, 0);
    return {
      timeframe: '1Day', feed: 'iex', limit, sort: 'desc', adjustment: 'raw',
      start: new Date(end.getTime() - HISTORY_WINDOW_MS).toISOString(),
      end: end.toISOString(),
    };
  }

  async fetchQuote(symbol: string): Promise<ProviderQuote> {
    return parseAlpacaSnapshot(await this.get(`/${symbol}/snapshot`, { feed: 'iex' }));
  }

  async fetchHistory(symbol: string): Promise<PricePoint[]> {
    return parseAlpacaBars(await this.get(`/${symbol}/bars`, this.barParams(30)));
  }

  fetchQuotes(tickers: readonly string[]) {
    return this.batch<ProviderQuote>(tickers, async () => {
      const data = entries(await this.get('/snapshots', { symbols: tickers.join(','), feed: 'iex' }));
      return Object.fromEntries(tickers.map(ticker => [ticker, attempt(() => {
        const item = data.get(ticker);
        if (!item) throw invalid(`No snapshot returned for ${ticker}`);
        return parseAlpacaSnapshot(item);
      })]));
    });
  }

  fetchHistories(tickers: readonly string[]) {
    return this.batch<PricePoint[]>(tickers, async () => {
      // A ticker with no entry parses as "no bars" (empty history).
      const barsBySymbol = new Map<string, unknown>();
      const seenTokens = new Set<string>();
      let pageToken = '';
      do {
        const root = object(await this.get('/bars', {
          symbols: tickers.join(','),
          ...this.barParams(1000),
          ...(pageToken ? { page_token: pageToken } : {}),
        }));
        const barsMap = entries(root.bars);
        for (const ticker of tickers) {
          const raw = barsMap.get(ticker);
          if (raw === undefined) continue;
          const previous = barsBySymbol.get(ticker);
          barsBySymbol.set(ticker, Array.isArray(raw) ? [...(Array.isArray(previous) ? previous : []), ...raw] : raw);
        }
        const next = typeof root.next_page_token === 'string' ? root.next_page_token : '';
        if (seenTokens.has(next)) throw invalid('Alpaca returned a repeating pagination token.');
        if (next) seenTokens.add(next);
        pageToken = next;
      } while (pageToken);

      return Object.fromEntries(tickers.map(ticker =>
        [ticker, attempt(() => parseAlpacaBars({ bars: barsBySymbol.get(ticker) }))]));
    });
  }
}