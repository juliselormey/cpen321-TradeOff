import request from 'supertest';
import type { Express } from 'express';
import { loadApp } from '../helpers/loadApp';
import { bearer, expectError } from '../helpers/testkit';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { Prisma } from '../../src/generated/prisma/client';

const decimal = (v: number) => new Prisma.Decimal(v);

jest.mock('../../src/prisma/db', () => ({
  db: {
    user: { findUnique: jest.fn() },
    account: { findUnique: jest.fn() },
    position: { findMany: jest.fn() },
    order: { findMany: jest.fn() },
    trade: { findMany: jest.fn() },
  },
}));
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: {
    getStock: jest.fn(),
  },
}));

interface PositionIn { userId: string; ticker: string; quantity: number; averageCost: number }
interface OrderIn {
  id: string; userId: string; ticker: string; side: string; type: string; quantity: number;
  limitPrice?: number | null; status: string; createdAt?: Date | string;
}
interface TradeIn {
  id: string; userId: string; ticker: string; type: string; quantity: number;
  pricePerShare: number; totalAmount: number; createdAt?: Date | string;
}

const U = 'u1';
let app: Express;

beforeEach(() => {
  const l = loadApp('db', 'market/service');
  app = l.app;
  (db.user.findUnique as unknown as jest.Mock).mockResolvedValue({ id: U });
  (marketService.getStock as unknown as jest.Mock).mockImplementation((t: string) => Promise.resolve({
    symbol: t,
    ticker: t,
    companyName: `${t} Inc`,
    currentPrice: t === 'AAPL' ? '100' : '50',
    status: 'ok',
    quoteAsOf: new Date().toISOString(),
  }));
});

const stub = ({ cash = 1000, positions = [] as PositionIn[], orders = [] as OrderIn[], trades = [] as TradeIn[] } = {}) => {
  (db.account.findUnique as unknown as jest.Mock).mockResolvedValue({ userId: U, cashBalance: decimal(cash), startingBalance: decimal(1000) });
  (db.position.findMany as unknown as jest.Mock).mockResolvedValue(positions.map((p) => ({ ...p, quantity: decimal(p.quantity), averageCost: decimal(p.averageCost) })));
  (db.order.findMany as unknown as jest.Mock).mockResolvedValue(orders.map((o) => ({ ...o, quantity: decimal(o.quantity), limitPrice: o.limitPrice != null ? decimal(o.limitPrice) : null, createdAt: new Date(o.createdAt ?? Date.now()) })));
  (db.trade.findMany as unknown as jest.Mock).mockResolvedValue(trades.map((t) => ({ ...t, quantity: decimal(t.quantity), pricePerShare: decimal(t.pricePerShare), totalAmount: decimal(t.totalAmount), createdAt: new Date(t.createdAt ?? Date.now()) })));
};
const get = () => request(app).get('/api/portfolio').set(bearer(U));
const aapl: PositionIn = { userId: U, ticker: 'AAPL', quantity: 2, averageCost: 90 };
const pendingBuy: OrderIn = { id: 'o1', userId: U, ticker: 'AAPL', side: 'BUY', type: 'LIMIT', quantity: 2, limitPrice: 90, status: 'PENDING', createdAt: new Date() };

describe('Mocked API: GET /api/portfolio', () => {
  it('PF-01 no JWT → 401', async () => {
    expect((await request(app).get('/api/portfolio')).status).toBe(401);
  });
  it('PF-02 no holdings → cash only, empty holdings, no error (UC-13 step 8)', async () => {
    stub({ cash: 1000 });
    const res = await get();
    expect(res.status).toBe(200);
    expect(res.body.cashBalance).toBe(1000);
    expect(res.body.holdings).toEqual([]);
    expect(res.body.totalValue).toBe(1000);
    expect(res.body.profitLoss).toBe(0);
  });
  it('PF-03 prices unavailable → 503 (UC-13 3a)', async () => {
    stub({ positions: [aapl] });
    (marketService.getStock as unknown as jest.Mock).mockRejectedValue(new Error('down'));
    expectError(await get(), 503);
  });
  it('PF-04 total = cash + holdings at current price + reserved cash', async () => {
    stub({ cash: 1000, positions: [aapl], orders: [pendingBuy] });
    const res = await get();
    expect(res.body.totalValue).toBe(1000 + 2 * 100 + 180);
    expect(res.body.reservedCash).toBe(180);
  });
  it('PF-05 holding profit/loss = (price − avgCost) × quantity', async () => {
    stub({ positions: [aapl] });
    const res = await get();
    expect(res.body.holdings[0]).toEqual(expect.objectContaining({ ticker: 'AAPL', value: 200, profitLoss: 20 }));
  });
  it('PF-06 allocation percentages sum to 100', async () => {
    stub({ cash: 800, positions: [aapl] });
    const res = await get();
    const sum = res.body.allocation.reduce((s: number, a: { percent: number }) => s + a.percent, 0);
    expect(sum).toBeCloseTo(100, 0);
  });
  it('PF-07 trades come back in the order stored; none → [] (UC-14)', async () => {
    const t = (id: string, at: string): TradeIn => ({ id, userId: U, ticker: 'AAPL', type: 'BUY', quantity: 1, pricePerShare: 100, totalAmount: 100, createdAt: at });
    stub({ trades: [t('t3', '2026-11-17'), t('t2', '2026-11-16'), t('t1', '2026-11-15')] });
    expect((await get()).body.recentTrades.map((x: { id: string }) => x.id)).toEqual(['t3', 't2', 't1']);
    stub({ trades: [] });
    expect((await get()).body.recentTrades).toEqual([]);
  });
  it('PF-08 database error → 500 with a generic message', async () => {
    (db.account.findUnique as unknown as jest.Mock).mockRejectedValue(new Error('db down'));
    expectError(await get(), 500);
  });
});