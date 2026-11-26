import { MarketService, marketService } from '../../src/market/service';
import { HISTORY_TTL_MS, REFRESH_INTERVAL_MS } from '../../src/market/catalog';
import { getServerPrice } from '../../src/market/settlement';
import { loadPrices, PriceUnavailableError, valuePortfolio } from '../../src/market/valuation';
import { Prisma } from '../../src/generated/prisma/client';
import { fixture, SYMBOL } from './helpers';

// Settlement imports this module, but price validation must not need a DB.
jest.mock('../../src/prisma/db', () => ({ db: {} }));

beforeEach(() => jest.useFakeTimers({ now: new Date('2026-11-18T15:00:00Z') }));
afterEach(() => { jest.restoreAllMocks(); jest.useRealTimers(); });

async function expiredOutage() {
  const f = fixture();
  const fresh = await f.service.getStock(SYMBOL);
  expect(fresh.status).toBe('ok');
  expect(fresh.history?.status).toBe('ok');
  f.state.fail = true;
  await jest.advanceTimersByTimeAsync(Math.max(HISTORY_TTL_MS, REFRESH_INTERVAL_MS) + 1);
  const stale = await f.service.getStock(SYMBOL);
  expect(stale.status).toBe('stale');
  return { ...f, fresh, stale };
}

function useService(service: MarketService) {
  // Route the production consumer to the real service and cache under test.
  // We do not fake the returned status or bypass the consumer's safety logic.
  jest.spyOn(marketService, 'getStock').mockImplementation(ticker => service.getStock(ticker));
}

test('NFR-3: outage retains retrieved quote/history as stale, never invents data for a cold cache', async () => {
  const f = await expiredOutage();
  expect(f.stale.currentPrice).toBe(f.fresh.currentPrice);
  expect(f.stale.quoteAsOf).toBe(f.fresh.quoteAsOf);
  expect(f.stale.fetchedAt).toBe(f.fresh.fetchedAt);
  expect(f.stale.history?.points).toEqual(f.fresh.history?.points);
  expect(f.stale.history?.status).toBe('stale');
  expect(f.stale.error?.code).toBe('PROVIDER_UNAVAILABLE');
  expect(f.provider.fetchQuotes).toHaveBeenCalledTimes(2);
  expect(f.provider.fetchHistories).toHaveBeenCalledTimes(2);

  const cold = new MarketService(f.provider, () => Date.now());
  const missing = await cold.getStock(SYMBOL);
  expect(missing.status).toBe('unavailable');
  expect(missing.currentPrice).toBeNull();
  expect(missing.history?.status).toBe('unavailable');
  expect(missing.history?.points).toEqual([]);
});

test('NFR-3: provider recovery respects retry delay and refreshes the same instance without restart', async () => {
  const f = await expiredOutage();
  f.state.fail = false;
  f.state.price = 125;
  const retryAt = Math.max(Date.parse(f.stale.retryAt!), Date.parse(f.stale.history!.retryAt!));
  expect(retryAt).toBeGreaterThan(Date.now());
  await jest.advanceTimersByTimeAsync(retryAt - Date.now() - 1);
  const before = await f.service.getStock(SYMBOL);
  expect(before.status).toBe('stale');
  expect(before.currentPrice).toBe('100');
  expect(f.provider.fetchQuotes).toHaveBeenCalledTimes(2);
  expect(f.provider.fetchHistories).toHaveBeenCalledTimes(2);
  await jest.advanceTimersByTimeAsync(2);
  const recovered = await f.service.getStock(SYMBOL);
  expect(recovered.status).toBe('ok');
  expect(recovered.currentPrice).toBe('125');
  expect(recovered.error).toBeNull();
  expect(recovered.retryAt).toBeNull();
  expect(recovered.history?.status).toBe('ok');
  expect(recovered.history?.error).toBeNull();
  expect(recovered.history?.points).toContainEqual(expect.objectContaining({ price: 124 }));
  expect(Date.parse(recovered.fetchedAt!)).toBeGreaterThan(Date.parse(f.fresh.fetchedAt!));
  expect(f.provider.fetchQuotes).toHaveBeenCalledTimes(3);
  expect(f.provider.fetchHistories).toHaveBeenCalledTimes(3);
});

test('NFR-3: trading rejects stale quotes and resumes with a recovered quote', async () => {
  const f = await expiredOutage();
  useService(f.service);
  await expect(getServerPrice(SYMBOL)).rejects.toMatchObject({ status: 503 });
  f.state.fail = false;
  f.state.price = 125;
  await jest.advanceTimersByTimeAsync(Date.parse(f.stale.retryAt!) - Date.now() + 1);
  expect((await getServerPrice(SYMBOL)).toNumber()).toBe(125);
});

test('NFR-3: portfolio valuation rejects stale quotes instead of presenting them as a current value', async () => {
  const f = await expiredOutage();
  useService(f.service);
  const prices = await loadPrices([SYMBOL]);
  expect(() => valuePortfolio({ account: null, pendingBuyOrders: [],
    positions: [{ ticker: SYMBOL, quantity: new Prisma.Decimal(2) }],
  }, prices)).toThrow(PriceUnavailableError);
  expect(prices.get(SYMBOL)).toBeNull();
});

test('NFR-3: cold-cache unavailable prices are rejected by trading and portfolio valuation', async () => {
  const f = fixture();
  f.state.fail = true;
  useService(f.service);
  await expect(getServerPrice(SYMBOL)).rejects.toMatchObject({ status: 503 });
  const prices = await loadPrices([SYMBOL]);
  expect(() => valuePortfolio({ account: null, pendingBuyOrders: [],
    positions: [{ ticker: SYMBOL, quantity: new Prisma.Decimal(2) }],
  }, prices)).toThrow(PriceUnavailableError);
});


test('NFR-3: cold cache recovers and portfolio valuation uses the newly retrieved price', async () => {
  const f = fixture();
  f.state.fail = true;
  const missing = await f.service.getStock(SYMBOL);
  expect(missing.status).toBe('unavailable');
  f.state.fail = false;
  f.state.price = 125;
  const retryAt = Math.max(Date.parse(missing.retryAt!), Date.parse(missing.history!.retryAt!));
  await jest.advanceTimersByTimeAsync(retryAt - Date.now() + 1);
  useService(f.service);
  const prices = await loadPrices([SYMBOL]);
  const valuation = valuePortfolio({ account: null, pendingBuyOrders: [],
    positions: [{ ticker: SYMBOL, quantity: new Prisma.Decimal(2) }],
  }, prices);
  expect(valuation.holdingsValue.toNumber()).toBe(250);
  const recovered = await f.service.getStock(SYMBOL);
  expect(recovered.status).toBe('ok');
  expect(recovered.history?.status).toBe('ok');
});
