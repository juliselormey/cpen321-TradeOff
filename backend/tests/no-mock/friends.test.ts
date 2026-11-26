import request from 'supertest';
import type { Express } from 'express';
import type * as StubProvider from '../helpers/stubprovider';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache } from '../helpers/testkit';
import {
  befriend, countFriendshipsBetween, countRequestsBetween, createUser, db, giveShares, requestFrom, resetDb,
} from '../helpers/fixtures';

// eslint-disable-next-line no-var
var mockMarket = { price: 100, fail: false };
jest.mock('../../src/market/alpaca', () =>
  jest.requireActual<typeof StubProvider>('../helpers/stubprovider').createStubProvider(mockMarket));

let app: Express, A: string, B: string, C: string, D: string, E: string;

beforeAll(() => { jest.useFakeTimers(FAKE_DATE); app = loadApp().app; });
afterAll(async () => { jest.useRealTimers(); await db.$disconnect(); });
beforeEach(async () => {
  await resetDb();
  mockMarket.price = 100; expireCache();
  [A, B, C, D, E] = [await createUser({ cash: 1000 }), await createUser({ cash: 500 }), await createUser({ cash: 300 }),
    await createUser({ cash: 10000 }), await createUser({ cash: 5000 })];
});

const as = (id: string) => ({
  get: (p: string) => request(app).get(p).set(bearer(id)),
  post: (p: string, b: object = {}) => request(app).post(p).set(bearer(id)).send(b),
  patch: (p: string, b: object = {}) => request(app).patch(p).set(bearer(id)).send(b),
  del: (p: string) => request(app).delete(p).set(bearer(id)),
});
const sendReq = async (from: string, to: string) => {
  const user = await db.user.findUniqueOrThrow({ where: { id: to }, select: { username: true } });
  return as(from).post('/api/friends/requests', { username: user.username });
};
const incoming = async (id: string) => (await as(id).get('/api/friends/requests')).body.incoming;
const outgoing = async (id: string) => (await as(id).get('/api/friends/requests')).body.outgoing;
const friendsOf = async (id: string) => (await as(id).get('/api/friends')).body.friends.map((f: { user: { id: string } }) => f.user.id);

describe('Unmocked API: friend requests', () => {
  it('SOC-N1 send A→B: pending row, visible to both, nobody is a friend yet (UC-14)', async () => {
    expect((await sendReq(A, B)).status).toBe(201);
    expect(await outgoing(A)).toHaveLength(1);
    expect(await incoming(B)).toHaveLength(1);
    expect(await friendsOf(A)).toEqual([]);
    expect(await friendsOf(B)).toEqual([]);
  });
  it('SOC-N2 duplicate send → 409, still one row', async () => {
    await sendReq(A, B);
    expect((await sendReq(A, B)).status).toBe(409);
    expect(await countRequestsBetween(A, B)).toBe(1);
  });
  it('SOC-N3 accept creates a friendship visible to both (UC-15)', async () => {
    const { body } = await sendReq(A, B);
    expect((await as(B).post(`/api/friends/requests/${body.request.id}/accept`)).status).toBe(204);
    expect(await friendsOf(A)).toEqual([B]);
    expect(await friendsOf(B)).toEqual([A]);
    expect(await incoming(B)).toEqual([]);
  });
  it('SOC-N4 decline removes the request without a friendship; A can send again (UC-16)', async () => {
    const { body } = await sendReq(A, B);
    expect((await as(B).del(`/api/friends/requests/${body.request.id}`)).status).toBe(204);
    expect(await friendsOf(A)).toEqual([]);
    expect((await sendReq(A, B)).status).toBe(201);
  });
  it('SOC-N5 cancel removes the request; B’s incoming list is empty (UC-17)', async () => {
    const { body } = await sendReq(A, B);
    expect((await as(A).del(`/api/friends/requests/${body.request.id}`)).status).toBe(204);
    expect(await incoming(B)).toEqual([]);
  });
  it('SOC-N7 a third user cannot accept someone else’s request', async () => {
    const { body } = await sendReq(A, B);
    expect([403, 404]).toContain((await as(C).post(`/api/friends/requests/${body.request.id}/accept`)).status);
    expect(await countFriendshipsBetween(A, B)).toBe(0);
    expect(await incoming(B)).toHaveLength(1);
  });
  it('SOC-N8 A→B and B→A at the same moment never create duplicates', async () => {
    await Promise.all([sendReq(A, B), sendReq(B, A)]);
    expect(await countRequestsBetween(A, B)).toBeLessThanOrEqual(1);
    expect(await countFriendshipsBetween(A, B)).toBeLessThanOrEqual(1);
  });
  it('SOC-N9 accepting twice at once creates one friendship', async () => {
    const { body } = await sendReq(A, B);
    await Promise.all([1, 2].map(() => as(B).post(`/api/friends/requests/${body.request.id}/accept`)));
    expect(await countFriendshipsBetween(A, B)).toBe(1);
  });
  it('separates incoming and outgoing pending requests', async () => {
    await sendReq(A, B);
    await sendReq(C, A);
    const reqsA = await as(A).get('/api/friends/requests');
    expect(reqsA.status).toBe(200);
    expect(reqsA.body.outgoing).toHaveLength(1);
    expect(reqsA.body.incoming).toHaveLength(1);
  });
  it('renaming preserves pending and accepted friendships and uses the new username/displayName', async () => {
    const { body } = await sendReq(A, B);
    const bob = await db.user.findUniqueOrThrow({ where: { id: B }, select: { username: true } });
    await as(B).patch('/api/auth/me', { displayName: 'Bob Updated', username: bob.username });
    const pending = await as(A).get('/api/friends/requests');
    expect(pending.status).toBe(200);
    expect(pending.body.outgoing[0].recipient.displayName).toBe('Bob Updated');
    await as(B).post(`/api/friends/requests/${body.request.id}/accept`);
    await as(B).patch('/api/auth/me', { displayName: 'Bob Latest', username: bob.username });
    const accepted = await as(A).get('/api/friends');
    expect(accepted.status).toBe(200);
    expect(accepted.body.friends[0].user.displayName).toBe('Bob Latest');
  });
});

describe('Unmocked API: DELETE /api/friends/:id', () => {
  it('SOC-N6 removing a friend empties both lists; a new request is possible (UC-18)', async () => {
    const f = await befriend(A, B);
    expect((await as(A).del(`/api/friends/${f.id}`)).status).toBe(204);
    expect(await friendsOf(A)).toEqual([]);
    expect(await friendsOf(B)).toEqual([]);
    expect((await sendReq(A, B)).status).toBe(201);
  });
});

describe('Unmocked API: GET /api/friends/leaderboard', () => {
  const board = async (id: string, q = '') => (await as(id).get(`/api/friends/leaderboard${q}`)).body;

  it('SOC-N10 contains the caller and accepted friends only, ranked by computed totals (UC-19)', async () => {
    await giveShares(B, T, 2, 100); // B: 500 + 2×100 = 700
    await befriend(A, B);
    await befriend(A, C);
    await requestFrom(E, A); // pending only
    const { entries } = await board(A);
    expect(entries.map((e: { user: { id: string } }) => e.user.id)).toEqual([A, B, C]);
    expect(entries.map((e: { totalPortfolioValue: string }) => e.totalPortfolioValue)).toEqual(['1000', '700', '300']);
    expect(JSON.stringify(entries)).not.toContain(D);
    expect(JSON.stringify(entries)).not.toContain(E);
  });
  it('SOC-N11 the caller’s entry equals GET /api/portfolio totalValue', async () => {
    await giveShares(A, T, 3, 90);
    const total = (await as(A).get('/api/portfolio')).body.totalValue;
    expect(Number((await board(A)).entries.find((e: { user: { id: string } }) => e.user.id === A).totalPortfolioValue)).toBe(total);
  });
  it('SOC-N12 a removed friend disappears from the leaderboard', async () => {
    const f = await befriend(A, B);
    await as(A).del(`/api/friends/${f.id}`);
    expect((await board(A)).entries.map((e: { user: { id: string } }) => e.user.id)).toEqual([A]);
  });
});

describe('Unmocked API: friends authentication and input validation', () => {
  it.each<['get' | 'post' | 'delete', string]>([
    ['get', '/api/friends'], ['get', '/api/friends/requests'], ['get', '/api/friends/leaderboard'],
    ['post', '/api/friends/requests'], ['post', '/api/friends/requests/x/accept'],
    ['delete', '/api/friends/requests/x'], ['delete', '/api/friends/x'],
  ])('SOC-N13 %s %s without JWT → 401', async (method, path) => {
    expect((await request(app)[method](path)).status).toBe(401);
  });
  it.each([[{}], [{ username: 12 }], [{ username: 'bad name' }], [{ username: 'ab' }], [{ username: 'a'.repeat(25) }]])(
    'SOC-N14 invalid target %j → 400 and no request is stored', async (body) => {
      expect((await as(A).post('/api/friends/requests', body)).status).toBe(400);
      expect(await db.friendship.count()).toBe(0);
    });
  it('SOC-N15 unknown user → 404, yourself → 400, banned user → 404; nothing is stored', async () => {
    expect((await as(A).post('/api/friends/requests', { username: 'no_such_user' })).status).toBe(404);
    const me = await db.user.findUniqueOrThrow({ where: { id: A }, select: { username: true } });
    expect((await as(A).post('/api/friends/requests', { username: me.username })).status).toBe(400);
    await db.user.update({ where: { id: C }, data: { isBanned: true } });
    expect((await sendReq(A, C)).status).toBe(404);
    expect(await db.friendship.count()).toBe(0);
  });
  it('SOC-N16 the target username is trimmed and case-insensitive; the response shows both profiles', async () => {
    const b = await db.user.findUniqueOrThrow({ where: { id: B }, select: { username: true } });
    const res = await as(A).post('/api/friends/requests', { username: `  ${(b.username as string).toUpperCase()}  ` });
    expect(res.status).toBe(201);
    expect(res.body.request).toEqual(expect.objectContaining({ status: 'PENDING', senderId: A, recipientId: B }));
    expect(res.body.request.sender.id).toBe(A);
    expect(res.body.request.recipient.id).toBe(B);
  });
});

describe('Unmocked API: who may change a friendship', () => {
  it('SOC-N17 the sender cannot accept their own request', async () => {
    const { body } = await sendReq(A, B);
    expect((await as(A).post(`/api/friends/requests/${body.request.id}/accept`)).status).toBe(404);
    expect(await countFriendshipsBetween(A, B)).toBe(0);
    expect(await incoming(B)).toHaveLength(1);
  });
  it('SOC-N18 outsiders cannot decline, cancel or unfriend', async () => {
    const { body } = await sendReq(A, B);
    expect((await as(C).del(`/api/friends/requests/${body.request.id}`)).status).toBe(404);
    expect(await countRequestsBetween(A, B)).toBe(1);
    const f = await befriend(D, E);
    expect((await as(C).del(`/api/friends/${f.id}`)).status).toBe(404);
    expect(await countFriendshipsBetween(D, E)).toBe(1);
  });
  it('SOC-N19 a request cannot be removed as a friend, and a friend cannot be removed as a request', async () => {
    const f = await befriend(A, B);
    expect((await as(A).del(`/api/friends/requests/${f.id}`)).status).toBe(404);
    expect(await countFriendshipsBetween(A, B)).toBe(1);
    const { body } = await sendReq(A, C);
    expect((await as(A).del(`/api/friends/${body.request.id}`)).status).toBe(404);
    expect(await countRequestsBetween(A, C)).toBe(1);
  });
  it('SOC-N20 an unknown id → 404 on accept, decline and unfriend', async () => {
    expect((await as(A).post('/api/friends/requests/does-not-exist/accept')).status).toBe(404);
    expect((await as(A).del('/api/friends/requests/does-not-exist')).status).toBe(404);
    expect((await as(A).del('/api/friends/does-not-exist')).status).toBe(404);
  });
  it('SOC-N21 the friends list shows only the other user’s public profile, from either side', async () => {
    await befriend(A, B);
    for (const [viewer, other] of [[A, B], [B, A]]) {
      const user = (await as(viewer as string).get('/api/friends')).body.friends[0].user;
      expect(user.id).toBe(other);
      expect(Object.keys(user).sort()).toEqual(['avatarUrl', 'displayName', 'id', 'username']);
    }
  });
});

describe('Unmocked API: leaderboard edge cases', () => {
  const board = async (id: string) => (await as(id).get('/api/friends/leaderboard')).body.entries as {
    user: { id: string }; isYou: boolean; totalPortfolioValue: string | null; returnPercent: number | null }[];

  it('SOC-N22 banned friends are excluded', async () => {
    await befriend(A, B);
    await db.user.update({ where: { id: B }, data: { isBanned: true } });
    expect((await board(A)).map((e) => e.user.id)).toEqual([A]);
  });
  it('SOC-N23 a price outage hides only the totals that need a price', async () => {
    await giveShares(B, T, 2, 100);
    await befriend(A, B);
    mockMarket.fail = true; expireCache();
    const entries = await board(A);
    expect(entries.find((e) => e.user.id === A)).toEqual(expect.objectContaining({ totalPortfolioValue: '1000', isYou: true }));
    expect(entries.find((e) => e.user.id === B)).toEqual(expect.objectContaining({ totalPortfolioValue: null, returnPercent: null, isYou: false }));
  });
  it('SOC-N24 is uncached, flags only the caller, and never exposes emails', async () => {
    await befriend(A, B);
    const res = await as(A).get('/api/friends/leaderboard');
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.entries.filter((e: { isYou: boolean }) => e.isYou)).toHaveLength(1);
    expect(JSON.stringify(res.body)).not.toContain('"email"');
  });
  it('SOC-N25 an empty social graph shows just the caller', async () => {
    const entries = await board(A);
    expect(entries).toHaveLength(1);
    expect(entries[0]).toEqual(expect.objectContaining({ isYou: true, totalPortfolioValue: '1000' }));
  });
});