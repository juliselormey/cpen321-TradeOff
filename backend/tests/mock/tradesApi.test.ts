import request from 'supertest';
import type { Express } from 'express';
import type * as MarketServiceModule from '../../src/market/service';
import { loadApp } from '../helpers/loadApp';
import { T, bearer, expectError, expiredBearer, tamperedBearer } from '../helpers/testkit';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { Prisma } from '../../src/generated/prisma/client';
import { OrderError, availableShares, fillOrder, getServerPrice, runSerializable } from '../../src/market/settlement';

const decimal = (v: number) => new Prisma.Decimal(v);
const mock = (f: unknown) => f as jest.Mock;

jest.mock('../../src/prisma/db', () => ({
  db: {
    user: { findUnique: jest.fn() },
    account: { findUnique: jest.fn(), findUniqueOrThrow: jest.fn(), update: jest.fn(), upsert: jest.fn() },
    position: { findUnique: jest.fn(), findFirst: jest.fn(), upsert: jest.fn(), update: jest.fn(), delete: jest.fn() },
    order: { create: jest.fn(), findMany: jest.fn(), findUnique: jest.fn(), findUniqueOrThrow: jest.fn(), findFirst: jest.fn(), updateMany: jest.fn(), aggregate: jest.fn() },
    trade: { create: jest.fn() },
    $transaction: jest.fn((cb: (tx: unknown) => unknown) => cb(db)),
  },
}));
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual<typeof MarketServiceModule>('../../src/market/service'),
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
  (db.$transaction as unknown as jest.Mock).mockImplementation((cb: (tx: typeof db) => unknown) => Promise.resolve(cb(db)));
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
    jest.mocked(marketService).getStock.mockRejectedValue(new Error('price unavailable'));
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

describe('Mocked API: trades routes (positions, pending orders, execution paths)', () => {
  let errors: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks(); // calls only; the outer beforeEach has already set the default behaviour
    errors = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    mock(db.position.findUnique).mockResolvedValue(null);
    mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: null } });
  });
  afterEach(() => { errors.mockRestore(); });

  const orderRow = (o: object = {}) => ({
    id: 'o1', userId: U, ticker: T, side: 'BUY', type: 'MARKET', status: 'PENDING', quantity: decimal(1),
    limitPrice: null, createdAt: new Date('2026-01-01T00:00:00Z'), ...o,
  });
  const heldPos = (quantity: number, averageCost = 90) => ({ userId: U, ticker: T, quantity: decimal(quantity), averageCost: decimal(averageCost) });
  const getTrades = (path: string) => request(app).get(`/api/trades${path}`).set(bearer(U));
  const place = (kind: 'market' | 'limit', body?: object) =>
    request(app).post(kind === 'market' ? MARKET : LIMIT).set(bearer(U)).send(body);

  describe('GET /api/trades/position/:ticker', () => {
    it('TRD-25 returns cash, holdings and the quantity not reserved by pending sells', async () => {
      mock(db.account.findUnique).mockResolvedValue({ userId: U, cashBalance: decimal(500), startingBalance: decimal(10000) });
      mock(db.position.findUnique).mockResolvedValue(heldPos(3));
      mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: decimal(1) } });
      const res = await getTrades(`/position/${T.toLowerCase()}`);
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ cashBalance: 500, ownedQuantity: 3, averageCost: 90, availableQuantity: 2 });
      expect(db.position.findUnique).toHaveBeenCalledWith({ where: { userId_ticker: { userId: U, ticker: T } } });
    });
    it('TRD-26 new user without account or position → starting balance and zeros', async () => {
      mock(db.account.findUnique).mockResolvedValue(null);
      const res = await getTrades(`/position/${T}`);
      expect(res.body).toEqual({ cashBalance: 10000, ownedQuantity: 0, averageCost: 0, availableQuantity: 0 });
    });
    it('TRD-27 available quantity never goes negative', async () => {
      mock(db.order.aggregate).mockResolvedValue({ _sum: { quantity: decimal(2) } });
      expect((await getTrades(`/position/${T}`)).body.availableQuantity).toBe(0);
    });
    it('TRD-28 database failure → 500', async () => {
      mock(db.account.findUnique).mockRejectedValue(new Error('db down'));
      const res = await getTrades(`/position/${T}`);
      expect(res.status).toBe(500);
      expect(res.body).toEqual({ error: 'Failed to fetch position' });
    });
  });

  describe('GET /api/trades/orders/pending', () => {
    it('TRD-29 serializes orders and reserves cash only for buy limits with a price', async () => {
      mock(db.order.findMany).mockResolvedValue([
        orderRow({ type: 'LIMIT', limitPrice: decimal(50), quantity: decimal(2) }),
        orderRow({ id: 'o2', side: 'SELL', type: 'LIMIT', limitPrice: decimal(60) }),
        orderRow({ id: 'o3', limitPrice: null }),
      ]);
      const res = await getTrades('/orders/pending');
      expect(res.status).toBe(200);
      expect(res.body.orders.map((o: { reservedCash: number }) => o.reservedCash)).toEqual([100, 0, 0]);
      expect(res.body.orders[0]).toMatchObject({ id: 'o1', limitPrice: 50, quantity: 2, createdAt: '2026-01-01T00:00:00.000Z' });
      expect(res.body.orders[2].limitPrice).toBeNull();
      expect(db.order.findMany).toHaveBeenCalledWith({ where: { userId: U, status: 'PENDING' }, orderBy: { createdAt: 'desc' } });
    });
  });

  describe('POST /api/trades/orders/:id/cancel (success paths)', () => {
    const id = '00000000-0000-4000-8000-000000000001';
    const cancel = () => request(app).post(CANCEL(id)).set(bearer(U));
    it('TRD-30 cancelling a buy limit releases its reserved cash', async () => {
      mock(db.order.findUniqueOrThrow).mockResolvedValue(orderRow({ type: 'LIMIT', limitPrice: decimal(90), quantity: decimal(2) }));
      const res = await cancel();
      expect(res.status).toBe(200);
      expect(res.body).toMatchObject({ success: true, orderId: id, status: 'CANCELLED', releasedCash: 180 });
      expect(db.order.updateMany).toHaveBeenCalledWith({ where: { id, userId: U, status: 'PENDING' }, data: { status: 'CANCELLED' } });
      expect(mock(db.account.update).mock.calls[0][0].data.cashBalance.increment.toString()).toBe('180');
    });
    it('TRD-31 cancelling a sell limit releases no cash', async () => {
      mock(db.order.findUniqueOrThrow).mockResolvedValue(orderRow({ side: 'SELL', type: 'LIMIT', limitPrice: decimal(90) }));
      const res = await cancel();
      expect(res.body.releasedCash).toBe(0);
      expect(db.account.update).not.toHaveBeenCalled();
    });
    it('TRD-32 unexpected failure → 500', async () => {
      mock(db.$transaction).mockRejectedValue(new Error('db down'));
      const res = await cancel();
      expect(res.status).toBe(500);
      expect(res.body).toEqual({ error: 'Failed to cancel order' });
    });
  });

  describe('order validation', () => {
    it.each([
      [{ side: 'BUY', quantity: 1 }, 'Ticker symbol is required'],
      [{ ticker: 'ZZZZ', side: 'BUY', quantity: 1 }, 'Unsupported ticker'],
      [{ ticker: T, type: 'hold', quantity: 1 }, 'Side must be BUY or SELL'],
      [{ ticker: T, side: 'BUY', quantity: 1_000_001 }, 'Quantity must be a positive number with at most 4 decimal places'],
      [{ ticker: T, side: 'BUY', quantity: 1, limitPrice: 100 }, 'Limit price is forbidden for market orders'],
    ])('TRD-33 market %j → 400 %s', async (body, message) => {
      const res = await place('market', body);
      expect(res.status).toBe(400);
      expect(res.body.error).toBe(message);
      expect(db.$transaction).not.toHaveBeenCalled();
    });
    it('TRD-34 empty body → 400', async () => {
      expect((await place('market')).status).toBe(400);
    });
    it.each([1.23456, 1_000_001])('TRD-35 limit price %p → 400', async (limitPrice) => {
      expect((await place('limit', { ticker: T, side: 'BUY', quantity: 1, limitPrice })).status).toBe(400);
    });
  });

  describe('order execution', () => {
    it('TRD-36 legacy `type` field is accepted as the side', async () => {
      mock(db.order.create).mockResolvedValue(orderRow());
      const res = await place('market', { ticker: T, type: 'buy', quantity: 1 });
      expect(res.status).toBe(200);
      expect(res.body).toMatchObject({ status: 'FILLED', message: 'MARKET BUY filled @ $100' });
    });
    it('TRD-37 market buy into an existing position updates quantity and average cost without a refund', async () => {
      mock(db.position.findUnique).mockResolvedValue(heldPos(2, 90));
      mock(db.order.create).mockResolvedValue(orderRow());
      expect((await place('market', { ticker: T, side: 'BUY', quantity: 1 })).status).toBe(200);
      const { update } = mock(db.position.upsert).mock.calls[0][0];
      expect(update.quantity.toString()).toBe('3');
      expect(update.averageCost.toFixed(4)).toBe('93.3333');
      expect(db.account.update).toHaveBeenCalledTimes(1); // the hold only
    });
    it('TRD-38 limit buy above market fills at market and refunds the difference', async () => {
      mock(db.order.create).mockResolvedValue(orderRow({ type: 'LIMIT', limitPrice: decimal(110) }));
      const res = await place('limit', { ticker: T, side: 'BUY', quantity: 1, limitPrice: 110 });
      expect(res.body).toMatchObject({ status: 'FILLED', executedPrice: 100, ownedQuantity: 0 });
      const calls = mock(db.account.update).mock.calls;
      expect(calls).toHaveLength(2);
      expect(calls[1][0].data.cashBalance.increment.toString()).toBe('10');
    });
    it('TRD-39 limit sell at or below market fills and reduces the position', async () => {
      mock(db.position.findUnique).mockResolvedValue(heldPos(5));
      mock(db.order.create).mockResolvedValue(orderRow({ side: 'SELL', type: 'LIMIT', limitPrice: decimal(90) }));
      const res = await place('limit', { ticker: T, side: 'SELL', quantity: 1, limitPrice: 90 });
      expect(res.body).toMatchObject({ status: 'FILLED', message: 'LIMIT SELL filled @ $100' });
      expect(mock(db.position.update).mock.calls[0][0].data.quantity.toString()).toBe('4');
      expect(mock(db.account.update).mock.calls[0][0].data.cashBalance.increment.toString()).toBe('100');
    });
    it('TRD-40 selling the entire position deletes it', async () => {
      mock(db.position.findUnique).mockResolvedValue(heldPos(1));
      mock(db.order.create).mockResolvedValue(orderRow({ side: 'SELL' }));
      expect((await place('market', { ticker: T, side: 'SELL', quantity: 1 })).status).toBe(200);
      expect(db.position.delete).toHaveBeenCalledWith({ where: { userId_ticker: { userId: U, ticker: T } } });
    });
    it('TRD-41 limit sell above market stays pending with shares reserved', async () => {
      mock(db.position.findUnique).mockResolvedValue(heldPos(5));
      mock(db.order.create).mockResolvedValue(orderRow({ side: 'SELL', type: 'LIMIT', limitPrice: decimal(150) }));
      const res = await place('limit', { ticker: T, side: 'SELL', quantity: 1, limitPrice: 150 });
      expect(res.body).toMatchObject({ status: 'PENDING', executedPrice: null });
      expect(res.body.message).toContain('shares reserved');
      expect(db.trade.create).not.toHaveBeenCalled();
    });
    it('TRD-42 write conflicts are retried, then reported as busy', async () => {
      mock(db.$transaction).mockRejectedValue({ code: 'P2034' });
      const res = await place('market', { ticker: T, side: 'BUY', quantity: 1 });
      expect(res.status).toBe(503);
      expect(res.body.error).toBe('The server is busy. Please try again.');
      expect(db.$transaction).toHaveBeenCalledTimes(3);
    });
    it.each(['market', 'limit'] as const)('TRD-43 unexpected %s failure → 500 without retrying', async (kind) => {
      mock(db.$transaction).mockRejectedValue(new Error('boom'));
      const res = await place(kind, { ticker: T, side: 'BUY', quantity: 1, limitPrice: 100 });
      expect(res.status).toBe(500);
      expect(res.body.error).toBe(`Failed to execute ${kind} order`);
      expect(db.$transaction).toHaveBeenCalledTimes(1);
    });
  });
});

describe('Mocked: settlement helpers', () => {
  beforeEach(() => { jest.clearAllMocks(); });

  describe('getServerPrice', () => {
    it('returns the fresh quote as a Decimal', async () => {
      mock(marketService.getStock).mockResolvedValue({ currentPrice: '123.45', status: 'ok' });
      expect((await getServerPrice(T)).toString()).toBe('123.45');
    });
    it.each([
      [{ currentPrice: '100', status: 'stale' }],
      [{ currentPrice: null, status: 'unavailable' }],
      [{ currentPrice: null, status: 'ok' }],
    ])('is a 503 when the quote is unusable: %j', async (stock) => {
      mock(marketService.getStock).mockResolvedValue(stock);
      await expect(getServerPrice(T)).rejects.toMatchObject({ status: 503 });
    });
    it('is a 503 when the market service throws', async () => {
      mock(marketService.getStock).mockRejectedValue(new Error('Unsupported market symbol'));
      const error = await getServerPrice('NOPE').catch((e) => e);
      expect(error).toBeInstanceOf(OrderError);
      expect(error.status).toBe(503);
    });
  });

  describe('runSerializable', () => {
    const conflict = { code: 'P2034' };
    it('retries write conflicts until it succeeds', async () => {
      mock(db.$transaction).mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict).mockResolvedValueOnce('done');
      await expect(runSerializable(async () => 'unused')).resolves.toBe('done');
      expect(db.$transaction).toHaveBeenCalledTimes(3);
      expect(mock(db.$transaction).mock.calls[0][1]).toEqual({ isolationLevel: Prisma.TransactionIsolationLevel.Serializable });
    });
    it('gives up after the allowed attempts', async () => {
      mock(db.$transaction).mockRejectedValue(conflict);
      await expect(runSerializable(async () => 'x')).rejects.toBe(conflict);
      expect(db.$transaction).toHaveBeenCalledTimes(3);
    });
    it('honours a custom attempt count', async () => {
      mock(db.$transaction).mockRejectedValue(conflict);
      await expect(runSerializable(async () => 'x', 1)).rejects.toBe(conflict);
      expect(db.$transaction).toHaveBeenCalledTimes(1);
    });
    it('does not retry other errors', async () => {
      const boom = new Error('boom');
      mock(db.$transaction).mockRejectedValue(boom);
      await expect(runSerializable(async () => 'x')).rejects.toBe(boom);
      expect(db.$transaction).toHaveBeenCalledTimes(1);
    });
  });

  const makeTx = () => ({
    order: { updateMany: jest.fn().mockResolvedValue({ count: 1 }), aggregate: jest.fn() },
    position: { findUnique: jest.fn().mockResolvedValue(null), upsert: jest.fn(), update: jest.fn(), delete: jest.fn() },
    account: { update: jest.fn() },
    trade: { create: jest.fn() },
  });
  const fillable = (o: object = {}) => ({ id: 'o1', userId: U, ticker: T, side: 'BUY', type: 'MARKET', quantity: decimal(2), limitPrice: null, ...o });
  const pos = (quantity: number, averageCost = 90) => ({ quantity: decimal(quantity), averageCost: decimal(averageCost) });
  const incrementOf = (tx: ReturnType<typeof makeTx>, call = 0) => tx.account.update.mock.calls[call][0].data.cashBalance.increment.toString();

  describe('availableShares', () => {
    it('is zero with no position and no reservations', async () => {
      const tx = makeTx();
      tx.order.aggregate.mockResolvedValue({ _sum: { quantity: null } });
      expect((await availableShares(tx as never, U, T)).toString()).toBe('0');
    });
    it('subtracts shares reserved by pending sells', async () => {
      const tx = makeTx();
      tx.position.findUnique.mockResolvedValue(pos(5));
      tx.order.aggregate.mockResolvedValue({ _sum: { quantity: decimal(2) } });
      expect((await availableShares(tx as never, U, T)).toString()).toBe('3');
    });
  });

  describe('fillOrder', () => {
    it('does nothing when the order was already claimed', async () => {
      const tx = makeTx();
      tx.order.updateMany.mockResolvedValue({ count: 0 });
      await expect(fillOrder(tx as never, fillable() as never, decimal(100))).resolves.toBe(false);
      expect(tx.position.findUnique).not.toHaveBeenCalled();
      expect(tx.trade.create).not.toHaveBeenCalled();
    });
    it('market buy opens a position with no refund', async () => {
      const tx = makeTx();
      await expect(fillOrder(tx as never, fillable() as never, decimal(100))).resolves.toBe(true);
      const { create } = tx.position.upsert.mock.calls[0][0];
      expect([create.quantity.toString(), create.averageCost.toString()]).toEqual(['2', '100']);
      expect(tx.account.update).not.toHaveBeenCalled();
      expect(tx.trade.create.mock.calls[0][0].data).toMatchObject({ type: 'BUY', ticker: T });
      expect(tx.trade.create.mock.calls[0][0].data.totalAmount.toString()).toBe('200');
    });
    it('limit buy filled below its limit refunds the held difference', async () => {
      const tx = makeTx();
      await fillOrder(tx as never, fillable({ type: 'LIMIT', limitPrice: decimal(120) }) as never, decimal(100));
      expect(incrementOf(tx)).toBe('40');
    });
    it('partial sell keeps the remainder and credits proceeds', async () => {
      const tx = makeTx();
      tx.position.findUnique.mockResolvedValue(pos(5));
      await fillOrder(tx as never, fillable({ side: 'SELL' }) as never, decimal(110));
      expect(tx.position.update.mock.calls[0][0].data.quantity.toString()).toBe('3');
      expect(tx.position.delete).not.toHaveBeenCalled();
      expect(incrementOf(tx)).toBe('220');
      expect(tx.trade.create.mock.calls[0][0].data.type).toBe('SELL');
    });
    it('selling everything deletes the position', async () => {
      const tx = makeTx();
      tx.position.findUnique.mockResolvedValue(pos(2));
      await fillOrder(tx as never, fillable({ side: 'SELL' }) as never, decimal(100));
      expect(tx.position.delete).toHaveBeenCalledTimes(1);
      expect(tx.position.update).not.toHaveBeenCalled();
    });
    it.each([[null], [pos(1)]])('sell without enough shares (%j) aborts with 409', async (existing) => {
      const tx = makeTx();
      tx.position.findUnique.mockResolvedValue(existing);
      await expect(fillOrder(tx as never, fillable({ side: 'SELL' }) as never, decimal(100)))
        .rejects.toMatchObject({ status: 409, message: 'Position missing for fill' });
      expect(tx.trade.create).not.toHaveBeenCalled();
    });
  });
});