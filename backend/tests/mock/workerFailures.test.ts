import { Prisma } from '../../src/generated/prisma/client';
import { db } from '../../src/prisma/db';
import { getServerPrice } from '../../src/market/settlement';
import { processPendingLimitOrders } from '../../src/market/limitWorker';
import { notifyUser } from '../../src/firebase/notifier';

// Mocked: the limit order worker under failures (database errors, price outages, lost races).
// Matching, fills and notifications on the happy path are covered against MySQL in the unmocked suite.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/market/settlement', () => ({ getServerPrice: jest.fn(), fillOrder: jest.fn() }));
jest.mock('../../src/firebase/notifier', () => ({ notifyUser: jest.fn() }));

const orders = jest.mocked(db.order.findMany);
const transaction = jest.mocked(db.$transaction);
const price = jest.mocked(getServerPrice);
const notifier = jest.mocked(notifyUser);
const dec = (v: number) => new Prisma.Decimal(v);
const make = (o: object = {}) => ({ id: 'o1', userId: 'u1', ticker: 'AAPL', type: 'LIMIT', side: 'BUY',
  status: 'PENDING', limitPrice: dec(100), quantity: dec(1), ...o }) as never;
const IDLE = { processedCount: 0, filledCount: 0 };

beforeEach(() => {
  jest.resetAllMocks();
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
  notifier.mockResolvedValue(undefined);
  price.mockResolvedValue(dec(90));
});

describe('Mocked: limit order worker failures', () => {
  it('LWF-1 a settlement transaction that fails leaves the order for the next tick and sends no notification', async () => {
    orders.mockResolvedValue([make()]);
    transaction.mockRejectedValue(new Error('Settlement failed'));
    await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 1, filledCount: 0 });
    expect(notifier).not.toHaveBeenCalled();
    expect(console.error).toHaveBeenCalled();
  });

  it('LWF-2 an order cancelled while the worker runs (settlement reports false) is not counted or notified', async () => {
    orders.mockResolvedValue([make()]);
    transaction.mockResolvedValue(false);
    await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 1, filledCount: 0 });
    expect(notifier).not.toHaveBeenCalled();
  });

  it('LWF-3 a failure reading orders returns an empty result and releases the lock for the next run', async () => {
    orders.mockRejectedValueOnce(new Error('db down'));
    await expect(processPendingLimitOrders()).resolves.toEqual(IDLE);
    expect(console.error).toHaveBeenCalled();
    orders.mockResolvedValue([]);
    await expect(processPendingLimitOrders()).resolves.toEqual(IDLE);
    expect(orders).toHaveBeenCalledTimes(2);
  });

  it('LWF-4 a ticker with no price is skipped; other tickers still fill; each ticker is priced once', async () => {
    orders.mockResolvedValue([make({ id: 'a1' }), make({ id: 'a2' }), make({ id: 'm1', ticker: 'MSFT' })]);
    price.mockImplementation(async (ticker) => {
      if (ticker === 'AAPL') throw new Error('Price unavailable');
      return dec(90);
    });
    transaction.mockResolvedValue(true);
    await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 3, filledCount: 1 });
    expect(price).toHaveBeenCalledTimes(2);
    expect(transaction).toHaveBeenCalledTimes(1);
  });
});