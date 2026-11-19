import express from 'express';
import request from 'supertest';
import { createMarketRouter } from '../../src/market/routes';
import { MarketService } from '../../src/market/service';
import { STOCK_CATALOG } from '../../src/market/catalog';
import { FAKE_DATE, HISTORY, T, UNSUPPORTED, expectError } from '../helpers/testkit';
import { ProviderQuote, PricePoint } from '../../src/market/types';

const START = Date.parse('2026-10-23T15:00:00Z');
const QUOTE: ProviderQuote = {
  price: 100, change: 1, percentageChange: 1,
  quoteAsOf: new Date(START).toISOString(),
};

const catalogTickers = STOCK_CATALOG.map(s => s.ticker);

function fixture() {
  let clock = START;
  const fetchQuote = jest.fn<Promise<ProviderQuote>, [string]>().mockResolvedValue(QUOTE);
  const fetchHistory = jest.fn<Promise<PricePoint[]>, [string]>().mockResolvedValue(HISTORY);
  const fetchQuotes = jest.fn().mockImplementation((symbols: readonly string[]) =>
    Promise.resolve(Object.fromEntries(symbols.map(s => [s, QUOTE])))
  );
  const fetchHistories = jest.fn().mockImplementation(async (symbols: readonly string[]) => {
    const entries = await Promise.all(
      symbols.map(async (s): Promise<[string, PricePoint[]]> => [s, await fetchHistory(s)])
    );
    return Object.fromEntries(entries);
  });
  const service = new MarketService({ fetchQuote, fetchHistory, fetchQuotes, fetchHistories }, () => clock);
  const app = express();
  app.use('/api/market', createMarketRouter(service));
  return { service, app, fetchQuote, fetchHistory, fetchQuotes, fetchHistories, advance: (ms: number) => { clock += ms; }, now: () => clock };
}

let f: ReturnType<typeof fixture>;
const expireCache = () => { f.advance(8_000 + 1); };

beforeEach(() => {
  jest.useFakeTimers(FAKE_DATE);
  f = fixture();
});
afterEach(() => jest.useRealTimers());

const list = () => request(f.app).get('/api/market/stocks');
const one = (s: string) => request(f.app).get(`/api/market/stocks/${s}`);
const hist = (s: string) => request(f.app).get(`/api/market/stocks/${s}/history`);

describe('Mocked API: GET /api/market/stocks', () => {
  it('MKT-01 returns all catalog stocks with name, ticker, price, change', async () => {
    const res = await list();
    expect(res.status).toBe(200);
    expect(res.body.stocks.length).toBe(100);
    for (const s of res.body.stocks) {
      expect(catalogTickers).toContain(s.ticker);
      expect(s).toEqual(expect.objectContaining({ companyName: expect.any(String) }));
    }
  });

  it('MKT-02 second request within 8 s is served from cache', async () => {
    await list();
    await list();
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
  });

  it('MKT-03 request after 8 s refetches from the provider', async () => {
    await list();
    f.fetchQuotes.mockResolvedValue(Object.fromEntries(catalogTickers.map(s => [s, { ...QUOTE, price: 110 }])));
    f.advance(8_000);
    const res = await list();
    expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
    expect(res.body.stocks[0].currentPrice).toBe('110');
  });

  it('MKT-04 expired cache + provider error → 200 with stale status', async () => {
    await list();
    expireCache();
    f.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const res = await list();
    expect(res.status).toBe(200);
    expect(res.body.stocks.every((s: { status: string }) => s.status === 'stale')).toBe(true);
  });

  it('MKT-05 fresh cache + provider error → 200 from cache', async () => {
    await list();
    f.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const res = await list();
    expect(res.status).toBe(200);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
  });

  it('MKT-06 cold cache + provider error → 200 with unavailable status', async () => {
    f.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const res = await list();
    expect(res.status).toBe(200);
    expect(res.body.stocks.every((s: { status: string; currentPrice: unknown }) => s.status === 'unavailable' && s.currentPrice === null)).toBe(true);
  });

  it('MKT-15 after a failure the provider recovers → fresh data, no restart (NFR-3)', async () => {
    f.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const failedRes = await list();
    expect(failedRes.status).toBe(200);
    expect(failedRes.body.stocks[0].status).toBe('unavailable');

    f.fetchQuotes.mockResolvedValue(Object.fromEntries(catalogTickers.map(s => [s, { ...QUOTE, price: 120 }])));
    expireCache(); // 8 s, well inside the 16 s bound
    const res = await list();
    expect(res.status).toBe(200);
    expect(res.body.stocks[0].status).toBe('ok');
    expect(res.body.stocks[0].currentPrice).toBe('120');
  });
});

describe('Mocked API: GET /api/market/stocks/:symbol', () => {
  it('MKT-08 supported symbol → quote', async () => {
    const res = await one(T);
    expect(res.status).toBe(200);
    expect(res.body).toEqual(expect.objectContaining({ ticker: T, companyName: expect.any(String), currentPrice: '100', quoteAsOf: expect.any(String) }));
  });
  it('MKT-10 unsupported symbol → 404 and provider not called', async () => {
    expectError(await one(UNSUPPORTED), 404);
    expect(f.fetchQuotes).not.toHaveBeenCalled();
    expect(f.fetchQuote).not.toHaveBeenCalled();
  });
  it('MKT-11 expired cache + provider error → 200 with stale status', async () => {
    await one(T);
    expireCache();
    f.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const res = await one(T);
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('stale');
  });
});

describe('Mocked API: GET /api/market/stocks/:symbol/history', () => {
  it('MKT-12 supported symbol → points in ascending time', async () => {
    const res = await hist(T);
    expect(res.status).toBe(200);
    expect(res.body.points.length).toBeGreaterThan(0);
    const times = res.body.points.map((p: { timestamp: string }) => Date.parse(p.timestamp));
    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect(res.body.points[0]).toEqual(expect.objectContaining({ timestamp: expect.any(String), price: expect.any(Number) }));
  });
  it('MKT-13 unsupported symbol → 404', async () => {
    expectError(await hist(UNSUPPORTED), 404);
  });
  it('MKT-14 provider error without cached points → 503 with unavailable status', async () => {
    f.fetchHistory.mockRejectedValue(new Error('alpaca down'));
    const res = await hist(T);
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unavailable');
  });
});