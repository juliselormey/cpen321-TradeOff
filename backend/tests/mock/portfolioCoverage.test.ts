import request from 'supertest';
import jwt from 'jsonwebtoken';
import { Prisma } from '../../src/generated/prisma/client';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';

jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: { getStock: jest.fn() },
}));

const app = createApp();
const secret = 'portfolio-coverage-secret';
const decimal = (value: number) => new Prisma.Decimal(value);

beforeEach(() => {
  jest.resetAllMocks();
  process.env.JWT_SECRET = secret;
  jest.mocked(db.user.findUnique).mockResolvedValue({ id: 'u1', isBanned: false } as never);
  jest.mocked(db.account.findUnique).mockResolvedValue(null);
  jest.mocked(db.position.findMany).mockResolvedValue([{
    userId: 'u1', ticker: 'AAPL', quantity: decimal(2), averageCost: null,
  }] as never);
  jest.mocked(db.trade.findMany).mockResolvedValue([]);
  jest.mocked(db.order.findMany).mockResolvedValue([{
    id: 'o1', ticker: 'MSFT', side: 'SELL', type: 'LIMIT', status: 'PENDING',
    quantity: decimal(1), limitPrice: null, createdAt: new Date('2026-01-01T00:00:00Z'),
  }] as never);
  jest.mocked(marketService.getStock).mockResolvedValue({
    ticker: 'AAPL', status: 'ok', currentPrice: '11',
  } as never);
});

it('serializes an absent account, missing average cost, and a null order limit price', async () => {
  const token = jwt.sign({ sub: 'u1' }, secret);
  const response = await request(app).get('/api/portfolio')
    .set('Authorization', `Bearer ${token}`).expect(200);

  expect(response.body).toEqual(expect.objectContaining({
    cashBalance: 10000,
    startingBalance: 10000,
    holdings: [expect.objectContaining({ averageCost: 0, price: 11, profitLoss: null })],
    pendingOrders: [expect.objectContaining({ limitPrice: null })],
  }));
});

it('returns null percent when the stored starting balance is zero', async () => {
  jest.mocked(db.account.findUnique).mockResolvedValue({
    userId: 'u1', cashBalance: decimal(0), startingBalance: decimal(0),
  } as never);
  jest.mocked(db.position.findMany).mockResolvedValue([]);
  jest.mocked(db.order.findMany).mockResolvedValue([]);

  const token = jwt.sign({ sub: 'u1' }, secret);
  const response = await request(app).get('/api/portfolio')
    .set('Authorization', `Bearer ${token}`).expect(200);
  expect(response.body.returnPercent).toBeNull();
});
