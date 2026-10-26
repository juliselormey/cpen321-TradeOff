import {
  HISTORY_TTL_MS, LIVE_SYMBOLS, MAX_DAILY_POINTS, MAX_ROLLING_POINTS,
  REFRESH_INTERVAL_MS, STOCK_CATALOG,
} from './catalog';
import { AlpacaProvider } from './alpaca';
import {
  MarketHistory, MarketIssue, MarketProviderError, MarketStock,
  PricePoint, ProviderQuote, QuoteProvider,
} from './types';

interface CacheEntry<T> {
  value: T | null;
  fetchedAt: number | null;
  nextAttemptAt: number;
  issue: MarketIssue | null;
}

function emptyEntry<T>(): CacheEntry<T> {
  return { value: null, fetchedAt: null, nextAttemptAt: 0, issue: null };
}

export class MarketService {
  private readonly prices = new Map<string, CacheEntry<ProviderQuote>>();
  private readonly histories = new Map<string, CacheEntry<PricePoint[]>>();
  private readonly rolling = new Map<string, Map<string, PricePoint>>();
  private readonly inFlight = new Map<string, Promise<void>>();
  private blockedUntil = 0;
  private providerIssue: MarketIssue | null = null;

  constructor(
    private readonly provider: QuoteProvider = new AlpacaProvider(),
    private readonly now: () => number = Date.now,
  ) {}

  async getStocks(): Promise<MarketStock[]> {
    return Promise.all(STOCK_CATALOG.map(stock => this.getStock(stock.ticker)));
  }

  async getStock(ticker: string): Promise<MarketStock> {
    const stock = STOCK_CATALOG.find(item => item.ticker === ticker);
    if (!stock) throw new Error('Unsupported market symbol');
    const base = {
      ticker: stock.ticker, companyName: stock.companyName,
      currency: 'USD' as const, isFavorite: stock.isFavorite,
    };
    await Promise.all([this.ensurePrice(ticker), this.ensureHistory(ticker)]);
    const cached = this.prices.get(ticker);
    const quote = cached?.value;
    const history = this.historyResult(ticker);
    return {
      ...base, currentPrice: quote ? String(quote.price) : null,
      change: quote?.change != null ? String(quote.change) : null,
      percentageChange: quote?.percentageChange ?? null,
      performancePoints: history.points.map(point => point.price),
      historySource: history.points.length ? 'alpaca' : 'unavailable', history,
      source: 'alpaca', feed: 'iex',
      status: quote ? (cached?.issue ? 'stale' : 'ok') : 'unavailable',
      quoteAsOf: quote?.quoteAsOf ?? null,
      fetchedAt: cached?.fetchedAt != null ? new Date(cached.fetchedAt).toISOString() : null,
      quoteAgeSeconds: quote ? Math.max(0, Math.floor((this.now() - Date.parse(quote.quoteAsOf)) / 1_000)) : null,
      retryAt: cached?.issue ? new Date(cached.nextAttemptAt).toISOString() : null,
      error: cached?.issue ?? null,
    };
  }

  async getHistory(ticker: string): Promise<MarketHistory> {
    if (!LIVE_SYMBOLS.includes(ticker)) throw new Error('Unsupported market symbol');
    await this.ensureHistory(ticker);
    return this.historyResult(ticker);
  }

  private async ensurePrice(symbol: string): Promise<void> {
    if (this.provider.fetchQuotes) {
      return this.refreshBatch('prices', this.prices, REFRESH_INTERVAL_MS,
        symbols => this.provider.fetchQuotes!(symbols), (ticker, quote, cached) => {
          if (cached && Date.parse(quote.quoteAsOf) < Date.parse(cached.quoteAsOf)) {
            throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an older snapshot.');
          }
          this.appendSample(ticker, { timestamp: quote.quoteAsOf, price: quote.price });
          return quote;
        });
    }
    let entry = this.prices.get(symbol);
    if (!entry) { entry = emptyEntry<ProviderQuote>(); this.prices.set(symbol, entry); }
    const cached = entry;
    await this.refresh(`price:${symbol}`, cached, REFRESH_INTERVAL_MS, async () => {
      const quote = await this.provider.fetchQuote(symbol);
      if (cached.value && Date.parse(quote.quoteAsOf) < Date.parse(cached.value.quoteAsOf)) {
        throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an older snapshot.');
      }
      this.appendSample(symbol, { timestamp: quote.quoteAsOf, price: quote.price });
      return quote;
    });
  }

  private async ensureHistory(symbol: string): Promise<void> {
    if (this.provider.fetchHistories) {
      return this.refreshBatch('histories', this.histories, HISTORY_TTL_MS,
        symbols => this.provider.fetchHistories!(symbols), (_ticker, points) =>
          [...points].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)).slice(-MAX_DAILY_POINTS));
    }
    let entry = this.histories.get(symbol);
    if (!entry) { entry = emptyEntry<PricePoint[]>(); this.histories.set(symbol, entry); }
    await this.refresh(`history:${symbol}`, entry, HISTORY_TTL_MS, async () => {
      const points = await this.provider.fetchHistory(symbol);
      return [...points].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)).slice(-MAX_DAILY_POINTS);
    });
  }

  // One batch warms every due catalog entry. Individual-stock and list routes
  // share both the in-flight promise and per-symbol caches.
  private async refreshBatch<T>(
    key: string, cache: Map<string, CacheEntry<T>>, ttl: number,
    fetch: (symbols: readonly string[]) => Promise<Record<string, T | MarketProviderError>>,
    accept: (symbol: string, value: T, previous: T | null) => T,
  ): Promise<void> {
    const pending = this.inFlight.get(key);
    if (pending) return pending;
    const due: string[] = [];
    for (const symbol of LIVE_SYMBOLS) {
      let entry = cache.get(symbol);
      if (!entry) { entry = emptyEntry<T>(); cache.set(symbol, entry); }
      if (this.now() < entry.nextAttemptAt) continue;
      if (this.now() < this.blockedUntil) {
        entry.issue = this.providerIssue;
        entry.nextAttemptAt = this.blockedUntil;
      } else due.push(symbol);
    }
    if (!due.length) return;
    const work = (async () => {
      let results: Record<string, T | MarketProviderError>;
      try { results = await fetch(due); } catch (error: unknown) {
        await Promise.all(due.map(symbol => this.load(cache.get(symbol)!, ttl, async () => { throw error; })));
        return;
      }
      await Promise.all(due.map(symbol => {
        const entry = cache.get(symbol)!;
        return this.load(entry, ttl, async () => {
          const value = results[symbol];
          if (value === undefined) throw new MarketProviderError('PROVIDER_INVALID_DATA', `No Alpaca data for ${symbol}.`);
          if (value instanceof MarketProviderError) throw value;
          return accept(symbol, value, entry.value);
        });
      }));
    })();
    this.inFlight.set(key, work);
    try { await work; } finally { this.inFlight.delete(key); }
  }

  private async refresh<T>(key: string, entry: CacheEntry<T>, ttl: number, load: () => Promise<T>): Promise<void> {
    const pending = this.inFlight.get(key);
    if (pending) return pending;
    if (this.now() < entry.nextAttemptAt) return;
    if (this.now() < this.blockedUntil) {
      entry.issue = this.providerIssue;
      entry.nextAttemptAt = this.blockedUntil;
      return;
    }
    const work = this.load(entry, ttl, load);
    this.inFlight.set(key, work);
    try { await work; } finally { this.inFlight.delete(key); }
  }

  private async load<T>(entry: CacheEntry<T>, ttl: number, load: () => Promise<T>): Promise<void> {
    try {
      entry.value = await load();
      entry.fetchedAt = this.now();
      entry.issue = null;
      entry.nextAttemptAt = this.now() + ttl;
    } catch (error: unknown) {
      const failure = error instanceof MarketProviderError ? error :
        new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.');
      entry.issue = { code: failure.code, message: failure.message };
      entry.nextAttemptAt = this.now() + Math.max(REFRESH_INTERVAL_MS, failure.retryAfterMs);
      if (['PROVIDER_RATE_LIMITED', 'PROVIDER_AUTH_ERROR', 'MARKET_NOT_CONFIGURED'].includes(failure.code)) {
        this.blockedUntil = Math.max(this.blockedUntil, entry.nextAttemptAt);
        this.providerIssue = entry.issue;
      }
    }
  }

  private appendSample(symbol: string, point: PricePoint): void {
    let samples = this.rolling.get(symbol);
    if (!samples) { samples = new Map(); this.rolling.set(symbol, samples); }
    // Same trade timestamp is one observation, even during weekends/closed hours.
    samples.set(point.timestamp, point);
    const times = [...samples.keys()].sort((a, b) => Date.parse(a) - Date.parse(b));
    for (const time of times.slice(0, Math.max(0, times.length - MAX_ROLLING_POINTS))) samples.delete(time);
  }

  private historyResult(symbol: string): MarketHistory {
    const entry = this.histories.get(symbol);
    const combined = new Map<string, PricePoint>();
    for (const point of entry?.value ?? []) combined.set(point.timestamp, { ...point });
    for (const point of this.rolling.get(symbol)?.values() ?? []) combined.set(point.timestamp, { ...point });
    const points = [...combined.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
    return {
      symbol, feed: 'iex', points,
      dailyBarsUpdatedAt: entry?.fetchedAt != null ? new Date(entry.fetchedAt).toISOString() : null,
      status: entry?.issue ? (points.length ? 'stale' : 'unavailable') : (points.length ? 'ok' : 'unavailable'),
      retryAt: entry?.issue ? new Date(entry.nextAttemptAt).toISOString() : null,
      error: entry?.issue ?? null,
    };
  }
}

export const marketService = new MarketService();
