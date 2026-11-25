import express from 'express';
import request from 'supertest';
import axios, { AxiosError } from 'axios';
import { createMarketRouter } from '../../src/market/routes';
import { MarketService } from '../../src/market/service';
import { AlpacaProvider, parseAlpacaSnapshot, parseAlpacaBars } from '../../src/market/alpaca';
import { HISTORY_TTL_MS, LIVE_SYMBOLS, MAX_DAILY_POINTS, MAX_ROLLING_POINTS } from '../../src/market/catalog';
import { MarketProviderError, PricePoint, ProviderQuote } from '../../src/market/types';

const START = Date.parse('2026-10-23T15:00:00Z');
const QUOTE: ProviderQuote = {
  price: 200.25, change: 2.25, percentageChange: 2.25 / 198 * 100,
  quoteAsOf: new Date(START).toISOString(),
};
const HISTORY: PricePoint[] = [
  { timestamp: '2026-10-21T04:00:00.000Z', price: 197 },
  { timestamp: '2026-10-22T04:00:00.000Z', price: 198 },
];
const SNAPSHOT = {
  latestTrade: { p: 200.25, t: QUOTE.quoteAsOf },
  prevDailyBar: { c: 198 },
};

function fixture() {
  let clock = START;
  const fetchQuote = jest.fn<Promise<ProviderQuote>, [string]>().mockResolvedValue(QUOTE);
  const fetchHistory = jest.fn<Promise<PricePoint[]>, [string]>().mockResolvedValue(HISTORY);
  const service = new MarketService({ fetchQuote, fetchHistory }, () => clock);
  const app = express();
  app.use('/api/market', createMarketRouter(service));
  return { app, service, fetchQuote, fetchHistory, advance: (ms: number) => { clock += ms; }, now: () => clock };
}

// Market HTTP interfaces, with the external Alpaca component mocked.
// No credentials, network quota, database, or trade operations are used.
describe('Mocked: market endpoints and caches', () => {
  test('list supplies provider quotes and history for all 100 unique catalog stocks', async () => {
    const { app, fetchQuote, fetchHistory } = fixture();
    const response = await request(app).get('/api/market/stocks').expect(200);
    expect(response.body.refreshIntervalMs).toBe(8_000);
    expect(response.body.stocks).toHaveLength(100);
    expect(response.body.stocks[0]).toMatchObject({
      ticker: 'AAPL', source: 'alpaca', feed: 'iex', status: 'ok', currentPrice: '200.25',
      change: '2.25', historySource: 'alpaca', quoteAgeSeconds: 0,
    });
    expect(response.body.stocks[0].history.points).toHaveLength(3);
    expect(response.body.liveSymbols).toEqual(LIVE_SYMBOLS);
    expect(new Set(LIVE_SYMBOLS).size).toBe(100);
    expect(response.body.stocks.every((s: { source: string }) => s.source === 'alpaca')).toBe(true);
    expect(response.body.stocks[1]).toMatchObject({
      ticker: 'MSFT', currentPrice: '200.25', history: { symbol: 'MSFT', status: 'ok' },
    });
    expect(fetchQuote).toHaveBeenCalledTimes(LIVE_SYMBOLS.length);
    expect(fetchHistory).toHaveBeenCalledTimes(LIVE_SYMBOLS.length);
    expect(fetchQuote).toHaveBeenCalledWith('AAPL');
    expect(fetchHistory).toHaveBeenCalledWith('AAPL');
  });

  test('concurrent list and history requests share both in-flight loads', async () => {
    const { app, service, fetchQuote, fetchHistory } = fixture();
    let resolveQuote: ((quote: ProviderQuote) => void) | undefined;
    let resolveHistory: ((points: PricePoint[]) => void) | undefined;
    fetchQuote.mockImplementation(() => new Promise(resolve => { resolveQuote = resolve; }));
    fetchHistory.mockImplementation(() => new Promise(resolve => { resolveHistory = resolve; }));
    const first = service.getStock('AAPL');
    const second = service.getStock('AAPL');
    const chart = service.getHistory('AAPL');
    expect(fetchQuote).toHaveBeenCalledTimes(1);
    expect(fetchHistory).toHaveBeenCalledTimes(1);
    resolveQuote?.(QUOTE);
    resolveHistory?.(HISTORY);
    await Promise.all([first, second, chart]);
    fetchQuote.mockResolvedValue(QUOTE);
    fetchHistory.mockResolvedValue(HISTORY);
    await Promise.all(Array.from({ length: 10 }, async () => {
      await request(app).get('/api/market/stocks').expect(200);
    }));
    expect(fetchQuote).toHaveBeenCalledTimes(LIVE_SYMBOLS.length);
    expect(fetchHistory).toHaveBeenCalledTimes(LIVE_SYMBOLS.length);
  });

  test('price expires at eight seconds while daily history stays cached independently', async () => {
    const { service, fetchQuote, fetchHistory, advance } = fixture();
    await service.getStock('AAPL');
    advance(7_999);
    await service.getStock('AAPL');
    expect(fetchQuote).toHaveBeenCalledTimes(1);
    advance(1);
    await service.getStock('AAPL');
    expect(fetchQuote).toHaveBeenCalledTimes(2);
    expect(fetchHistory).toHaveBeenCalledTimes(1);
    advance(HISTORY_TTL_MS - 8_000);
    await service.getHistory('AAPL');
    expect(fetchHistory).toHaveBeenCalledTimes(2);
    expect(fetchQuote).toHaveBeenCalledTimes(2);
  });

  test('idle time alone never polls either external endpoint', async () => {
    const { service, fetchQuote, fetchHistory, advance } = fixture();
    await service.getStock('AAPL');
    advance(86_400_000);
    expect(fetchQuote).toHaveBeenCalledTimes(1);
    expect(fetchHistory).toHaveBeenCalledTimes(1);
  });

  test('unknown symbols consume no provider calls; MSFT supports quote and history', async () => {
    const { app, fetchQuote, fetchHistory } = fixture();
    await request(app).get('/api/market/stocks/INVALID').expect(404);
    await request(app).get('/api/market/stocks/INVALID/history').expect(404);
    expect(fetchQuote).not.toHaveBeenCalled();
    expect(fetchHistory).not.toHaveBeenCalled();
    const stock = await request(app).get('/api/market/stocks/msft').expect(200);
    expect(stock.body).toMatchObject({ source: 'alpaca', status: 'ok', ticker: 'MSFT' });
    await request(app).get('/api/market/stocks/MSFT/history').expect(200);
    expect(fetchHistory).toHaveBeenCalledTimes(1);
  });

  test('unconfigured provider returns explicit 503 without replacing AAPL by a sample', async () => {
    const { app, fetchQuote, fetchHistory } = fixture();
    const error = new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
    fetchQuote.mockRejectedValue(error);
    fetchHistory.mockRejectedValue(error);
    const response = await request(app).get('/api/market/stocks/AAPL').expect(503);
    expect(response.body).toMatchObject({
      currentPrice: null, status: 'unavailable', source: 'alpaca',
      error: { code: 'MARKET_NOT_CONFIGURED' }, history: { points: [] },
    });
    const list = await request(app).get('/api/market/stocks').expect(200);
    expect(list.body.stocks.every((s: { status: string; currentPrice: unknown }) => s.status === 'unavailable' && s.currentPrice === null)).toBe(true);
    await request(app).get('/api/market/stocks/AAPL/history').expect(503);
    expect(fetchQuote).toHaveBeenCalledTimes(1);
    expect(fetchHistory).toHaveBeenCalledTimes(1);
  });

  test('price rate-limit retains last success, honors cooldown, then recovers', async () => {
    const { app, fetchQuote, advance } = fixture();
    await request(app).get('/api/market/stocks/AAPL').expect(200);
    advance(8_000);
    fetchQuote.mockRejectedValue(new MarketProviderError('PROVIDER_RATE_LIMITED', 'Alpaca rate limit reached.', 60_000));
    const response = await request(app).get('/api/market/stocks/AAPL').expect(200);
    expect(response.body).toMatchObject({
      currentPrice: '200.25', status: 'stale', fetchedAt: new Date(START).toISOString(),
      error: { code: 'PROVIDER_RATE_LIMITED' },
    });
    advance(59_999);
    await request(app).get('/api/market/stocks/AAPL').expect(200);
    expect(fetchQuote).toHaveBeenCalledTimes(2);
    advance(1);
    fetchQuote.mockResolvedValue({ ...QUOTE, price: 201 });
    const recovered = await request(app).get('/api/market/stocks/AAPL').expect(200);
    expect(recovered.body).toMatchObject({ status: 'ok', currentPrice: '201', error: null });
  });

  test('shared provider cooldown also blocks a due history retry', async () => {
    const { service, fetchQuote, fetchHistory, advance } = fixture();
    fetchQuote.mockRejectedValue(new MarketProviderError('PROVIDER_RATE_LIMITED', 'Limit', 60_000));
    fetchHistory.mockRejectedValue(new Error('timeout'));
    await service.getStock('AAPL');
    advance(30_000);
    const history = await service.getHistory('AAPL');
    expect(history.error?.code).toBe('PROVIDER_RATE_LIMITED');
    expect(fetchHistory).toHaveBeenCalledTimes(1);
  });

  test('history outage retains daily data without invalidating a successful price', async () => {
    const { service, fetchHistory, advance } = fixture();
    await service.getStock('AAPL');
    advance(HISTORY_TTL_MS);
    fetchHistory.mockRejectedValue(new Error('private-credentials-not-for-clients'));
    const stock = await service.getStock('AAPL');
    expect(stock.status).toBe('ok');
    expect(stock.history?.status).toBe('stale');
    expect(stock.history?.points).toHaveLength(3);
    expect(JSON.stringify(stock)).not.toContain('private-credentials');
    await service.getHistory('AAPL');
    expect(fetchHistory).toHaveBeenCalledTimes(2);
  });

  test('empty daily history is cached; one real sample does not fabricate a chart', async () => {
    const { service, fetchHistory } = fixture();
    fetchHistory.mockResolvedValue([]);
    const stock = await service.getStock('AAPL');
    expect(stock.history?.points).toEqual([{ timestamp: QUOTE.quoteAsOf, price: QUOTE.price }]);
    await service.getHistory('AAPL');
    expect(fetchHistory).toHaveBeenCalledTimes(1);
  });

  test('closed-market repeated timestamps are deduplicated and never made into new movement', async () => {
    const { service, fetchQuote, advance } = fixture();
    for (let index = 0; index < 10; index++) {
      await service.getStock('AAPL');
      advance(8_000);
    }
    const history = await service.getHistory('AAPL');
    expect(fetchQuote).toHaveBeenCalledTimes(10);
    expect(history.points).toHaveLength(3);
    expect(history.points[2]?.timestamp).toBe(QUOTE.quoteAsOf);
  });

  test('bounded rolling buffer retains newest real samples and chronological order', async () => {
    const { service, fetchQuote, fetchHistory, advance, now } = fixture();
    fetchHistory.mockResolvedValue(Array.from({ length: 50 }, (_, i) => ({
      timestamp: new Date(START - (50 - i) * 86_400_000).toISOString(), price: 150 + i,
    })));
    fetchQuote.mockImplementation(() => Promise.resolve({ ...QUOTE, quoteAsOf: new Date(now()).toISOString() }));
    for (let index = 0; index < MAX_ROLLING_POINTS + 10; index++) {
      await service.getStock('AAPL');
      advance(8_000);
    }
    const history = await service.getHistory('AAPL');
    expect(history.points).toHaveLength(MAX_DAILY_POINTS + MAX_ROLLING_POINTS);
    expect(history.points.every((p, i) => i === 0 || Date.parse(p.timestamp) > Date.parse(history.points[i - 1]?.timestamp ?? ''))).toBe(true);
    expect(history.points.at(-1)?.timestamp).toBe(new Date(now() - 8_000).toISOString());
  });

  test('out-of-order snapshot cannot regress the current price', async () => {
    const { service, fetchQuote, advance } = fixture();
    await service.getStock('AAPL');
    advance(8_000);
    fetchQuote.mockResolvedValue({ ...QUOTE, price: 190, quoteAsOf: HISTORY[0]?.timestamp ?? '' });
    const stock = await service.getStock('AAPL');
    expect(stock.currentPrice).toBe('200.25');
    expect(stock.error?.code).toBe('PROVIDER_INVALID_DATA');
  });

  test('HTTP responses disable caching and unknown errors never expose request credentials', async () => {
    const { app, fetchQuote } = fixture();
    fetchQuote.mockRejectedValue(new Error('private-secret'));
    const response = await request(app).get('/api/market/stocks/AAPL').expect(503);
    expect(response.text).not.toContain('private-secret');
    expect(response.headers['cache-control']).toBe('no-store');
  });

  test('an unexpected price error on a cold cache is reported as temporarily unavailable', async () => {
    const { service, fetchQuote } = fixture();
    fetchQuote.mockRejectedValue(new Error('socket hang up'));
    const stock = await service.getStock('AAPL');
    expect(stock).toMatchObject({ currentPrice: null, status: 'unavailable', error: { code: 'PROVIDER_UNAVAILABLE' } });
    expect(typeof stock.retryAt).toBe('string');
  });

  test('a rate limit on one symbol pauses the other symbols until it expires', async () => {
    const { service, fetchQuote, advance } = fixture();
    fetchQuote.mockRejectedValueOnce(new MarketProviderError('PROVIDER_RATE_LIMITED', 'Limit', 60_000));
    await service.getStock('AAPL');
    const blocked = await service.getStock('MSFT');
    expect(blocked.error?.code).toBe('PROVIDER_RATE_LIMITED');
    expect(fetchQuote).toHaveBeenCalledTimes(1);
    advance(60_001);
    expect((await service.getStock('MSFT')).status).toBe('ok');
    expect(fetchQuote).toHaveBeenLastCalledWith('MSFT');
  });

  test('a cold history failure is reported with a retry time', async () => {
    const { service, fetchHistory } = fixture();
    fetchHistory.mockRejectedValue(new MarketProviderError('PROVIDER_UNAVAILABLE', 'down', 8_000));
    const history = await service.getHistory('AAPL');
    expect(history).toMatchObject({ status: 'unavailable', points: [], error: { code: 'PROVIDER_UNAVAILABLE' } });
    expect(typeof history.retryAt).toBe('string');
  });

  test('unsupported symbols are rejected without touching the provider', async () => {
    const { service, fetchQuote, fetchHistory } = fixture();
    await expect(service.getStock('NOPE')).rejects.toThrow('Unsupported market symbol');
    await expect(service.getHistory('NOPE')).rejects.toThrow('Unsupported market symbol');
    expect(fetchQuote).not.toHaveBeenCalled();
    expect(fetchHistory).not.toHaveBeenCalled();
  });
});

describe('Mocked: Alpaca IEX HTTP adapter', () => {
  afterEach(() => jest.restoreAllMocks());
  const credentials = () => ({ key: 'test-id', secret: 'test-secret' });

  test('both credentials are required before any HTTP call', async () => {
    const get = jest.spyOn(axios, 'get');
    await expect(new AlpacaProvider(() => ({ key: 'id', secret: '' })).fetchQuote('AAPL'))
      .rejects.toMatchObject({ code: 'MARKET_NOT_CONFIGURED' });
    expect(get).not.toHaveBeenCalled();
  });

  test('snapshot requests enforce IEX and normalize price/dollar/percentage changes', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: SNAPSHOT });
    // Snapshot percentages are normalized to four decimals by the API adapter.
    await expect(new AlpacaProvider(credentials).fetchQuote('AAPL')).resolves.toEqual({
      ...QUOTE, percentageChange: 1.1364,
    });
    expect(get).toHaveBeenCalledWith('https://data.alpaca.markets/v2/stocks/AAPL/snapshot', expect.objectContaining({
      params: { feed: 'iex' },
      headers: { 'APCA-API-KEY-ID': 'test-id', 'APCA-API-SECRET-KEY': 'test-secret' },
      timeout: 4_000, maxRedirects: 0,
    }));
  });

  test('history uses bounded daily range, latest-first limit, and raw IEX prices', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: { bars: [
      { t: HISTORY[1]?.timestamp, c: 198 }, { t: HISTORY[0]?.timestamp, c: 197 },
    ], next_page_token: 'intentionally-unneeded-older-bars' } });
    await expect(new AlpacaProvider(credentials, () => START).fetchHistory('AAPL')).resolves.toEqual(HISTORY);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('https://data.alpaca.markets/v2/stocks/AAPL/bars', expect.objectContaining({
      params: {
        timeframe: '1Day', feed: 'iex', start: '2026-09-08T00:00:00.000Z',
        end: '2026-10-23T00:00:00.000Z', limit: 30, sort: 'desc', adjustment: 'raw',
      },
    }));
  });

  test('snapshot can use a real minute/daily bar when no trade exists; missing prior close stays null', () => {
    expect(parseAlpacaSnapshot({ minuteBar: { t: QUOTE.quoteAsOf, c: 200 } })).toEqual({
      price: 200, change: null, percentageChange: null, quoteAsOf: QUOTE.quoteAsOf,
    });
    expect(parseAlpacaSnapshot({ dailyBar: { t: QUOTE.quoteAsOf, c: 199 } }).price).toBe(199);
  });

  test.each([null, {}, { latestTrade: { p: 0, t: QUOTE.quoteAsOf } },
    { latestTrade: { p: Infinity, t: QUOTE.quoteAsOf } }, { latestTrade: { p: 100, t: 'invalid' } }])(
    'rejects snapshot with no valid price: %p', data => {
      expect(() => parseAlpacaSnapshot(data)).toThrow(MarketProviderError);
    },
  );

  test('historical bars deduplicate by time, sort, and permit a genuine empty response', () => {
    expect(parseAlpacaBars({ bars: null })).toEqual([]);
    expect(parseAlpacaBars({ bars: [] })).toEqual([]);
    expect(parseAlpacaBars({ bars: [
      { t: HISTORY[1]?.timestamp, c: 198 }, { t: HISTORY[0]?.timestamp, c: 197 },
      { t: HISTORY[1]?.timestamp, c: 198 },
    ] })).toEqual(HISTORY);
  });

  test.each([{}, { bars: 'bad' }, { bars: [{ t: 'bad', c: 100 }] }, { bars: [{ t: QUOTE.quoteAsOf, c: -1 }] }])(
    'rejects malformed history rather than inventing chart points: %p', data => {
      expect(() => parseAlpacaBars(data)).toThrow(MarketProviderError);
    },
  );

  test.each([
    [401, 'PROVIDER_AUTH_ERROR', 60_000], [403, 'PROVIDER_AUTH_ERROR', 60_000],
    [429, 'PROVIDER_RATE_LIMITED', 120_000], [500, 'PROVIDER_UNAVAILABLE', 8_000],
  ])('HTTP %s maps to a sanitized failure/cooldown', async (status, code, retryAfterMs) => {
    jest.spyOn(axios, 'get').mockRejectedValue({
      isAxiosError: true, response: { status, headers: { 'retry-after': '120' } },
      config: { headers: { 'APCA-API-SECRET-KEY': 'private-secret' } },
    });
    await expect(new AlpacaProvider(credentials).fetchQuote('AAPL')).rejects.toMatchObject({ code, retryAfterMs });
  });

  test('network failure is sanitized', async () => {
    jest.spyOn(axios, 'get').mockRejectedValue(new Error('secret-http-config'));
    await expect(new AlpacaProvider(credentials).fetchQuote('AAPL')).rejects.toMatchObject({
      code: 'PROVIDER_UNAVAILABLE', message: 'Alpaca market data is temporarily unavailable.',
    });
  });
  const httpError = (status: number, headers: Record<string, string> = {}) =>
    new AxiosError('http', 'ERR_BAD_RESPONSE', undefined, undefined,
      { status, statusText: '', headers, data: {}, config: {} } as never);

  test.each<[string, Record<string, string>, number]>([
    ['no hint', {}, 60_000],
    ['retry-after seconds', { 'retry-after': '120' }, 120_000],
    ['retry-after below the minimum', { 'retry-after': '5' }, 60_000],
    ['retry-after HTTP date', { 'retry-after': new Date(START + 300_000).toUTCString() }, 300_000],
    ['rate-limit reset epoch', { 'x-ratelimit-reset': String(START / 1000 + 200) }, 200_000],
    ['unparseable hints', { 'retry-after': 'soon', 'x-ratelimit-reset': 'later' }, 60_000],
  ])('HTTP 429 with %s', async (_name, headers, expected) => {
    jest.spyOn(Date, 'now').mockReturnValue(START);
    jest.spyOn(axios, 'get').mockRejectedValue(httpError(429, headers));
    await expect(new AlpacaProvider(credentials).fetchQuote('AAPL')).rejects.toMatchObject({
      code: 'PROVIDER_RATE_LIMITED', retryAfterMs: expected,
    });
  });

  test('an HTTP error without a response is temporarily unavailable', async () => {
    jest.spyOn(axios, 'get').mockRejectedValue(new AxiosError('timeout', 'ECONNABORTED'));
    await expect(new AlpacaProvider(credentials).fetchQuote('AAPL')).rejects.toMatchObject({ code: 'PROVIDER_UNAVAILABLE' });
  });

  test('credentials default to the trimmed environment variables', async () => {
    const saved = { id: process.env.ALPACA_API_KEY_ID, secret: process.env.ALPACA_API_SECRET_KEY };
    try {
      delete process.env.ALPACA_API_KEY_ID;
      delete process.env.ALPACA_API_SECRET_KEY;
      await expect(new AlpacaProvider().fetchQuote('AAPL')).rejects.toMatchObject({ code: 'MARKET_NOT_CONFIGURED' });
      process.env.ALPACA_API_KEY_ID = ' key ';
      process.env.ALPACA_API_SECRET_KEY = ' sec ';
      const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: SNAPSHOT });
      await new AlpacaProvider().fetchQuote('AAPL');
      expect(get).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
        headers: { 'APCA-API-KEY-ID': 'key', 'APCA-API-SECRET-KEY': 'sec' },
      }));
    } finally {
      if (saved.id === undefined) delete process.env.ALPACA_API_KEY_ID; else process.env.ALPACA_API_KEY_ID = saved.id;
      if (saved.secret === undefined) delete process.env.ALPACA_API_SECRET_KEY; else process.env.ALPACA_API_SECRET_KEY = saved.secret;
    }
  });

  test('historical bars keep only the latest 30 after sorting', () => {
    const day = (i: number) => new Date(Date.UTC(2026, 9, 1 + i)).toISOString();
    const bars = Array.from({ length: 35 }, (_, i) => ({ t: day(34 - i), c: 100 + i }));
    const points = parseAlpacaBars({ bars });
    expect(points).toHaveLength(30);
    expect(points[0]?.timestamp).toBe(day(5));
    expect(points[29]?.timestamp).toBe(day(34));
  });
});