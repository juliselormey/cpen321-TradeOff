import axios from 'axios';
import { HISTORY_LOOKBACK_DAYS, MAX_DAILY_POINTS } from './catalog';
import { MarketProviderError, PricePoint, ProviderQuote, QuoteProvider } from './types';

function object(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
}

function positive(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function timestamp(value: unknown): string | null {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) return null;
  return new Date(value).toISOString();
}

export function parseAlpacaSnapshot(data: unknown): ProviderQuote {
  const snapshot = object(data);
  const trade = object(snapshot.latestTrade);
  const minute = object(snapshot.minuteBar);
  const daily = object(snapshot.dailyBar);
  const previousClose = object(snapshot.prevDailyBar).c;
  const candidates = [
    { price: trade.p, time: timestamp(trade.t) },
    { price: minute.c, time: timestamp(minute.t) },
    { price: daily.c, time: timestamp(daily.t) },
  ].filter((point): point is { price: number; time: string } => positive(point.price) && point.time !== null);
  candidates.sort((a, b) => Date.parse(b.time) - Date.parse(a.time));
  const latest = candidates[0];
  if (!latest) throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned no valid price.');
  const change = positive(previousClose) ? Number((latest.price - previousClose).toFixed(8)) : null;
  return {
    price: latest.price,
    change,
    percentageChange: change !== null && positive(previousClose) ? change / previousClose * 100 : null,
    quoteAsOf: latest.time,
  };
}

export function parseAlpacaBars(data: unknown): PricePoint[] {
  const response = object(data);
  // null is Alpaca's valid no-bars result. Missing/wrong shapes are errors.
  if (response.bars === null) return [];
  if (!Array.isArray(response.bars)) {
    throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned invalid historical data.');
  }
  const points = new Map<string, PricePoint>();
  for (const value of response.bars) {
    const bar = object(value);
    const time = timestamp(bar.t);
    if (!time || !positive(bar.c)) {
      throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an invalid historical bar.');
    }
    points.set(time, { timestamp: time, price: bar.c });
  }
  return [...points.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
    .slice(-MAX_DAILY_POINTS);
}

function failure(error: unknown): MarketProviderError {
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
  return new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.');
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
    return parseAlpacaSnapshot(await this.get(symbol, 'snapshot', {}));
  }

  async fetchHistory(symbol: string): Promise<PricePoint[]> {
    // Exclude today's partial daily bar. Midnight UTC for the New York date
    // is before that session's daily bar start (04:00/05:00 UTC).
    const date = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(this.now());
    const end = new Date(`${date}T00:00:00Z`);
    const start = new Date(end.getTime() - HISTORY_LOOKBACK_DAYS * 86_400_000);
    return parseAlpacaBars(await this.get(symbol, 'bars', {
      timeframe: '1Day', start: start.toISOString(), end: end.toISOString(),
      limit: MAX_DAILY_POINTS, sort: 'desc', adjustment: 'raw',
    }));
  }

  async fetchQuotes(symbols: readonly string[]): Promise<Record<string, ProviderQuote | MarketProviderError>> {
    const snapshots = new Map(Object.entries(object(await this.request('snapshots', { symbols: symbols.join(',') }))));
    return Object.fromEntries(symbols.map((symbol): [string, ProviderQuote | MarketProviderError] => {
      try { return [symbol, parseAlpacaSnapshot(snapshots.get(symbol))]; }
      catch { return [symbol, new MarketProviderError('PROVIDER_INVALID_DATA', `No valid Alpaca quote for ${symbol}.`)]; }
    }));
  }

  async fetchHistories(symbols: readonly string[]): Promise<Record<string, PricePoint[] | MarketProviderError>> {
    const date = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(this.now());
    const end = new Date(`${date}T00:00:00Z`);
    const start = new Date(end.getTime() - HISTORY_LOOKBACK_DAYS * 86_400_000);
    const collected = new Map<string, PricePoint[] | MarketProviderError>(symbols.map(s => [s, []]));
    let pageToken: string | undefined;
    const seenTokens = new Set<string>();
    // The page limit is TOTAL, not per symbol. Follow pagination so later
    // symbols aren't silently dropped; cap work and fail honestly if incomplete.
    for (let page = 0; page < 10; page++) {
      const response = object(await this.request('bars', {
        symbols: symbols.join(','), timeframe: '1Day',
        start: start.toISOString(), end: end.toISOString(),
        limit: 1_000, sort: 'desc', adjustment: 'raw',
        ...(pageToken ? { page_token: pageToken } : {}),
      }));
      if (response.bars !== null && (typeof response.bars !== 'object' || Array.isArray(response.bars))) {
        throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned invalid batch history.');
      }
      const bars = new Map(Object.entries(object(response.bars)));
      for (const symbol of symbols) {
        const previous = collected.get(symbol);
        const pageBars = bars.get(symbol);
        if (previous instanceof MarketProviderError || pageBars == null) continue;
        try {
          if (!Array.isArray(pageBars)) throw new Error('Invalid bar array');
          collected.set(symbol, parseAlpacaBars({ bars: [
            ...(previous ?? []).map(p => ({ t: p.timestamp, c: p.price })),
            ...pageBars,
          ] }));
        } catch {
          collected.set(symbol, new MarketProviderError('PROVIDER_INVALID_DATA', `Invalid Alpaca history for ${symbol}.`));
        }
      }
      const next = response.next_page_token;
      if (next == null) return Object.fromEntries(collected);
      if (typeof next !== 'string' || !next || seenTokens.has(next)) break;
      seenTokens.add(next);
      pageToken = next;
    }
    throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca history pagination did not complete.');
  }

  private async get(symbol: string, endpoint: string, params: Record<string, string | number>): Promise<unknown> {
    return this.request(`${encodeURIComponent(symbol)}/${endpoint}`, params);
  }

  private async request(path: string, params: Record<string, string | number>): Promise<unknown> {
    const { key, secret } = this.credentials();
    if (!key || !secret) {
      throw new MarketProviderError('MARKET_NOT_CONFIGURED', 'Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY on the backend.', 60_000);
    }
    try {
      const response = await axios.get<unknown>(
        `https://data.alpaca.markets/v2/stocks/${path}`,
        {
          params: { ...params, feed: 'iex' },
          headers: { 'APCA-API-KEY-ID': key, 'APCA-API-SECRET-KEY': secret },
          timeout: 4_000, maxRedirects: 0, maxContentLength: 256 * 1_024,
        },
      );
      return response.data;
    } catch (error: unknown) {
      // Axios request config contains credentials; never expose/log the raw error.
      throw failure(error);
    }
  }
}
