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

const byTime = (a: PricePoint, b: PricePoint) => Date.parse(a.timestamp) - Date.parse(b.timestamp);
const toIso = (ms: number | null | undefined) => (ms == null ? null : new Date(ms).toISOString());

export class MarketService {
  private readonly prices = new Map<string, CacheEntry<ProviderQuote>>();
  private readonly histories = new Map<string, CacheEntry<PricePoint[]>>();
  private readonly rolling = new Map<string, Map<string, PricePoint>>();
  private readonly inFlight = new Map<string, Promise<void>>();
  private blockedUntil = 0;
  private providerIssue: MarketIssue | null = null;

  constructor(
    private readonly provider: QuoteProvider = new AlpacaProvider(),
    // Resolve Date.now at call time so fake clocks installed after module import
    // can advance cache expiry in tests. Runtime behavior remains wall-clock time.
    private readonly now: () => number = () => Date.now(),
  ) {}

  async getStocks(): Promise<MarketStock[]> {
    return Promise.all(STOCK_CATALOG.map(stock => this.getStock(stock.ticker)));
  }

  async getStock(ticker: string): Promise<MarketStock> {
    const stock = STOCK_CATALOG.find(item => item.ticker === ticker);
    if (!stock) throw new Error('Unsupported market symbol');
    await Promise.all([this.ensurePrice(ticker), this.ensureHistory(ticker)]);
    const cached = this.prices.get(ticker);
    const quote = cached?.value;
    const issue = cached?.issue ?? null;
    const history = this.historyResult(ticker);
    return {
      ticker: stock.ticker, companyName: stock.companyName,
      currency: 'USD', isFavorite: stock.isFavorite,
      currentPrice: quote ? String(quote.price) : null,
      change: quote?.change != null ? String(quote.change) : null,
      percentageChange: quote?.percentageChange ?? null,
      performancePoints: history.points.map(point => point.price),
      historySource: history.points.length ? 'alpaca' : 'unavailable', history,
      source: 'alpaca', feed: 'iex',
      status: quote ? (issue ? 'stale' : 'ok') : 'unavailable',
      quoteAsOf: quote?.quoteAsOf ?? null,
      fetchedAt: toIso(cached?.fetchedAt),
      quoteAgeSeconds: quote ? Math.max(0, Math.floor((this.now() - Date.parse(quote.quoteAsOf)) / 1_000)) : null,
      retryAt: issue ? toIso(cached?.nextAttemptAt) : null,
      error: issue,
    };
  }

  async getHistory(ticker: string): Promise<MarketHistory> {
    if (!LIVE_SYMBOLS.includes(ticker)) throw new Error('Unsupported market symbol');
    await this.ensureHistory(ticker);
    return this.historyResult(ticker);
  }

  private acceptQuote(symbol: string, quote: ProviderQuote, previous: ProviderQuote | null): ProviderQuote {
    if (previous && Date.parse(quote.quoteAsOf) < Date.parse(previous.quoteAsOf)) {
      throw new MarketProviderError('PROVIDER_INVALID_DATA', 'Alpaca returned an older snapshot.');
    }
    this.appendSample(symbol, { timestamp: quote.quoteAsOf, price: quote.price });
    return quote;
  }

  private normalizeHistory(points: PricePoint[]): PricePoint[] {
    return [...points].sort(byTime).slice(-MAX_DAILY_POINTS);
  }

  private async ensurePrice(symbol: string): Promise<void> {
    const fetchQuotes = this.provider.fetchQuotes?.bind(this.provider);
    if (fetchQuotes) {
      return this.refreshBatch('prices', this.prices, REFRESH_INTERVAL_MS, fetchQuotes,
        (ticker, quote, previous) => this.acceptQuote(ticker, quote, previous));
    }
    const entry = this.entry(this.prices, symbol);
    await this.refresh(`price:${symbol}`, entry, REFRESH_INTERVAL_MS, async () =>
      this.acceptQuote(symbol, await this.provider.fetchQuote(symbol), entry.value));
  }

  private async ensureHistory(symbol: string): Promise<void> {
    const fetchHistories = this.provider.fetchHistories?.bind(this.provider);
    if (fetchHistories) {
      return this.refreshBatch('histories', this.histories, HISTORY_TTL_MS, fetchHistories,
        (_ticker, points) => this.normalizeHistory(points));
    }
    await this.refresh(`history:${symbol}`, this.entry(this.histories, symbol), HISTORY_TTL_MS, async () =>
      this.normalizeHistory(await this.provider.fetchHistory(symbol)));
  }

  private entry<T>(cache: Map<string, CacheEntry<T>>, symbol: string): CacheEntry<T> {
    let entry = cache.get(symbol);
    if (!entry) {
      entry = { value: null, fetchedAt: null, nextAttemptAt: 0, issue: null };
      cache.set(symbol, entry);
    }
    return entry;
  }

  /** True when the entry must not be refreshed yet (backoff, or a provider-wide block). */
  private isDeferred(entry: CacheEntry<unknown>): boolean {
    if (this.now() < entry.nextAttemptAt) return true;
    if (this.now() < this.blockedUntil) {
      entry.issue = this.providerIssue;
      entry.nextAttemptAt = this.blockedUntil;
      return true;
    }
    return false;
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
    const due: [string, CacheEntry<T>][] = [];
    for (const symbol of LIVE_SYMBOLS) {
      const entry = this.entry(cache, symbol);
      if (!this.isDeferred(entry)) due.push([symbol, entry]);
    }
    if (!due.length) return;
    const work = (async () => {
      let results: Map<string, T | MarketProviderError>;
      try {
        results = new Map(Object.entries(await fetch(due.map(([symbol]) => symbol))));
      } catch (error: unknown) {
        await Promise.all(due.map(([, entry]) => this.load(entry, ttl, async () => { throw error; })));
        return;
      }
      await Promise.all(due.map(([symbol, entry]) => this.load(entry, ttl, () => {
        const value = results.get(symbol);
        if (value === undefined) throw new MarketProviderError('PROVIDER_INVALID_DATA', `No Alpaca data for ${symbol}.`);
        if (value instanceof MarketProviderError) throw value;
        return Promise.resolve(accept(symbol, value, entry.value));
      })));
    })();
    this.inFlight.set(key, work);
    try { await work; } finally { this.inFlight.delete(key); }
  }

  private async refresh<T>(key: string, entry: CacheEntry<T>, ttl: number, load: () => Promise<T>): Promise<void> {
    const pending = this.inFlight.get(key);
    if (pending) return pending;
    if (this.isDeferred(entry)) return;
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
      const failure = error instanceof MarketProviderError
        ? error
        : new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.');
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
    const points = [...combined.values()].sort(byTime);
    return {
      symbol, feed: 'iex', points,
      dailyBarsUpdatedAt: toIso(entry?.fetchedAt),
      status: points.length ? (entry?.issue ? 'stale' : 'ok') : 'unavailable',
      retryAt: entry?.issue ? toIso(entry.nextAttemptAt) : null,
      error: entry?.issue ?? null,
    };
  }
}

export const marketService = new MarketService();
export { valuePortfolio, loadPrices, PriceUnavailableError, STARTING_BALANCE, valuePortfolio as computePortfolioValue } from './valuation';
export type { ValuationInput, HoldingValue, Valuation } from './valuation';
