import jwt from 'jsonwebtoken';
import type { Response } from 'supertest';

export const SECRET = process.env.JWT_SECRET ?? 'test-secret';
export const SYMBOLS = ['AAPL', 'MSFT', 'GOOGL', 'AMZN', 'TSLA', 'NVDA'];
export const T = SYMBOLS[0]!;
export const UNSUPPORTED = 'ZZZZ';
export const CACHE_MS = 8000;

export const FAKE_DATE: any = {
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setInterval', 'clearInterval',
    'setTimeout', 'clearTimeout', 'queueMicrotask', 'performance', 'hrtime'],
};
export const advanceMs = (ms: number) => { jest.setSystemTime(Date.now() + ms); };
export const expireCache = () => { advanceMs(CACHE_MS + 1); };

export const tokenFor = (id: string, opts: jwt.SignOptions = { expiresIn: '1h' }) =>
  jwt.sign({ sub: id }, SECRET, opts);
export const bearer = (id: string) => ({ Authorization: `Bearer ${tokenFor(id)}` });
export const expiredBearer = (id: string) => ({ Authorization: `Bearer ${tokenFor(id, { expiresIn: -10 })}` });
export const tamperedBearer = (id: string) => ({ Authorization: `Bearer ${tokenFor(id).slice(0, -3)}abc` });

export const makeQuotes = (price = 100) =>
  Object.fromEntries(
    SYMBOLS.map((symbol) => [
      symbol,
      { price, change: 1, percentageChange: 1, quoteAsOf: new Date(Date.now() + 10000).toISOString() },
    ])
  );

export const HISTORY = [
  { timestamp: '2026-11-16T14:30:00Z', price: 99 },
  { timestamp: '2026-11-17T14:30:00Z', price: 100 },
];

export const expectError = (res: Response, status: number) => {
  expect(res.status).toBe(status);
  expect(typeof res.body.error).toBe('string');
  expect(JSON.stringify(res.body)).not.toMatch(/stack|\.ts:\d+/);
};
