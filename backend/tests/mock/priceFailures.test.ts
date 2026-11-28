import request from 'supertest';
import jwt from 'jsonwebtoken';
import { createApp } from '../../src/app';
import { Prisma } from '../../src/generated/prisma/client';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { LIVE_SYMBOLS } from '../../src/market/catalog';
import { loadPrices } from '../../src/market/valuation';
import { mockTransactions } from '../helpers/mockDb';

// Mocked: Alpaca-derived quotes are bad (stale, zero, NaN, missing) or the lookup fails.
// Healthy valuation, trading and ranking are covered against MySQL in the unmocked suite.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: { getStock: jest.fn() },
}));

const secret = 'price-failures-secret';
const app = createApp();
const auth = () => `Bearer ${jwt.sign({ sub: 'u1' }, secret)}`;
const m = (f: unknown) => f as jest.Mock;
const dec = (v: number) => new Prisma.Decimal(v);
const getStock = m(marketService.getStock);
const SYMBOL = LIVE_SYMBOLS[0] as string;

type Quote = { currentPrice: string | null; status: string } | Error;
const badQuotes: [string, Quote][] = [
  ['a stale quote', { currentPrice: '120', status: 'stale' }],
  ['an unavailable quote', { currentPrice: null, status: 'unavailable' }],
  ['an ok quote without a price', { currentPrice: null, status: 'ok' }],
  ['a zero price', { currentPrice: '0', status: 'ok' }],
  ['a negative price', { currentPrice: '-5', status: 'ok' }],
  ['a NaN price', { currentPrice: 'NaN', status: 'ok' }],
  ['a garbage price', { currentPrice: 'abc', status: 'ok' }],
  ['a failed lookup', new Error('Unsupported market symbol')],
];
const arrange = (quote: Quote) => (quote instanceof Error ? getStock.mockRejectedValue(quote) : getStock.mockResolvedValue(quote));

beforeEach(() => {
  jest.resetAllMocks();
  mockTransactions(db);
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
  process.env.JWT_SECRET = secret;
  m(db.user.findUnique).mockImplementation(async (args: any) => ({ id: args?.where?.id ?? 'u1', isBanned: false }));
});

describe('Mocked: loadPrices with bad quotes', () => {
  it('PRC-1 looks up each distinct ticker once and keeps good prices', async () => {
    getStock.mockResolvedValue({ currentPrice: '120', status: 'ok' });
    const prices = await loadPrices(['AAPL', 'AAPL', 'MSFT']);
    expect(getStock).toHaveBeenCalledTimes(2);
    expect(prices.get('AAPL')?.toString()).toBe('120');
    expect(prices.size).toBe(2);
  });
  it.each(badQuotes)('PRC-2 maps %s to "no price"', async (_name, quote) => {
    arrange(quote);
    expect((await loadPrices(['AAPL'])).get('AAPL')).toBeNull();
  });
});

describe('Mocked API: routes that need a price', () => {
  it.each(badQuotes)('PRC-3 portfolio with %s → 503', async (_name, quote) => {
    arrange(quote);
    m(db.account.findUnique).mockResolvedValue({ userId: 'u1', cashBalance: dec(1000), startingBalance: dec(1000) });
    m(db.position.findMany).mockResolvedValue([{ userId: 'u1', ticker: 'AAPL', quantity: dec(2), averageCost: dec(90) }]);
    m(db.trade.findMany).mockResolvedValue([]);
    m(db.order.findMany).mockResolvedValue([]);
    const res = await request(app).get('/api/portfolio').set('Authorization', auth());
    expect(res.status).toBe(503);
    expect(res.body.error).toMatch(/unavailable/i);
  });

  it.each(badQuotes)('PRC-4 a market or limit order with %s → 503 and no transaction', async (_name, quote) => {
    arrange(quote);
    for (const [kind, extra] of [['market', {}], ['limit', { limitPrice: 100 }]] as const) {
      const res = await request(app).post(`/api/trades/execute/${kind}`).set('Authorization', auth())
        .send({ ticker: SYMBOL, side: 'buy', quantity: 1, ...extra });
      expect(res.status).toBe(503);
      expect(res.body.error).toBe(`Price unavailable for ${SYMBOL}`);
    }
    expect(db.$transaction).not.toHaveBeenCalled();
  });

  it.each(badQuotes)('PRC-5 leaderboard with %s hides only the totals that need a price', async (_name, quote) => {
    arrange(quote);
    const base = { avatarUrl: null, orders: [], account: { cashBalance: dec(500), startingBalance: dec(10000) } };
    m(db.user.findMany).mockResolvedValue([
      { ...base, id: 'u1', username: 'me', displayName: 'Me', positions: [] },
      { ...base, id: 'u2', username: 'friend', displayName: 'Friend', positions: [{ ticker: 'AAPL', quantity: dec(10) }] },
    ]);
    const res = await request(app).get('/api/friends/leaderboard').set('Authorization', auth());
    expect(res.status).toBe(200);
    expect(res.body.entries[0]).toMatchObject({ totalPortfolioValue: '500', isYou: true });
    expect(res.body.entries[1]).toMatchObject({ totalPortfolioValue: null, returnPercent: null, isYou: false });
  });
});
