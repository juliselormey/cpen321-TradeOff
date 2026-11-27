import { Prisma } from '../../src/generated/prisma/client';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { OrderError, fillOrder, getServerPrice, runSerializable } from '../../src/market/settlement';

// Mocked: settlement under failures (no usable price, write conflicts, lost races, corrupted rows).
// Successful fills are covered against MySQL in the unmocked suite.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/market/service', () => ({ marketService: { getStock: jest.fn() } }));

const m = (f: unknown) => f as jest.Mock;
const dec = (v: number) => new Prisma.Decimal(v);

beforeEach(() => jest.resetAllMocks());

describe('Mocked: getServerPrice failures', () => {
  it.each([
    ['a stale quote', { currentPrice: '100', status: 'stale' }],
    ['an unavailable quote', { currentPrice: null, status: 'unavailable' }],
    ['an ok quote without a price', { currentPrice: null, status: 'ok' }],
  ])('SET-1 %s → 503', async (_name, stock) => {
    m(marketService.getStock).mockResolvedValue(stock);
    await expect(getServerPrice('AAPL')).rejects.toMatchObject({ status: 503, message: 'Price unavailable for AAPL' });
  });
  it('SET-2 a failing lookup → 503 OrderError', async () => {
    m(marketService.getStock).mockRejectedValue(new Error('Unsupported market symbol'));
    const error = await getServerPrice('NOPE').catch((e) => e);
    expect(error).toBeInstanceOf(OrderError);
    expect(error.status).toBe(503);
  });
});

describe('Mocked: runSerializable write conflicts', () => {
  const conflict = { code: 'P2034' };
  it('SET-3 retries a conflict until it clears', async () => {
    m(db.$transaction).mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict).mockResolvedValueOnce('done');
    await expect(runSerializable(async () => 'unused')).resolves.toBe('done');
    expect(db.$transaction).toHaveBeenCalledTimes(3);
    expect(m(db.$transaction).mock.calls[0][1]).toEqual({ isolationLevel: Prisma.TransactionIsolationLevel.Serializable });
  });
  it('SET-4 gives up after the allowed attempts', async () => {
    m(db.$transaction).mockRejectedValue(conflict);
    await expect(runSerializable(async () => 'x')).rejects.toBe(conflict);
    expect(db.$transaction).toHaveBeenCalledTimes(3);
  });
  it('SET-5 honours a custom attempt count', async () => {
    m(db.$transaction).mockRejectedValue(conflict);
    await expect(runSerializable(async () => 'x', 1)).rejects.toBe(conflict);
    expect(db.$transaction).toHaveBeenCalledTimes(1);
  });
  it('SET-6 never retries other errors', async () => {
    const boom = new Error('boom');
    m(db.$transaction).mockRejectedValue(boom);
    await expect(runSerializable(async () => 'x')).rejects.toBe(boom);
    expect(db.$transaction).toHaveBeenCalledTimes(1);
  });
});

describe('Mocked: fillOrder lost races and corrupted state', () => {
  const makeTx = () => ({
    order: { updateMany: jest.fn().mockResolvedValue({ count: 1 }) },
    position: { findUnique: jest.fn().mockResolvedValue(null), upsert: jest.fn(), update: jest.fn(), delete: jest.fn() },
    account: { update: jest.fn() },
    trade: { create: jest.fn() },
  });
  const sell = { id: 'o1', userId: 'u1', ticker: 'AAPL', side: 'SELL', type: 'MARKET', quantity: dec(2), limitPrice: null };

  it('SET-7 an order that is no longer pending is left alone (returns false)', async () => {
    const tx = makeTx();
    tx.order.updateMany.mockResolvedValue({ count: 0 });
    await expect(fillOrder(tx as never, sell as never, dec(100))).resolves.toBe(false);
    expect(tx.position.findUnique).not.toHaveBeenCalled();
    expect(tx.trade.create).not.toHaveBeenCalled();
  });
  it.each([[null], [{ quantity: dec(1), averageCost: dec(90) }]])(
    'SET-8 a sell fill without enough shares (%j) aborts with 409 and writes no trade', async (existing) => {
      const tx = makeTx();
      tx.position.findUnique.mockResolvedValue(existing);
      await expect(fillOrder(tx as never, sell as never, dec(100)))
        .rejects.toMatchObject({ status: 409, message: 'Position missing for fill' });
      expect(tx.trade.create).not.toHaveBeenCalled();
    });
});