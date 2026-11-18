import request from 'supertest';
import type { Express } from 'express';
import { loadApp } from '../helpers/loadApp';
import { T, bearer, expectError, expiredBearer, tamperedBearer } from '../helpers/testkit';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { Prisma } from '../../src/generated/prisma/client';

const decimal = (v: number) => new Prisma.Decimal(v);
const mock = (f: unknown) => f as jest.Mock;

jest.mock('../../src/prisma/db', () => ({
  db: {
    user: { findUnique: jest.fn() },
    account: { findUnique: jest.fn(), findUniqueOrThrow: jest.fn(), update: jest.fn(), upsert: jest.fn() },
    position: { findUnique: jest.fn(), findFirst: jest.fn(), upsert: jest.fn(), update: jest.fn() },
    order: { create: jest.fn(), findUnique: jest.fn(), findFirst: jest.fn(), updateMany: jest.fn(), aggregate: jest.fn() },
    trade: { create: jest.fn() },
    $transaction: jest.fn((cb) => cb(db)),
  },
}));
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: {
    getStock: jest.fn(),
  },
}));

const U = 'u1';
const MARKET = '/api/trades/execute/market';
const LIMIT = '/api/trades/execute/limit';
const CANCEL = (id: string) => `/api/trades/orders/${id}/cancel`;
let app: Express;

beforeEach(() => {
  const l = loadApp('db', 'market/service');
  app = l.app;
  (db.user.findUnique as unknown as jest.Mock).mockResolvedValue({ id: U });
  (db.$transaction as unknown as jest.Mock).mockImplementation(async (cb: (tx: typeof db) => unknown) => cb(db));
  (marketService.getStock as unknown as jest.Mock).mockResolvedValue({ currentPrice: '100', status: 'ok' });
  (db.account.upsert as unknown as jest.Mock).mockResolvedValue({ userId: U, cashBalance: decimal(10000), startingBalance: decimal(10000) });
  (db.account.findUnique as unknown as jest.Mock).mockResolvedValue({ userId: U, cashBalance: decimal(10000), startingBalance: decimal(10000) });
  (db.account.findUniqueOrThrow as unknown as jest.Mock).mockResolvedValue({ userId: U, cashBalance: decimal(10000), startingBalance: decimal(10000) });
  (db.position.findUnique as unknown as jest.Mock).mockResolvedValue({ userId: U, ticker: T, quantity: decimal(2), averageCost: decimal(90) });
  (db.order.aggregate as unknown as jest.Mock).mockResolvedValue({ _sum: { quantity: decimal(0) } });
  (db.order.updateMany as unknown as jest.Mock).mockResolvedValue({ count: 1 });
});

const cash = (c: number) => {
  const acc = { userId: U, cashBalance: decimal(c), startingBalance: decimal(10000) };
  (db.account.findUnique as unknown as jest.Mock).mockResolvedValue(acc);
  (db.account.findUniqueOrThrow as unknown as jest.Mock).mockResolvedValue(acc);
  (db.account.upsert as unknown as jest.Mock).mockResolvedValue(acc);
};
const owned = (q: number) =>
  (db.position.findUnique as unknown as jest.Mock).mockResolvedValue(q ? { userId: U, ticker: T, quantity: decimal(q), averageCost: decimal(90) } : null);
const stubWrites = () => {
  (db.order.create as unknown as jest.Mock).mockResolvedValue({ id: 'o1', status: 'PENDING', quantity: decimal(1), side: 'BUY', type: 'MARKET', ticker: T });
  (db.trade.create as unknown as jest.Mock).mockResolvedValue({ id: 't1' });
  (db.account.update as unknown as jest.Mock).mockResolvedValue({ cashBalance: decimal(0) });
  (db.position.upsert as unknown as jest.Mock).mockResolvedValue({});
  (db.position.update as unknown as jest.Mock).mockResolvedValue({});
};
const marketBody = (o: object = {}) => ({ ticker: T, side: 'BUY', quantity: 1, ...o });
const limitBody = (o: object = {}) => ({ ticker: T, side: 'BUY', quantity: 1, limitPrice: 100, ...o });
const postMarket = (o: object = {}) => request(app).post(MARKET).set(bearer(U)).send(marketBody(o));
const postLimit = (o: object = {}) => request(app).post(LIMIT).set(bearer(U)).send(limitBody(o));

describe('Mocked API: POST /api/trades/execute/market', () => {
  it.each([[MARKET], [LIMIT], [CANCEL('00000000-0000-0000-0000-000000000001')]])('TRD-01 %s without Authorization → 401', async (path) => {
    expect((await request(app).post(path).send(marketBody())).status).toBe(401);
  });
  it.each([[MARKET], [LIMIT]])('TRD-02 %s with expired or tampered JWT → 401', async (path) => {
    expect((await request(app).post(path).set(expiredBearer(U)).send(marketBody())).status).toBe(401);
    expect((await request(app).post(path).set(tamperedBearer(U)).send(marketBody())).status).toBe(401);
  });

  it('TRD-03..06 market rejects invalid quantity', async () => {
    cash(1000);
    for (const quantity of [0, -1, 'abc', 1.123456, undefined]) {
      expectError(await postMarket({ quantity }), 400);
    }
  });
  it('TRD-03..06 limit rejects invalid quantity', async () => {
    cash(1000);
    for (const quantity of [0, -1, 'abc', 1.123456, undefined]) {
      expectError(await postLimit({ quantity }), 400);
    }
  });
  it('TRD-08 side other than buy/sell → 400', async () => {
    cash(1000);
    expectError(await postMarket({ side: 'hold' }), 400);
  });

  it('TRD-09 price lookup fails → 503, transaction never started (UC-09 7a)', async () => {
    cash(1000);
    mock(marketService.getStock).mockRejectedValue(new Error('price unavailable'));
    expectError(await postMarket(), 503);
    expect(db.$transaction).not.toHaveBeenCalled();
  });
  it('TRD-10 cash below cost → 400 with no writes (UC-09 8b)', async () => {
    cash(50);
    expectError(await postMarket({ quantity: 1 }), 400);
    expect(db.trade.create).not.toHaveBeenCalled();
  });
  it('TRD-11 cash exactly equal to cost → 200 (boundary)', async () => {
    cash(100); stubWrites();
    expect((await postMarket({ quantity: 1 })).status).toBe(200);
  });
  it('TRD-12 client-sent price is ignored; the server price is used', async () => {
    cash(1000); stubWrites();
    const res = await postMarket({ pricePerShare: 1, price: 1 });
    expect(res.status).toBe(200);
    expect(res.body.executedPrice).toBe(100);
  });
  it('TRD-17 sell more than owned → 400 (UC-11)', async () => {
    cash(0); owned(2);
    mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: decimal(0) } });
    expectError(await postMarket({ side: 'SELL', quantity: 3 }), 400);
  });
  it('TRD-18 sell a stock never owned → 400', async () => {
    cash(0); owned(0);
    mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: decimal(0) } });
    expectError(await postMarket({ side: 'SELL' }), 400);
  });
  it('TRD-19 sell exactly what is owned → 200 (boundary)', async () => {
    cash(0); owned(2); stubWrites();
    mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: decimal(0) } });
    expect((await postMarket({ side: 'SELL', quantity: 2 })).status).toBe(200);
  });
});

describe('Mocked API: POST /api/trades/execute/limit', () => {
  it.each([0, -5, NaN, undefined])('TRD-13 invalid limitPrice %p → 400', async (limitPrice) => {
    cash(1000);
    expectError(await postLimit({ limitPrice }), 400);
  });
  it('TRD-14 limit at or above market → filled immediately', async () => {
    cash(1000); stubWrites();
    const res = await postLimit({ limitPrice: 110 });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('FILLED');
  });
  it('TRD-15 limit below market → pending order', async () => {
    cash(1000); stubWrites();
    mock(db.order.create).mockResolvedValue({ id: 'o1', status: 'PENDING', quantity: decimal(1), side: 'BUY', type: 'LIMIT', ticker: T, limitPrice: decimal(90) });
    const res = await postLimit({ limitPrice: 90 });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('PENDING');
  });
  it('TRD-16 cash below the reservation → 400', async () => {
    cash(50);
    expectError(await postLimit({ limitPrice: 90, quantity: 1 }), 400);
  });
});

describe('Mocked API: POST /api/trades/orders/:id/cancel', () => {
  const cancelId = '00000000-0000-0000-0000-000000000001';
  const cancel = (id = cancelId) => request(app).post(CANCEL(id)).set(bearer(U));

  it('TRD-21 unknown order → 404 (UC-12)', async () => {
    mock(db.order.updateMany).mockResolvedValue({ count: 0 });
    mock(db.order.findFirst).mockResolvedValue(null);
    expectError(await cancel(), 404);
  });
  it('TRD-22 another user’s order → 404 (does not reveal it exists)', async () => {
    mock(db.order.updateMany).mockResolvedValue({ count: 0 });
    mock(db.order.findFirst).mockResolvedValue(null);
    expectError(await cancel(), 404);
  });
  it.each(['FILLED', 'CANCELLED'])('TRD-23 %s order → 409', async (status) => {
    mock(db.order.updateMany).mockResolvedValue({ count: 0 });
    mock(db.order.findFirst).mockResolvedValue({ id: cancelId, userId: U, status });
    expectError(await cancel(), 409);
  });
  it('TRD-24 malformed order id → 400', async () => {
    expectError(await cancel('not-a-valid-uuid'), 400);
  });
});
