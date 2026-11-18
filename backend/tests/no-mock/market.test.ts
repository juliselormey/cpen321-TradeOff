import request from 'supertest';
import { createApp } from '../../src/app';
import { SYMBOLS, T, UNSUPPORTED } from '../helpers/testkit';

const app = createApp();

// Live Alpaca (IEX). Consumes provider quota: run sparingly.
jest.setTimeout(30000);

beforeAll(() => {
  if (!process.env.ALPACA_API_KEY_ID || !process.env.ALPACA_API_SECRET_KEY) {
    throw new Error('Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY: this suite must fail, not skip silently');
  }
});

describe('Unmocked API: GET /api/market/stocks', () => {
  it('MKT-N1 returns six stocks with numeric prices > 0', async () => {
    const res = await request(app).get('/api/market/stocks');
    expect(res.status).toBe(200);
    expect(res.body.stocks).toHaveLength(6);
    for (const s of res.body.stocks) {
      expect(SYMBOLS).toContain(s.ticker);
      expect(typeof Number(s.currentPrice)).toBe('number');
      expect(Number(s.currentPrice)).toBeGreaterThan(0);
    }
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
