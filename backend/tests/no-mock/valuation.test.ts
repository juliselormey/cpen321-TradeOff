import { Prisma } from '../../src/generated/prisma/client';
import { PriceUnavailableError, STARTING_BALANCE, valuePortfolio } from '../../src/market/valuation';

// valuePortfolio is a pure function: no database, no market, nothing to mock.
const dec = (v: number) => new Prisma.Decimal(v);
const acct = (cash: number, starting: number) => ({ cashBalance: dec(cash), startingBalance: dec(starting) });

describe('valuePortfolio', () => {
  it('VAL-1 defaults to the starting balance when the user has no account', () => {
    const v = valuePortfolio({ account: null, positions: [], pendingBuyOrders: [] }, new Map());
    expect(v.totalValue.equals(STARTING_BALANCE)).toBe(true);
    expect(v.profitLoss.toString()).toBe('0');
    expect(v.returnPercent?.toString()).toBe('0');
  });
  it('VAL-2 adds reserved buy cash and values holdings at the current price', () => {
    const v = valuePortfolio({
      account: acct(500, 1000),
      positions: [{ ticker: 'AAPL', quantity: dec(10), averageCost: dec(40) }],
      pendingBuyOrders: [{ quantity: dec(2), limitPrice: dec(100) }, { quantity: dec(5), limitPrice: null }],
    }, new Map([['AAPL', dec(50)]]));
    expect(v.reservedCash.toString()).toBe('200');
    expect(v.holdingsValue.toString()).toBe('500');
    expect(v.totalValue.toString()).toBe('1200');
    expect(v.profitLoss.toString()).toBe('200');
    expect(v.returnPercent?.toString()).toBe('20');
    expect(v.holdings[0]?.profitLoss?.toString()).toBe('100');
    expect(v.allocation[0]?.label).toBe('Cash');
    expect(v.allocation[0]?.percent).toBeCloseTo((700 / 1200) * 100);
    expect(v.allocation[1]?.label).toBe('AAPL');
    expect(v.allocation[1]?.percent).toBeCloseTo((500 / 1200) * 100);
  });
  it('VAL-3 a holding without a cost basis has unknown profit/loss', () => {
    const v = valuePortfolio({ account: acct(0, 100), positions: [{ ticker: 'AAPL', quantity: dec(1) }],
      pendingBuyOrders: [] }, new Map([['AAPL', dec(50)]]));
    expect(v.holdings[0]).toMatchObject({ averageCost: null, profitLoss: null });
  });
  it('VAL-4 empty positions are ignored even without a price', () => {
    const v = valuePortfolio({ account: acct(100, 100), positions: [{ ticker: 'AAPL', quantity: dec(0) }],
      pendingBuyOrders: [] }, new Map());
    expect(v.holdings).toEqual([]);
  });
  it.each([[new Map()], [new Map([['AAPL', null]])]])('VAL-5 a held ticker with no price throws (%j)', (prices) => {
    const run = () => valuePortfolio({ account: acct(100, 100), positions: [{ ticker: 'AAPL', quantity: dec(1) }],
      pendingBuyOrders: [] }, prices as Map<string, Prisma.Decimal | null>);
    expect(run).toThrow(PriceUnavailableError);
    expect(run).toThrow('Price unavailable for AAPL');
  });
  it('VAL-6 a zero-value portfolio has zero allocation, and no return when it started from zero', () => {
    const empty = valuePortfolio({ account: acct(0, 100), positions: [], pendingBuyOrders: [] }, new Map());
    expect(empty.allocation).toEqual([{ label: 'Cash', percent: 0 }]);
    expect(empty.profitLoss.toString()).toBe('-100');
    expect(valuePortfolio({ account: acct(100, 0), positions: [], pendingBuyOrders: [] }, new Map()).returnPercent).toBeNull();
  });
});