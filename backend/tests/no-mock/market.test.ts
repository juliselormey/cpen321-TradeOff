import 'dotenv/config';
import express from 'express';
import request from 'supertest';
import { createMarketRouter } from '../../src/market/routes';
import { LIVE_SYMBOLS } from '../../src/market/catalog';

jest.setTimeout(30_000);

const app = express();
app.use('/api/market', createMarketRouter());

function requireAlpacaCredentials() {
  if (!process.env.ALPACA_API_KEY_ID?.trim() || !process.env.ALPACA_API_SECRET_KEY?.trim()) {
    throw new Error('Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY in backend/.env to run no-mock Market API tests.');
  }
}

describe('Unmocked API: GET /api/market/stocks', () => {
  beforeAll(requireAlpacaCredentials);

  test('Input: GET with no parameters; Output: live catalog quotes and cache metadata (200)', async () => {
    const response = await request(app).get('/api/market/stocks').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body.liveSymbols).toEqual(LIVE_SYMBOLS);
    expect(response.body.refreshIntervalMs).toBe(8_000);
    expect(response.body.stocks).toHaveLength(LIVE_SYMBOLS.length);
    for (const stock of response.body.stocks) {
      expect(stock).toMatchObject({ source: 'alpaca', feed: 'iex' });
      expect(['ok', 'stale']).toContain(stock.status);
      expect(Number(stock.currentPrice)).toBeGreaterThan(0);
      expect(Array.isArray(stock.history.points)).toBe(true);
      expect(['ok', 'stale']).toContain(stock.history.status);
      expect(stock.quoteAsOf).toEqual(expect.any(String));
      expect(stock.fetchedAt).toEqual(expect.any(String));
    }
  });
});

describe('Unmocked API: GET /api/market/stocks/:symbol', () => {
  beforeAll(requireAlpacaCredentials);

  test('Input: supported live ticker; Output: real Alpaca quote and company metadata (200)', async () => {
    const response = await request(app).get('/api/market/stocks/AAPL').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toMatchObject({
      ticker: 'AAPL', companyName: 'Apple Inc.', currency: 'USD', source: 'alpaca', feed: 'iex',
    });
    expect(['ok', 'stale']).toContain(response.body.status);
    expect(Number(response.body.currentPrice)).toBeGreaterThan(0);
    expect(response.body.quoteAsOf).toEqual(expect.any(String));
  });

  test('Input: unsupported ticker; Output: UNKNOWN_SYMBOL (404)', async () => {
    const response = await request(app).get('/api/market/stocks/NOTREAL').expect(404);
    expect(response.body.error).toMatchObject({ code: 'UNKNOWN_SYMBOL' });
  });
});

describe('Unmocked API: GET /api/market/stocks/:symbol/history', () => {
  beforeAll(requireAlpacaCredentials);

  test('Input: live ticker; Output: real historical IEX points (200)', async () => {
    const response = await request(app).get('/api/market/stocks/AAPL/history').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toMatchObject({ symbol: 'AAPL', feed: 'iex', status: 'ok' });
    expect(response.body.points.length).toBeGreaterThan(0);
    for (const point of response.body.points) {
      expect(Number(point.price)).toBeGreaterThan(0);
      expect(Number.isFinite(Date.parse(point.timestamp))).toBe(true);
    }
  });

  test('Input: unsupported ticker; Output: UNKNOWN_SYMBOL (404)', async () => {
    const response = await request(app).get('/api/market/stocks/NOTREAL/history').expect(404);
    expect(response.body.error).toMatchObject({ code: 'UNKNOWN_SYMBOL' });
  });
});
