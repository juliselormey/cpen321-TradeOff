import express from 'express';
import request from 'supertest';
import { createMarketRouter } from '../../src/market/routes';
import { MarketService } from '../../src/market/service';
import { FAKE_DATE, HISTORY, SYMBOLS, T, UNSUPPORTED, expectError, makeQuotes } from '../helpers/testkit';
import { ProviderQuote, PricePoint } from '../../src/market/types';

const START = Date.parse('2026-10-23T15:00:00Z');
const QUOTE: ProviderQuote = {
  price: 100, change: 1, percentageChange: 1,
  quoteAsOf: new Date(START).toISOString(),
};

function fixture() {
  let clock = START;
  const fetchQuote = jest.fn<Promise<ProviderQuote>, [string]>().mockResolvedValue(QUOTE);
  const fetchHistory = jest.fn<Promise<PricePoint[]>, [string]>().mockResolvedValue(HISTORY);
  const fetchQuotes = jest.fn().mockImplementation(async (symbols: readonly string[]) =>
    Object.fromEntries(symbols.map(s => [s, QUOTE]))
  );
  const fetchHistories = jest.fn().mockImplementation(async (symbols: readonly string[]) =>
    Object.fromEntries(await Promise.all(symbols.map(async s => [s, await fetchHistory(s)])))
  );
  const service = new MarketService({ fetchQuote, fetchHistory, fetchQuotes, fetchHistories }, () => clock);
  const app = express();
  app.use('/api/market', createMarketRouter(service));
  return { service, app, fetchQuote, fetchHistory, fetchQuotes, fetchHistories, advance: (ms: number) => { clock += ms; }, now: () => clock };
}

let f: ReturnType<typeof fixture>;
let provider: any;
const expireCache = () => f.advance(8_000 + 1);

beforeEach(() => {
  jest.useFakeTimers(FAKE_DATE);
  f = fixture();
  provider = f;
});
afterEach(() => jest.useRealTimers());

const list = () => request(f.app).get('/api/market/stocks');
const one = (s: string) => request(f.app).get(`/api/market/stocks/${s}`);
const hist = (s: string) => request(f.app).get(`/api/market/stocks/${s}/history`);

describe('Mocked API: GET /api/market/stocks', () => {
  it('MKT-01 returns all 100 stocks with name, ticker, price, change', async () => {
    const res = await list();
    expect(res.status).toBe(200);
    expect(res.body.stocks).toHaveLength(100);
    for (const s of res.body.stocks) {
      expect(SYMBOLS).toContain(s.ticker);
      expect(s).toEqual(expect.objectContaining({ companyName: expect.any(String), currentPrice: '100', change: '1' }));
    }
  });

  it('MKT-02 second request within 8 s is served from cache', async () => {
    await list();
    await list();
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
  });

  it('MKT-03 request after 8 s refetches from the provider', async () => {
    await list();
    f.fetchQuotes.mockResolvedValue(Object.fromEntries(SYMBOLS.map(s => [s, { ...QUOTE, price: 110 }])));
    f.advance(8_000);
    const res = await list();
    expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
    expect(res.body.stocks[0].currentPrice).toBe('110');
  });

  it('MKT-04 expired cache + provider error → 503 (UC-06 2a)', async () => {
    await list();
    expireCache();
    provider.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    expectError(await list(), 503);
  });

  it('MKT-05 fresh cache + provider error → 200 from cache', async () => {
    await list();
    provider.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    const res = await list();
    expect(res.status).toBe(200);
    expect(provider.fetchQuotes).toHaveBeenCalledTimes(1);
  });

  it('MKT-06 cold cache + provider error → 503', async () => {
    provider.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    expectError(await list(), 503);
  });

  it('MKT-15 after a 503 the provider recovers → fresh data, no restart (NFR-3)', async () => {
    provider.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    expectError(await list(), 503);
    provider.fetchQuotes.mockResolvedValue(makeQuotes(120));
    expireCache(); // 8 s, well inside the 16 s bound
    const res = await list();
    expect(res.status).toBe(200);
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
  it('MKT-11 expired cache + provider error → 503', async () => {
    await one(T);
    expireCache();
    provider.fetchQuotes.mockRejectedValue(new Error('alpaca down'));
    expectError(await one(T), 503);
  });
});

describe('Mocked API: GET /api/market/stocks/:symbol/history', () => {
  it('MKT-12 supported symbol → points in ascending time', async () => {
    const res = await hist(T);
    expect(res.status).toBe(200);
    expect(res.body.points.length).toBeGreaterThan(0);
    const times = res.body.points.map((p: any) => Date.parse(p.timestamp));
    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect(res.body.points[0]).toEqual(expect.objectContaining({ timestamp: expect.any(String), price: expect.any(Number) }));
  });
  it('MKT-13 unsupported symbol → 404', async () => {
    expectError(await hist(UNSUPPORTED), 404);
  });
  it('MKT-14 provider error → 503', async () => {
    provider.fetchHistory.mockRejectedValue(new Error('alpaca down'));
    expectError(await hist(T), 503);
  });
});
