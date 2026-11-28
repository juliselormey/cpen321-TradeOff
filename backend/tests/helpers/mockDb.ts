/**
 * Stand-in for src/prisma/db where every model method is a jest.fn().
 * Used only by the mocked suite, for failure injection (database down, write conflicts).
 * Real database behaviour is covered against MySQL in the unmocked suite.
 *
 * Usage: jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
 */
export function mockDbModule() {
  const cache = new Map<string, unknown>();
  const model = () => {
    const methods = new Map<string, jest.Mock>();
    return new Proxy({}, {
      get: (_target, op) => {
        if (typeof op !== 'string') return undefined;
        let fn = methods.get(op);
        if (!fn) { fn = jest.fn(); methods.set(op, fn); }
        return fn;
      },
    });
  };
  const db = new Proxy({}, {
    get: (_target, key) => {
      if (typeof key !== 'string') return undefined;
      let value = cache.get(key);
      if (!value) { value = key.startsWith('$') ? jest.fn() : model(); cache.set(key, value); }
      return value;
    },
  });
  return { db };
}

/** Execute route snapshot callbacks against the same mocked model methods. */
export function mockTransactions(db: { $transaction: unknown }): void {
  (db.$transaction as jest.Mock).mockImplementation(
    (work: ((tx: unknown) => unknown) | Promise<unknown>[]) =>
      typeof work === 'function' ? work(db) : Promise.all(work),
  );
}
