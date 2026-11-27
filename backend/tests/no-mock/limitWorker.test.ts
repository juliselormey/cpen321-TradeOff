import request from 'supertest';
import type { Express } from 'express';
import type * as StubProvider from '../helpers/stubprovider';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache } from '../helpers/testkit';
import { countTrades, createUser, db, getCash, getOrder, getQty, giveShares, resetDb } from '../helpers/fixtures';
import { notifyUser } from '../../src/firebase/notifier';

const mockMarket = { price: 100, fail: false };
jest.mock('../../src/market/alpaca', () =>
  ({ AlpacaProvider: jest.requireActual<typeof StubProvider>('../helpers/stubprovider').createStubProviderClass(() => mockMarket) }));
jest.mock('../../src/firebase/notifier', () => ({ notifyUser: jest.fn() })); // Firebase is external

interface Worker {
  processPendingLimitOrders: () => Promise<{ processedCount: number; filledCount: number }>;
  startLimitOrderWorker: (intervalMs?: number) => void;
  stopLimitOrderWorker: () => void;
}

let app: Express, worker: Worker, A: string, B: string;
const tick = () => worker.processPendingLimitOrders();
const setPrice = (p: number) => { mockMarket.price = p; mockMarket.fail = false; expireCache(); };
const limit = (uid: string, o: object = {}) =>
  request(app).post('/api/trades/execute/limit').set(bearer(uid)).send({ ticker: T, side: 'buy', quantity: 1, limitPrice: 100, ...o });
const idOf = (res: { body: unknown }) => (res.body as { orderId: string }).orderId;
// Each check hits the real database, so real time passes without needing real timers.
const until = async (check: () => Promise<boolean>, tries = 300) => {
  for (let i = 0; i < tries; i++) {
    if (await check()) return true;
    await db.order.count();
  }
  return false;
};

beforeAll(() => {
  jest.useFakeTimers(FAKE_DATE);
  const l = loadApp('market/limitWorker');
  app = l.app;
  worker = l.mods[0] as Worker;
});
afterAll(async () => { jest.useRealTimers(); await db.$disconnect(); });
beforeEach(async () => {
  await resetDb();
  setPrice(100);
  A = await createUser({ cash: 10000 });
  B = await createUser({ cash: 10000 });
});
afterEach(() => worker.stopLimitOrderWorker());

describe('Unmocked: limit order matching (processPendingLimitOrders)', () => {
  it('LW-N1 a buy fills when the price falls to its limit; the held difference is refunded', async () => {
    const orderId = idOf(await limit(A, { limitPrice: 90, quantity: 2 }));
    expect(await getCash(A)).toBe(9820); // 180 reserved
    setPrice(85);
    expect(await tick()).toEqual({ processedCount: 1, filledCount: 1 });
    expect((await getOrder(orderId))?.status).toBe('FILLED');
    expect(await getQty(A, T)).toBe(2);
    expect(await getCash(A)).toBe(9830); // paid 2 × 85
  });
  it('LW-N2 a sell fills when the price rises to its limit and credits the proceeds', async () => {
    await giveShares(A, T, 5, 90);
    const orderId = idOf(await limit(A, { side: 'sell', quantity: 2, limitPrice: 120 }));
    setPrice(125);
    expect((await tick()).filledCount).toBe(1);
    expect((await getOrder(orderId))?.status).toBe('FILLED');
    expect(await getQty(A, T)).toBe(3);
    expect(await getCash(A)).toBe(10250);
  });
  it('LW-N3 orders that do not cross stay pending on both sides', async () => {
    await giveShares(A, T, 5, 90);
    const buy = idOf(await limit(A, { limitPrice: 90 }));
    const sell = idOf(await limit(A, { side: 'sell', quantity: 1, limitPrice: 120 }));
    setPrice(100);
    expect(await tick()).toEqual({ processedCount: 2, filledCount: 0 });
    expect((await getOrder(buy))?.status).toBe('PENDING');
    expect((await getOrder(sell))?.status).toBe('PENDING');
    expect(await countTrades(A)).toBe(0);
  });
  it('LW-N4 several users and several orders on one ticker are all matched in one pass', async () => {
    await limit(A, { limitPrice: 90 });
    await limit(A, { limitPrice: 95 });
    await limit(B, { limitPrice: 90 });
    setPrice(85);
    expect(await tick()).toEqual({ processedCount: 3, filledCount: 3 });
    expect(await getQty(A, T)).toBe(2);
    expect(await getQty(B, T)).toBe(1);
  });
  it('LW-N5 an outage leaves orders pending and the worker recovers afterwards', async () => {
    const orderId = idOf(await limit(A, { limitPrice: 90 }));
    mockMarket.fail = true; expireCache();
    expect(await tick()).toEqual({ processedCount: 1, filledCount: 0 });
    expect((await getOrder(orderId))?.status).toBe('PENDING');
    setPrice(85);
    expect((await tick()).filledCount).toBe(1);
  });
  it('LW-N6 overlapping runs never fill an order twice', async () => {
    await limit(A, { limitPrice: 90 });
    setPrice(85);
    await Promise.all([tick(), tick()]);
    expect(await countTrades(A)).toBe(1);
    expect(await getQty(A, T)).toBe(1);
  });
  it('LW-N7 nothing is processed when no order is pending', async () => {
    expect(await tick()).toEqual({ processedCount: 0, filledCount: 0 });
  });
});

describe('Unmocked: limit order worker lifecycle', () => {
  it('LW-N8 the started worker fills crossing orders on its interval and stops when told to', async () => {
    const first = idOf(await limit(A, { limitPrice: 90 }));
    setPrice(85);
    worker.startLimitOrderWorker(1000);
    worker.startLimitOrderWorker(1000); // a second start does not add a second timer
    jest.advanceTimersByTime(1000);
    expect(await until(async () => (await getOrder(first))?.status === 'FILLED')).toBe(true);
    expect(await countTrades(A)).toBe(1);

    worker.stopLimitOrderWorker();
    worker.stopLimitOrderWorker(); // stopping twice is harmless
    const second = idOf(await limit(B, { limitPrice: 80 }));
    setPrice(75);
    jest.advanceTimersByTime(5000);
    for (let i = 0; i < 20; i++) await db.order.count(); // let anything in flight finish
    expect((await getOrder(second))?.status).toBe('PENDING');
  });
});

describe('Unmocked: limit order notifications and bad data', () => {
  const notify = jest.mocked(notifyUser);
  beforeEach(() => notify.mockClear());

  it('LW-N9 the owner is notified once for each filled order, and only for filled orders', async () => {
    await giveShares(B, T, 5, 90);
    await limit(A, { limitPrice: 90 });
    await limit(B, { side: 'sell', quantity: 1, limitPrice: 120 });
    await limit(A, { limitPrice: 50 }); // never crosses
    setPrice(85);
    await tick();
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith(A, 'Limit order filled', `Your buy order for ${T} has been filled.`);
    setPrice(125);
    await tick();
    expect(notify).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenLastCalledWith(B, 'Limit order filled', `Your sell order for ${T} has been filled.`);
  });
  it('LW-N10 a fill that meets corrupted data rolls back completely and is retried later', async () => {
    const errors = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    await giveShares(A, T, 5, 90);
    const orderId = idOf(await limit(A, { side: 'sell', quantity: 2, limitPrice: 120 }));
    await db.position.deleteMany({ where: { userId: A } });
    setPrice(125);
    expect(await tick()).toEqual({ processedCount: 1, filledCount: 0 });
    expect((await getOrder(orderId))?.status).toBe('PENDING');
    expect(await countTrades(A)).toBe(0);
    expect(await getCash(A)).toBe(10000);
    expect(notify).not.toHaveBeenCalled();
    errors.mockRestore();
  });
  it('LW-N11 an order for a symbol outside the catalog is skipped', async () => {
    const order = await db.order.create({ data: { userId: A, ticker: 'ZZZZ', side: 'BUY', type: 'LIMIT', status: 'PENDING', quantity: 1, limitPrice: 100 } });
    expect(await tick()).toEqual({ processedCount: 1, filledCount: 0 });
    expect((await getOrder(order.id))?.status).toBe('PENDING');
  });
  it('LW-N12 a limit order without a limit price is never filled', async () => {
    const order = await db.order.create({ data: { userId: A, ticker: T, side: 'BUY', type: 'LIMIT', status: 'PENDING', quantity: 1 } });
    setPrice(1);
    expect(await tick()).toEqual({ processedCount: 1, filledCount: 0 });
    expect((await getOrder(order.id))?.status).toBe('PENDING');
  });
});