import request from 'supertest';
import type { Express } from 'express';
import type * as StubProvider from '../helpers/stubprovider';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache } from '../helpers/testkit';
import {
  countTrades, createUser, db, getAvgCost, getCash, getOrder, getQty, giveShares, resetDb,
} from '../helpers/fixtures';

const mockMarket = { price: 100, fail: false };
jest.mock('../../src/services/alpacaProvider', () =>
  jest.requireActual<typeof StubProvider>('../helpers/stubprovider').createStubProvider(mockMarket));

interface LimitOrderWorker { runLimitOrderWorkerOnce: () => Promise<void> }

let app: Express, worker: LimitOrderWorker, A: string, B: string;
const tick = () => worker.runLimitOrderWorkerOnce();
const setPrice = (p: number) => { mockMarket.price = p; mockMarket.fail = false; expireCache(); };

beforeAll(() => {
  jest.useFakeTimers(FAKE_DATE);
  const l = loadApp('services/limitOrderWorker');
  app = l.app;
  worker = l.mods[0] as LimitOrderWorker;
});
afterAll(async () => { jest.useRealTimers(); await db.$disconnect(); });
beforeEach(async () => {
  await resetDb();
  setPrice(100);
  A = await createUser({ cash: 10000 });
  B = await createUser({ cash: 10000 });
});

const market = (uid: string, o: object = {}) =>
  request(app).post('/api/trades/execute/market').set(bearer(uid)).send({ ticker: T, side: 'buy', quantity: 1, ...o });
const limit = (uid: string, o: object = {}) =>
  request(app).post('/api/trades/execute/limit').set(bearer(uid)).send({ ticker: T, side: 'buy', quantity: 1, limitPrice: 100, ...o });
const cancel = (uid: string, id: string) => request(app).post(`/api/trades/orders/${id}/cancel`).set(bearer(uid));
const orderIdFrom = (res: { body: unknown }) => (res.body as { orderId: string }).orderId;

describe('Unmocked API: POST /api/trades/execute/market', () => {
  it('TRD-N1 buy debits cash, creates the position, records one trade (UC-09)', async () => {
    const res = await market(A, { quantity: 3 });
    expect(res.status).toBe(200);
    expect(await getCash(A)).toBe(9700);
    expect(await getQty(A, T)).toBe(3);
    expect(await countTrades(A)).toBe(1);
    expect(res.body.cashBalance).toBe(9700);
  });
  it('TRD-N2 second buy adds to the position with weighted average cost', async () => {
    await giveShares(A, T, 2, 90);
    await market(A, { quantity: 2 });
    expect(await getQty(A, T)).toBe(4);
    expect(await getAvgCost(A, T)).toBe(95);
  });
  it('TRD-N3 insufficient cash → 400, nothing changes (UC-09 8b)', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 50 } });
    expect((await market(A)).status).toBe(400);
    expect(await getCash(A)).toBe(50);
    expect(await getQty(A, T)).toBe(0);
    expect(await countTrades(A)).toBe(0);
  });
  it.todo('TRD-N4 failure mid-transaction rolls everything back (needs a hook to force the final insert to fail)');
  it('TRD-N5 two simultaneous buys cannot overdraw the account', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 100 } });
    setPrice(60);
    const statuses = (await Promise.all([market(A), market(A)])).map((r: { status: number }) => r.status).sort();
    expect(statuses).toEqual([200, 400]);
    expect(await getCash(A)).toBe(40);
  });
  it('TRD-N6 ten simultaneous buys: cash never negative, trades = successes', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 550 } });
    const results = await Promise.all(Array.from({ length: 10 }, () => market(A)));
    const ok = results.filter((r) => r.status === 200).length;
    expect(ok).toBe(5);
    expect(await getCash(A)).toBe(550 - 100 * ok);
    expect(await countTrades(A)).toBe(ok);
  });
  it('TRD-N14 sell credits cash and reduces the position (UC-11)', async () => {
    await giveShares(A, T, 5, 90);
    const res = await market(A, { side: 'sell', quantity: 2 });
    expect(res.status).toBe(200);
    expect(await getCash(A)).toBe(10200);
    expect(await getQty(A, T)).toBe(3);
    expect(await db.trade.count({ where: { userId: A, type: 'SELL' } })).toBe(1);
  });
  it('TRD-N15 selling the whole position leaves none, never negative', async () => {
    await giveShares(A, T, 5, 90);
    await market(A, { side: 'sell', quantity: 5 });
    expect(await getQty(A, T)).toBe(0);
  });
  it('TRD-N16 selling more than owned → 400, state unchanged', async () => {
    await giveShares(A, T, 5, 90);
    expect((await market(A, { side: 'sell', quantity: 6 })).status).toBe(400);
    expect(await getQty(A, T)).toBe(5);
    expect(await getCash(A)).toBe(10000);
  });
});

describe('Unmocked API: POST /api/trades/execute/limit', () => {
  it('TRD-N7 limit below market → pending order, cash reserved, no position, no trade (UC-10)', async () => {
    const res = await limit(A, { limitPrice: 90, quantity: 2 });
    expect(res.status).toBe(200);
    const order = await getOrder(orderIdFrom(res));
    expect(order?.status).toBe('PENDING');
    expect(await getCash(A)).toBe(10000 - 180);
    expect(await getQty(A, T)).toBe(0);
    expect(await countTrades(A)).toBe(0);
  });
  it('TRD-N8 limit at or above market → filled immediately, nothing left pending', async () => {
    const res = await limit(A, { limitPrice: 110 });
    expect(res.status).toBe(200);
    expect((await getOrder(orderIdFrom(res)))?.status).toBe('FILLED');
    expect(await countTrades(A)).toBe(1);
    expect(await db.order.count({ where: { userId: A, status: 'PENDING' } })).toBe(0);
  });
  it('TRD-N9 worker fills a pending buy once the price crosses the limit', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90, quantity: 2 }));
    setPrice(85);
    await tick();
    expect((await getOrder(orderId))?.status).toBe('FILLED');
    expect(await countTrades(A)).toBe(1);
    expect(await getQty(A, T)).toBe(2);
  });
  it('TRD-N10 worker leaves the order pending while price > limit', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90 }));
    setPrice(95);
    await tick();
    expect((await getOrder(orderId))?.status).toBe('PENDING');
    expect(await countTrades(A)).toBe(0);
  });
  it('TRD-N11 a second tick does not fill the same order twice', async () => {
    await limit(A, { limitPrice: 90 });
    setPrice(85);
    await tick();
    await tick();
    expect(await countTrades(A)).toBe(1);
  });
  it('TRD-N12 worker never fills a cancelled order', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90 }));
    await cancel(A, orderId);
    setPrice(80);
    await tick();
    expect((await getOrder(orderId))?.status).toBe('CANCELLED');
    expect(await countTrades(A)).toBe(0);
  });
  it('TRD-N13 worker survives a provider outage and fills after recovery', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90 }));
    mockMarket.fail = true; expireCache();
    await expect(tick()).resolves.not.toThrow();
    expect((await getOrder(orderId))?.status).toBe('PENDING');
    setPrice(85);
    await tick();
    expect((await getOrder(orderId))?.status).toBe('FILLED');
  });
  it('TRD-N17 pending limit sell fills when the price rises to the limit', async () => {
    await giveShares(A, T, 5, 90);
    const orderId = orderIdFrom(await limit(A, { side: 'sell', quantity: 2, limitPrice: 120 }));
    expect((await getOrder(orderId))?.status).toBe('PENDING');
    setPrice(125);
    const before = await getCash(A);
    await tick();
    expect((await getOrder(orderId))?.status).toBe('FILLED');
    expect(await getQty(A, T)).toBe(3);
    expect(await getCash(A)).toBeGreaterThanOrEqual(before + 240);
  });
});

describe('Unmocked API: POST /api/trades/orders/:id/cancel', () => {
  it('TRD-N18 cancel releases exactly the reserved cash, position untouched (UC-12)', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90, quantity: 2 }));
    const res = await cancel(A, orderId);
    expect(res.status).toBe(200);
    expect((await getOrder(orderId))?.status).toBe('CANCELLED');
    expect(await getCash(A)).toBe(10000);
    expect(await getQty(A, T)).toBe(0);
  });
  it('TRD-N19 cancelling twice → 409 and cash released only once', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90, quantity: 2 }));
    await cancel(A, orderId);
    expect((await cancel(A, orderId)).status).toBe(409);
    expect(await getCash(A)).toBe(10000);
  });
  it('TRD-N20 cancel racing the worker: exactly one outcome, cash consistent', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90, quantity: 2 }));
    setPrice(85);
    await Promise.all([cancel(A, orderId), tick()]);
    const status = (await getOrder(orderId))?.status;
    if (status === 'CANCELLED') {
      expect(await countTrades(A)).toBe(0);
      expect(await getCash(A)).toBe(10000);
    } else {
      expect(status).toBe('FILLED');
      expect(await countTrades(A)).toBe(1);
      expect(await getQty(A, T)).toBe(2);
    }
  });
  it('TRD-N21 another user cannot cancel the order → 404, order stays pending', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90 }));
    expect((await cancel(B, orderId)).status).toBe(404);
    expect((await getOrder(orderId))?.status).toBe('PENDING');
  });
});