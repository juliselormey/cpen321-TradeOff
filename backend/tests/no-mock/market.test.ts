import request from 'supertest';
import { createApp } from '../../src/app';
import { T, UNSUPPORTED } from '../helpers/testkit';
import { STOCK_CATALOG } from '../../src/market/catalog';

const app = createApp();

// Live Alpaca (IEX). Consumes provider quota: run sparingly.
jest.setTimeout(30000);

beforeAll(() => {
  if (!process.env.ALPACA_API_KEY_ID || !process.env.ALPACA_API_SECRET_KEY) {
    throw new Error('Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY: this suite must fail, not skip silently');
  }
});

describe('Unmocked API: GET /api/market/stocks', () => {
  it('MKT-N1 returns every catalog stock, and live quotes have prices > 0', async () => {
    const res = await request(app).get('/api/market/stocks');
    expect(res.status).toBe(200);
    const catalog = STOCK_CATALOG.map((c) => c.ticker);
    expect(res.body.stocks).toHaveLength(catalog.length);
    for (const s of res.body.stocks) expect(catalog).toContain(s.ticker);
    const priced = res.body.stocks.filter((s: { currentPrice: string | null }) => Number(s.currentPrice) > 0);
    expect(priced.length).toBeGreaterThan(0);
    expect(res.body.stocks.find((s: { ticker: string }) => s.ticker === T).currentPrice).not.toBeNull();
  });
});

describe('Unmocked API: GET /api/market/stocks/:symbol', () => {
  it('MKT-N2 supported symbol → price > 0', async () => {
    const res = await request(app).get(`/api/market/stocks/${T}`);
    expect(res.status).toBe(200);
    expect(Number(res.body.currentPrice)).toBeGreaterThan(0);
  });
  it('MKT-N4 unsupported symbol → 404', async () => {
    expect((await request(app).get(`/api/market/stocks/${UNSUPPORTED}`)).status).toBe(404);
  });
});

describe('Unmocked API: GET /api/market/stocks/:symbol/history', () => {
  it('MKT-N3 supported symbol → at least one point', async () => {
    const res = await request(app).get(`/api/market/stocks/${T}/history`);
    expect(res.status).toBe(200);
    expect(res.body.points.length).toBeGreaterThanOrEqual(1);
  });
});

describe('Unmocked API: market response contract', () => {
  it('MKT-N5 the list reports the live symbols and the refresh interval, uncached', async () => {
    const res = await request(app).get('/api/market/stocks');
    expect(res.body.liveSymbols).toContain(T);
    expect(res.body.refreshIntervalMs).toBeGreaterThan(0);
    expect(res.headers['cache-control']).toBe('no-store');
  });
  it('MKT-N6 symbols are case-insensitive and the detail carries source, feed and history', async () => {
    const res = await request(app).get(`/api/market/stocks/${T.toLowerCase()}`);
    expect(res.status).toBe(200);
    expect(res.body).toEqual(expect.objectContaining({ ticker: T, source: 'alpaca', feed: 'iex', currency: 'USD' }));
    expect(res.body.history.symbol).toBe(T);
  });
  it('MKT-N7 repeated requests inside the refresh window are served from the cache', async () => {
    const first = (await request(app).get(`/api/market/stocks/${T}`)).body;
    const second = (await request(app).get(`/api/market/stocks/${T}`)).body;
    expect(second.fetchedAt).toBe(first.fetchedAt);
    expect(second.quoteAsOf).toBe(first.quoteAsOf);
  });
  it('MKT-N8 history points are positive and in ascending time order', async () => {
    const { points } = (await request(app).get(`/api/market/stocks/${T}/history`)).body;
    const times = points.map((p: { timestamp: string }) => Date.parse(p.timestamp));
    expect(times).toEqual([...times].sort((a: number, b: number) => a - b));
    for (const p of points) expect(p.price).toBeGreaterThan(0);
  });
  it('MKT-N9 an unsupported symbol is rejected on every market route with a code', async () => {
    for (const path of [`/api/market/stocks/${UNSUPPORTED}`, `/api/market/stocks/${UNSUPPORTED}/history`]) {
      const res = await request(app).get(path);
      expect(res.status).toBe(404);
      expect(res.body.code).toBe('UNKNOWN_SYMBOL');
    }
  });
});