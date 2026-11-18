import request from 'supertest';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache } from '../helpers/testkit';
import { createUser, db, getCash, giveShares, resetDb } from '../helpers/fixtures';

const mockMarket = { price: 100, fail: false };
jest.mock('../../src/market/alpaca', () =>
  require('../helpers/stubprovider').createStubProvider(mockMarket));

let app: any, A: string, B: string;

beforeAll(() => { jest.useFakeTimers(FAKE_DATE); app = loadApp().app; });
afterAll(async () => { jest.useRealTimers(); await db.$disconnect(); });
beforeEach(async () => {
  await resetDb();
  mockMarket.price = 100; expireCache();
  A = await createUser({ cash: 10000 });
  B = await createUser({ cash: 10000 });
});

const portfolio = (uid: string) => request(app).get('/api/portfolio').set(bearer(uid));
const buy = (uid: string, o: object = {}) =>
  request(app).post('/api/trades/execute/market').set(bearer(uid)).send({ ticker: T, side: 'BUY', quantity: 1, ...o });
const limit = (uid: string, o: object = {}) =>
  request(app).post('/api/trades/execute/limit').set(bearer(uid)).send({ ticker: T, side: 'BUY', quantity: 1, limitPrice: 100, ...o });

describe('Unmocked API: GET /api/portfolio', () => {
  it('PF-N1 after a buy, holding and cash match the database (UC-13)', async () => {
    await buy(A, { quantity: 3 });
    const res = await portfolio(A);
    expect(res.status).toBe(200);
    expect(res.body.holdings[0]).toEqual(expect.objectContaining({ ticker: T, quantity: 3 }));
    expect(res.body.cashBalance).toBe(await getCash(A));
  });
  it('PF-N2 a pending limit order’s reserved cash is included in the total', async () => {
    await limit(A, { limitPrice: 90, quantity: 2 });
    const res = await portfolio(A);
    expect(res.body.pendingOrders).toHaveLength(1);
    expect(res.body.totalValue).toBe(10000);
  });
  it('PF-N3 after buy then partial sell: holding reduced, two trades newest first (UC-14)', async () => {
    await buy(A, { quantity: 3 });
    await buy(A, { side: 'SELL', quantity: 1 });
    const res = await portfolio(A);
    expect(res.body.holdings[0].quantity).toBe(2);
    expect(res.body.recentTrades).toHaveLength(2);
    expect(res.body.recentTrades[0].type).toBe('SELL');
  });
  it('PF-N4 brand-new account → starting cash, nothing else', async () => {
    const res = await portfolio(A);
    expect(res.body.cashBalance).toBe(10000);
    expect(res.body.holdings).toEqual([]);
    expect(res.body.pendingOrders).toEqual([]);
    expect(res.body.recentTrades).toEqual([]);
  });
  it('PF-N5 one user never sees another user’s data', async () => {
    await giveShares(B, T, 7, 100);
    const res = await portfolio(A);
    expect(res.body.holdings).toEqual([]);
    expect(JSON.stringify(res.body)).not.toContain(B);
  });
  it('PF-N6 portfolio total equals the leaderboard total for the same user', async () => {
    await buy(A, { quantity: 2 });
    await limit(A, { limitPrice: 90 });
    const p = (await portfolio(A)).body.totalValue;
    const lb = await request(app).get('/api/friends/leaderboard').set(bearer(A));
    expect(Number(lb.body.entries.find((e: unknown) => (e as { isYou: boolean }).isYou).totalPortfolioValue)).toBe(p);
  });
  it('PF-N7 cancelling a pending order empties the list and leaves the total unchanged', async () => {
    const { body } = await limit(A, { limitPrice: 90, quantity: 2 });
    const before = (await portfolio(A)).body.totalValue;
    await request(app).post(`/api/trades/orders/${body.orderId}/cancel`).set(bearer(A));
    const after = (await portfolio(A)).body;
    expect(after.pendingOrders).toEqual([]);
    expect(after.totalValue).toBe(before);
  });
});
