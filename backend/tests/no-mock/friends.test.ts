import request from 'supertest';
import type { Express } from 'express';
import { loadApp } from '../helpers/loadApp';
import { FAKE_DATE, T, bearer, expireCache } from '../helpers/testkit';
import {
  befriend, countFriendshipsBetween, countRequestsBetween, createUser, db, giveShares, requestFrom, resetDb,
} from '../helpers/fixtures';

// eslint-disable-next-line no-var
var mockMarket = { price: 100, fail: false };
jest.mock('../../src/market/alpaca', () =>
  require('../helpers/stubprovider').createStubProvider(mockMarket));

let app: Express, A: string, B: string, C: string, D: string, E: string;

beforeAll(() => { jest.useFakeTimers(FAKE_DATE); app = loadApp().app as Express; });
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
    await as(B).patch('/api/auth/me', { name: 'Bob Updated' });
    const pending = await as(A).get('/api/friends/requests');
    expect(pending.status).toBe(200);
    expect(pending.body.outgoing[0].recipient.displayName).toBe('Bob Updated');
    await as(B).post(`/api/friends/requests/${body.request.id}/accept`);
    await as(B).patch('/api/auth/me', { name: 'Bob Latest' });
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
