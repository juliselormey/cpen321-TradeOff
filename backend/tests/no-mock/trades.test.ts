import request from 'supertest';
import type { Express } from 'express';
import type * as StubProvider from '../helpers/stubprovider';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache, expiredBearer, tamperedBearer } from '../helpers/testkit';
import {
  countTrades, createUser, db, getAvgCost, getCash, getOrder, getQty, giveShares, resetDb,
} from '../helpers/fixtures';

const mockMarket = { price: 100, fail: false };
jest.mock('../../src/market/alpaca', () =>
  jest.requireActual<typeof StubProvider>('../helpers/stubprovider').createStubProvider(mockMarket));

interface LimitOrderWorker { processPendingLimitOrders: () => Promise<{ processedCount: number; filledCount: number }> }

let app: Express, worker: LimitOrderWorker, A: string, B: string;
const tick = () => worker.processPendingLimitOrders();
const setPrice = (p: number) => { mockMarket.price = p; mockMarket.fail = false; expireCache(); };

beforeAll(() => {
  jest.useFakeTimers(FAKE_DATE);
  const l = loadApp('market/limitWorker');
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

const QTY_MESSAGE = 'Quantity must be a positive number with at most 4 decimal places';
const LIMIT_MESSAGE = 'Limit price is required for limit orders and must be positive with at most 4 decimal places';
const send = (uid: string, kind: 'market' | 'limit', body: object) =>
  request(app).post(`/api/trades/execute/${kind}`).set(bearer(uid)).send(body);
const nothingChanged = async (uid: string) => {
  expect(await getCash(uid)).toBe(10000);
  expect(await countTrades(uid)).toBe(0);
  expect(await db.order.count({ where: { userId: uid } })).toBe(0);
};

describe('Unmocked API: order validation and authentication', () => {
  it.each([
    [{ side: 'buy', quantity: 1 }, 'Ticker symbol is required'],
    [{ ticker: 'ZZZZ', side: 'buy', quantity: 1 }, 'Unsupported ticker'],
    [{ ticker: T, side: 'hold', quantity: 1 }, 'Side must be BUY or SELL'],
    [{ ticker: T, side: 'buy' }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: 0 }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: -1 }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: 'abc' }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: 1.00001 }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: 1_000_001 }, QTY_MESSAGE],
    [{ ticker: T, side: 'buy', quantity: 1, limitPrice: 100 }, 'Limit price is forbidden for market orders'],
  ])('TRD-N22 market order %j → 400 %s, nothing changes', async (body, message) => {
    const res = await send(A, 'market', body);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe(message);
    await nothingChanged(A);
  });
  it.each([[undefined], [0], [-5], [1.23456], [1_000_001], ['abc']])(
    'TRD-N23 limit order with limitPrice %p → 400, nothing changes', async (limitPrice) => {
      const res = await send(A, 'limit', { ticker: T, side: 'buy', quantity: 1, limitPrice });
      expect(res.status).toBe(400);
      expect(res.body.error).toBe(LIMIT_MESSAGE);
      await nothingChanged(A);
    });
  it('TRD-N24 an empty body is rejected', async () => {
    expect((await request(app).post('/api/trades/execute/market').set(bearer(A))).status).toBe(400);
    await nothingChanged(A);
  });
  it('TRD-N25 missing, expired and tampered tokens → 401 on every trading route; nothing changes', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 90 }));
    const before = await getCash(A);
    for (const auth of [{}, expiredBearer(A), tamperedBearer(A)] as Record<string, string>[]) {
      expect((await request(app).post('/api/trades/execute/market').set(auth).send({ ticker: T, side: 'buy', quantity: 1 })).status).toBe(401);
      expect((await request(app).post('/api/trades/execute/limit').set(auth).send({ ticker: T, side: 'buy', quantity: 1, limitPrice: 90 })).status).toBe(401);
      expect((await request(app).post(`/api/trades/orders/${orderId}/cancel`).set(auth)).status).toBe(401);
      expect((await request(app).get('/api/trades/orders/pending').set(auth)).status).toBe(401);
      expect((await request(app).get(`/api/trades/position/${T}`).set(auth)).status).toBe(401);
    }
    expect(await getCash(A)).toBe(before);
    expect((await getOrder(orderId))?.status).toBe('PENDING');
  });
  it('TRD-N26 the legacy `type` field is accepted as the side', async () => {
    const res = await request(app).post('/api/trades/execute/market').set(bearer(A)).send({ ticker: T, type: 'buy', quantity: 1 });
    expect(res.status).toBe(200);
    expect(await getQty(A, T)).toBe(1);
  });
  it('TRD-N27 fractional quantities (up to 4 decimals) are supported', async () => {
    expect((await market(A, { quantity: 0.5 })).status).toBe(200);
    expect(await getQty(A, T)).toBeCloseTo(0.5, 4);
    expect(await getCash(A)).toBeCloseTo(9950, 4);
  });
  it('TRD-N28 a price outage → 503 and no state change; trading resumes after recovery', async () => {
    mockMarket.fail = true; expireCache();
    expect((await market(A)).status).toBe(503);
    expect((await limit(A)).status).toBe(503);
    await nothingChanged(A);
    setPrice(100);
    expect((await market(A)).status).toBe(200);
  });
});

describe('Unmocked API: GET /api/trades/position/:ticker', () => {
  const position = (uid: string, ticker = T) => request(app).get(`/api/trades/position/${ticker}`).set(bearer(uid));

  it('TRD-N29 a new user has the starting balance and no position', async () => {
    expect((await position(A)).body).toEqual({ cashBalance: 10000, ownedQuantity: 0, averageCost: 0, availableQuantity: 0 });
  });
  it('TRD-N30 shares reserved by a pending sell are owned but not available (case-insensitive ticker)', async () => {
    await giveShares(A, T, 3, 90);
    await limit(A, { side: 'sell', quantity: 1, limitPrice: 150 });
    const res = await position(A, T.toLowerCase());
    expect(res.body).toEqual({ cashBalance: 10000, ownedQuantity: 3, averageCost: 90, availableQuantity: 2 });
  });
});

describe('Unmocked API: GET /api/trades/orders/pending', () => {
  const pending = (uid: string) => request(app).get('/api/trades/orders/pending').set(bearer(uid));

  it('TRD-N31 lists only the caller’s pending orders, with reserved cash for buys only', async () => {
    await giveShares(A, T, 3, 90);
    const buyId = orderIdFrom(await limit(A, { limitPrice: 90, quantity: 2 }));
    const sellId = orderIdFrom(await limit(A, { side: 'sell', quantity: 1, limitPrice: 150 }));
    await limit(B, { limitPrice: 90 });
    const { orders } = (await pending(A)).body;
    expect(orders.map((o: { id: string }) => o.id).sort()).toEqual([buyId, sellId].sort());
    expect(orders.find((o: { id: string }) => o.id === buyId)).toEqual(expect.objectContaining({ side: 'BUY', type: 'LIMIT', status: 'PENDING', limitPrice: 90, quantity: 2, reservedCash: 180 }));
    expect(orders.find((o: { id: string }) => o.id === sellId).reservedCash).toBe(0);
    await cancel(A, buyId);
    await cancel(A, sellId);
    expect((await pending(A)).body.orders).toEqual([]);
    expect((await pending(B)).body.orders).toHaveLength(1);
  });
});

describe('Unmocked API: price improvement and reservations', () => {
  it('TRD-N32 a limit buy above market fills at the market price and refunds the difference', async () => {
    const res = await limit(A, { limitPrice: 110, quantity: 2 });
    expect(res.status).toBe(200);
    expect(res.body).toEqual(expect.objectContaining({ status: 'FILLED', executedPrice: 100 }));
    expect(await getCash(A)).toBe(9800);
    const order = await getOrder(orderIdFrom(res));
    expect(Number(order?.executedPrice)).toBe(100);
    expect(Number((await db.trade.findFirstOrThrow({ where: { userId: A } })).pricePerShare)).toBe(100);
  });
  it('TRD-N33 a limit sell below market fills at the market price', async () => {
    await giveShares(A, T, 5, 90);
    const res = await limit(A, { side: 'sell', quantity: 2, limitPrice: 80 });
    expect(res.body.status).toBe('FILLED');
    expect(res.body.executedPrice).toBe(100);
    expect(await getCash(A)).toBe(10200);
  });
  it('TRD-N34 a pending limit sell reserves its shares; cancelling frees them', async () => {
    await giveShares(A, T, 5, 90);
    const orderId = orderIdFrom(await limit(A, { side: 'sell', quantity: 5, limitPrice: 150 }));
    const blocked = await market(A, { side: 'sell', quantity: 1 });
    expect(blocked.status).toBe(400);
    expect(blocked.body.error).toBe('Insufficient shares owned');
    expect(await getQty(A, T)).toBe(5);
    expect((await cancel(A, orderId)).status).toBe(200);
    expect(await getCash(A)).toBe(10000); // a sell reserves no cash
    expect((await market(A, { side: 'sell', quantity: 1 })).status).toBe(200);
    expect(await getQty(A, T)).toBe(4);
  });
  it('TRD-N35 cancelling a filled order → 409 and a malformed id → 400, cash unchanged', async () => {
    const orderId = orderIdFrom(await limit(A, { limitPrice: 110 }));
    const cash = await getCash(A);
    expect((await cancel(A, orderId)).status).toBe(409);
    expect((await cancel(A, 'not-a-uuid')).status).toBe(400);
    expect(await getCash(A)).toBe(cash);
  });
  it('TRD-N36 a pending buy reserves cash that a market buy cannot spend', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 300 } });
    await limit(A, { limitPrice: 90, quantity: 3 }); // reserves 270
    const res = await market(A, { quantity: 1 });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Insufficient cash balance');
    expect(await getCash(A)).toBe(30);
  });
});

describe('Unmocked: limit order worker', () => {
  it('TRD-N37 one tick fills every user’s crossing order and reports the counts', async () => {
    await limit(A, { limitPrice: 90 });
    await limit(B, { limitPrice: 90 });
    setPrice(85);
    const result = await tick();
    expect(result).toEqual({ processedCount: 2, filledCount: 2 });
    expect(await getQty(A, T)).toBe(1);
    expect(await getQty(B, T)).toBe(1);
    expect(await getCash(A)).toBe(9915); // price improvement refunded: paid 85, held 90
  });
  it('TRD-N38 a pending limit sell stays pending below its limit', async () => {
    await giveShares(A, T, 5, 90);
    const orderId = orderIdFrom(await limit(A, { side: 'sell', quantity: 2, limitPrice: 120 }));
    setPrice(110);
    expect((await tick()).filledCount).toBe(0);
    expect((await getOrder(orderId))?.status).toBe('PENDING');
  });
  it('TRD-N39 two overlapping ticks never double-fill', async () => {
    await limit(A, { limitPrice: 90 });
    setPrice(85);
    await Promise.all([tick(), tick()]);
    expect(await countTrades(A)).toBe(1);
    expect(await getQty(A, T)).toBe(1);
  });
  it('TRD-N40 filled and cancelled orders are not processed again', async () => {
    const filled = orderIdFrom(await limit(A, { limitPrice: 90 }));
    const cancelled = orderIdFrom(await limit(B, { limitPrice: 90 }));
    await cancel(B, cancelled);
    setPrice(85);
    await tick();
    expect((await getOrder(filled))?.status).toBe('FILLED');
    expect(await tick()).toEqual({ processedCount: 0, filledCount: 0 });
  });
});

describe('Unmocked API: trade boundaries', () => {
  it('TRD-N41 cash exactly equal to the cost is enough (boundary)', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 100 } });
    expect((await market(A, { quantity: 1 })).status).toBe(200);
    expect(await getCash(A)).toBe(0);
  });
  it('TRD-N42 a client-sent price is ignored; the server price is used', async () => {
    const res = await market(A, { pricePerShare: 1, price: 1 });
    expect(res.status).toBe(200);
    expect(res.body.executedPrice).toBe(100);
    expect(await getCash(A)).toBe(9900);
  });
  it('TRD-N43 selling a stock that was never owned → 400, nothing changes', async () => {
    const res = await market(A, { side: 'sell' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Insufficient shares owned');
    expect(await countTrades(A)).toBe(0);
    expect(await getCash(A)).toBe(10000);
  });
  it('TRD-N44 a limit buy needs cash for its whole reservation → 400, nothing reserved', async () => {
    await db.account.update({ where: { userId: A }, data: { cashBalance: 50 } });
    const res = await limit(A, { limitPrice: 90, quantity: 1 });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Insufficient cash balance');
    expect(await getCash(A)).toBe(50);
    expect(await db.order.count({ where: { userId: A } })).toBe(0);
  });
});