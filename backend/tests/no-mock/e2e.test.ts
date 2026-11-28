import express from 'express';
import request from 'supertest';
import { createE2ERouter } from '../../src/routes/e2e';
import { bearer } from '../helpers/testkit';
import { createUser, db, getCash, getQty, giveShares, resetDb } from '../helpers/fixtures';

const E2E_URL = 'mysql://localhost/tradeoff_e2e_tests';

/**
 * The router only inspects the database *name* when it is created (its safety guard); every query still goes to
 * the real test database that `db` is already connected to. The environment is restored straight after creation.
 * Pass null to leave a variable unset.
 */
function e2eApp(url: string | null = E2E_URL, enabled: string | null = 'true') {
  const saved = { url: process.env.DATABASE_URL, enabled: process.env.E2E_ENABLED };
  try {
    if (enabled === null) delete process.env.E2E_ENABLED; else process.env.E2E_ENABLED = enabled;
    if (url === null) delete process.env.DATABASE_URL; else process.env.DATABASE_URL = url;
    return express().use(express.json()).use('/__e2e', createE2ERouter());
  } finally {
    if (saved.url === undefined) delete process.env.DATABASE_URL; else process.env.DATABASE_URL = saved.url;
    if (saved.enabled === undefined) delete process.env.E2E_ENABLED; else process.env.E2E_ENABLED = saved.enabled;
  }
}
const setup = (id: string, body: object = {}) => request(e2eApp()).post('/__e2e/leaderboard/setup').set(bearer(id)).send(body);
const cleanup = (id: string) => request(e2eApp()).post('/__e2e/leaderboard/cleanup').set(bearer(id));
const fixturesOf = (id: string) => db.user.findMany({
  where: { googleSub: { startsWith: `e2e-leaderboard:${id}:` } },
  include: { account: true, positions: true }, orderBy: { googleSub: 'asc' },
});
const friendshipCount = (id: string, status?: 'ACCEPTED' | 'PENDING') =>
  db.friendship.count({ where: { OR: [{ senderId: id }, { recipientId: id }], ...(status ? { status } : {}) } });

beforeEach(resetDb);
afterAll(() => db.$disconnect());

describe('Unmocked: e2e fixture router guards', () => {
  it('E2E-N1 disabled by default: every route is a 404 and nothing is touched', async () => {
    const id = await createUser({ cash: 321 });
    await request(e2eApp(E2E_URL, null)).post('/__e2e/account/reset').set(bearer(id)).expect(404);
    await request(e2eApp(E2E_URL, 'false')).post('/__e2e/leaderboard/setup').set(bearer(id)).expect(404);
    expect(await getCash(id)).toBe(321);
  });
  it('E2E-N2 refuses to start against a database that is not tradeoff_e2e_<name>', () => {
    expect(() => e2eApp('mysql://localhost/tradeoff')).toThrow('disposable');
    expect(() => e2eApp('mysql://localhost/tradeoff_e2e_UPPER')).toThrow('disposable');
  });
  it('E2E-N3 refuses to start without a DATABASE_URL', () => {
    expect(() => e2eApp(null)).toThrow();
  });
  it.each([
    ['post', '/__e2e/leaderboard/setup'], ['post', '/__e2e/leaderboard/cleanup'], ['post', '/__e2e/account/reset'],
    ['get', '/__e2e/account/snapshot'], ['get', '/__e2e/leaderboard/snapshot'],
  ] as const)('E2E-N4 %s %s requires authentication', async (method, path) => {
    expect((await request(e2eApp())[method](path)).status).toBe(401);
  });
});

describe('Unmocked: e2e leaderboard fixtures', () => {
  it('E2E-N5 setup creates eight real participants with accounts, one holding, and seven friendships', async () => {
    const id = await createUser();
    const res = await setup(id);
    expect(res.status).toBe(200);
    expect(res.body.ready).toBe(true);
    expect(res.body.users).toHaveLength(8);
    const fixtures = await fixturesOf(id);
    expect(fixtures).toHaveLength(8);
    expect(fixtures.map((u) => Number(u.account?.cashBalance))).toEqual([12000, 11000, 10000, 9000, 8000, 7000, 6000, 5000]);
    expect(fixtures.map((u) => Number(u.account?.startingBalance))).toEqual([20000, 4000, 20000, 4000, 20000, 4000, 20000, 4000]);
    expect(fixtures[0]?.positions.map((p) => p.ticker)).toEqual(['MSFT']);
    expect(fixtures.slice(1).every((u) => u.positions.length === 0)).toBe(true);
    expect(await friendshipCount(id, 'ACCEPTED')).toBe(6);
    expect(await friendshipCount(id, 'PENDING')).toBe(1);
  });
  it('E2E-N6 setup is repeatable: it replaces the fixtures instead of duplicating them', async () => {
    const id = await createUser();
    await setup(id);
    await setup(id);
    expect(await fixturesOf(id)).toHaveLength(8);
    expect(await friendshipCount(id)).toBe(7);
  });
  it('E2E-N7 an empty setup removes earlier fixtures and the caller’s friendships, and creates none', async () => {
    const id = await createUser();
    const other = await createUser();
    await setup(id);
    await db.friendship.create({ data: { senderId: id, recipientId: other, pairKey: [id, other].sort().join(':'), status: 'ACCEPTED' } });
    const res = await setup(id, { empty: true });
    expect(res.status).toBe(200);
    expect(res.body.users).toEqual([]);
    expect(await fixturesOf(id)).toHaveLength(0);
    expect(await friendshipCount(id)).toBe(0);
  });
  it('E2E-N8 a caller without a username gets a temporary one that cleanup takes back; an existing one is kept', async () => {
    const bare = await createUser();
    await db.user.update({ where: { id: bare }, data: { username: null } });
    await setup(bare, { empty: true });
    expect((await db.user.findUniqueOrThrow({ where: { id: bare } })).username).toMatch(/^e2e_[a-f0-9]{12}_self$/);
    await cleanup(bare).expect(200);
    expect((await db.user.findUniqueOrThrow({ where: { id: bare } })).username).toBeNull();

    const named = await createUser({ username: 'keep_me' });
    await setup(named, { empty: true });
    await cleanup(named).expect(200);
    expect((await db.user.findUniqueOrThrow({ where: { id: named } })).username).toBe('keep_me');
  });
  it('E2E-N9 cleanup only removes the caller’s own fixtures', async () => {
    const first = await createUser();
    const second = await createUser();
    await setup(first);
    await setup(second);
    await cleanup(first).expect(200, { ready: true });
    expect(await fixturesOf(first)).toHaveLength(0);
    expect(await fixturesOf(second)).toHaveLength(8);
  });
});

describe('Unmocked: e2e snapshots and reset', () => {
  it.each(['/__e2e/account/snapshot', '/__e2e/leaderboard/snapshot'])('E2E-N10 %s returns the caller and fixture rows only', async (path) => {
    const id = await createUser();
    const stranger = await createUser();
    await giveShares(stranger, 'AAPL', 4, 100);
    await setup(id);
    const res = await request(e2eApp()).get(path).set(bearer(id));
    expect(res.status).toBe(200);
    expect(Object.keys(res.body).sort()).toEqual(['accounts', 'friendships', 'orders', 'positions', 'trades']);
    expect(res.body.accounts.length).toBeGreaterThanOrEqual(8);
    expect(res.body.accounts.some((a: { userId: string }) => a.userId === stranger)).toBe(false);
    expect(res.body.positions.some((p: { userId: string }) => p.userId === stranger)).toBe(false);
    expect(res.body.friendships).toHaveLength(7);
  });
  it('E2E-N11 reset wipes only the authenticated user’s trading data, whatever user ID the body names', async () => {
    const id = await createUser();
    const other = await createUser();
    await giveShares(id, 'AAPL', 3, 100);
    await giveShares(other, 'AAPL', 2, 100);
    await db.account.update({ where: { userId: id }, data: { cashBalance: 123 } });
    await db.order.create({ data: { userId: id, ticker: 'AAPL', side: 'BUY', type: 'LIMIT', status: 'PENDING', quantity: 1, limitPrice: 90 } });
    await db.trade.create({ data: { userId: id, ticker: 'AAPL', type: 'BUY', quantity: 1, pricePerShare: 100, totalAmount: 100 } });
    await request(e2eApp()).post('/__e2e/account/reset').set(bearer(id)).send({ userId: other }).expect(200, { ready: true });
    expect(await getCash(id)).toBe(10000);
    expect(await getQty(id, 'AAPL')).toBe(0);
    expect(await db.order.count({ where: { userId: id } })).toBe(0);
    expect(await db.trade.count({ where: { userId: id } })).toBe(0);
    expect(await getQty(other, 'AAPL')).toBe(2);
  });
  it('E2E-N12 reset recreates a missing account with the starting balance', async () => {
    const id = await createUser();
    await db.account.deleteMany({ where: { userId: id } });
    await request(e2eApp()).post('/__e2e/account/reset').set(bearer(id)).expect(200);
    const account = await db.account.findUniqueOrThrow({ where: { userId: id } });
    expect([Number(account.cashBalance), Number(account.startingBalance)]).toEqual([10000, 10000]);
  });
});