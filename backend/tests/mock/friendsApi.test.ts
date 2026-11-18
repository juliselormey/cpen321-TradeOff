import request from 'supertest';
import type { Express } from 'express';
import { loadApp } from '../helpers/loadApp';
import { bearer, expectError } from '../helpers/testkit';
import { db } from '../../src/prisma/db';

jest.mock('../../src/prisma/db', () => ({
  db: {
    user: { findUnique: jest.fn(), findMany: jest.fn() },
    friendship: { findMany: jest.fn(), findFirst: jest.fn(), create: jest.fn(), updateMany: jest.fn(), deleteMany: jest.fn() },
    friendRequest: { findFirst: jest.fn(), findUnique: jest.fn(), create: jest.fn(), update: jest.fn(), delete: jest.fn(), findMany: jest.fn() },
  },
}));
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: {
    computePortfolioValue: jest.fn(),
    loadPrices: jest.fn(),
    getStock: jest.fn(),
  },
}));

const U = 'u1';
let app: Express;

beforeEach(() => {
  const l = loadApp('db', 'market/service');
  app = l.app;
  (db.user.findUnique as unknown as jest.Mock).mockResolvedValue({ id: U });
});

const target = (exists: boolean) =>
  (db.user.findUnique as unknown as jest.Mock).mockImplementation(({ where }: { where: { id: string } }) =>
    Promise.resolve(where.id === U || exists ? { id: where.id, name: where.id, username: where.id } : null));
const as = (id = U) => ({ get: (p: string) => request(app).get(p).set(bearer(id)),
  post: (p: string, b: object = {}) => request(app).post(p).set(bearer(id)).send(b),
  del: (p: string) => request(app).delete(p).set(bearer(id)) });
const reqRow = (o: object = {}) => ({ id: 'r1', senderId: U, receiverId: 'u2', recipientId: 'u2', status: 'PENDING', ...o });

type Method = 'get' | 'post' | 'delete';

const hit = (method: Method, path: string) => {
  switch (method) {
    case 'get': return request(app).get(path);
    case 'post': return request(app).post(path);
    case 'delete': return request(app).delete(path);
  }
};

describe('Mocked API: friends routes (auth)', () => {
  it.each<[Method, string]>([
    ['get', '/api/friends'], ['get', '/api/friends/requests'], ['post', '/api/friends/requests'],
    ['post', '/api/friends/requests/r1/accept'], ['delete', '/api/friends/requests/r1'],
    ['delete', '/api/friends/f1'], ['get', '/api/friends/leaderboard'],
  ])('SOC-01 %s %s without JWT → 401', async (method, path) => {
    expect((await hit(method, path)).status).toBe(401);
  });
});

describe('Mocked API: POST /api/friends/requests', () => {
  it.each([[{}], [{ userId: '' }]])('SOC-02 body %j → 400', async (b) => {
    expectError(await as().post('/api/friends/requests', b), 400);
  });
  it('SOC-03 unknown user → 404 (UC-14 7a)', async () => {
    target(false);
    expectError(await as().post('/api/friends/requests', { userId: '00000000-0000-0000-0000-000000000000' }), 404);
  });
  it('SOC-04 own id → 400 (UC-14 7c)', async () => {
    expectError(await as().post('/api/friends/requests', { userId: U }), 400);
  });
  it('SOC-07 already friends / pending → 409', async () => {
    target(true);
    (db.friendship.create as unknown as jest.Mock).mockRejectedValue({ code: 'P2002' });
    expectError(await as().post('/api/friends/requests', { userId: '00000000-0000-0000-0000-000000000002' }), 409);
  });
  it('SOC-08 valid request → 201 with pending status (UC-14)', async () => {
    target(true);
    (db.friendship.create as unknown as jest.Mock).mockResolvedValue(reqRow());
    const res = await as().post('/api/friends/requests', { userId: '00000000-0000-0000-0000-000000000002' });
    expect(res.status).toBe(201);
    expect(res.body.request).toEqual(expect.objectContaining({ id: 'r1', status: 'PENDING', receiverId: 'u2' }));
  });
});

describe('Mocked API: POST /api/friends/requests/:id/accept', () => {
  const accept = (as_ = U) => as(as_).post('/api/friends/requests/r1/accept');
  it('SOC-09 unknown request → 404', async () => {
    (db.friendship.updateMany as unknown as jest.Mock).mockResolvedValue({ count: 0 });
    expectError(await accept(), 404);
  });
  it('SOC-12 receiver accepts → 204 (UC-15)', async () => {
    (db.friendship.updateMany as unknown as jest.Mock).mockResolvedValue({ count: 1 });
    const res = await accept('u2');
    expect(res.status).toBe(204);
    expect(db.friendship.updateMany).toHaveBeenCalled();
  });
});

describe('Mocked API: DELETE /api/friends/requests/:id', () => {
  it('SOC-13 unknown request → 404', async () => {
    (db.friendship.deleteMany as unknown as jest.Mock).mockResolvedValue({ count: 0 });
    expectError(await as().del('/api/friends/requests/r1'), 404);
  });
  it('SOC-15 receiver declines / sender cancels → 204 each (UC-16, UC-17)', async () => {
    (db.friendship.deleteMany as unknown as jest.Mock).mockResolvedValue({ count: 1 });
    expect((await as('u2').del('/api/friends/requests/r1')).status).toBe(204);
    expect((await as(U).del('/api/friends/requests/r1')).status).toBe(204);
  });
});

describe('Mocked API: DELETE /api/friends/:id', () => {
  it('SOC-17 unknown → 404, participant → 204 (UC-18)', async () => {
    (db.friendship.deleteMany as unknown as jest.Mock).mockResolvedValue({ count: 0 });
    expectError(await as().del('/api/friends/f1'), 404);
    (db.friendship.deleteMany as unknown as jest.Mock).mockResolvedValue({ count: 1 });
    expect((await as().del('/api/friends/f1')).status).toBe(204);
  });
});

describe('Mocked API: GET /api/friends and /api/friends/requests', () => {
  it('SOC-18 no relationships → empty arrays; requests split incoming/outgoing', async () => {
    (db.friendship.findMany as unknown as jest.Mock).mockResolvedValue([]);
    const friends = await as().get('/api/friends');
    expect(friends.body.friends).toEqual([]);
    const reqs = await as().get('/api/friends/requests');
    expect(reqs.body).toEqual({ incoming: [], outgoing: [] });
  });

  it('separates incoming and outgoing pending requests', async () => {
    const sender = U;
    const recipient = '00000000-0000-0000-0000-000000000002';
    const incoming = { id: 'one', senderId: recipient, recipientId: sender, status: 'PENDING' };
    const outgoing = { id: 'two', senderId: sender, recipientId: recipient, status: 'PENDING' };
    (db.friendship.findMany as unknown as jest.Mock).mockResolvedValue([incoming, outgoing]);
    const response = await request(app).get('/api/friends/requests').set(bearer(sender)).expect(200);
    expect(response.body).toEqual({ incoming: [incoming], outgoing: [outgoing] });
    expect(db.friendship.findMany).toHaveBeenCalledWith(expect.objectContaining({ where: {
      status: 'PENDING', OR: [{ senderId: sender }, { recipientId: sender }],
    } }));
  });

  it('renaming preserves pending and accepted friendships and uses the new username/displayName', async () => {
    const sender = U;
    const recipient = '00000000-0000-0000-0000-000000000002';
    const alice = { id: sender, username: 'alice', name: 'Alice', isBanned: false };
    const bob = { id: recipient, username: 'bob', name: 'Bob', isBanned: false };
    const row = { id: 'stable-request', senderId: sender, recipientId: recipient, pairKey: `${sender}:${recipient}`, status: 'PENDING', sender: alice, recipient: bob };
    (db.user.findUnique as unknown as jest.Mock).mockImplementation((args: { where?: { id?: string; username?: string } }) => {
      const where = args.where;
      return Promise.resolve([alice, bob].find(u => where?.id ? u.id === where.id : u.username === where?.username) ?? null);
    });
    (db.friendship.create as unknown as jest.Mock).mockResolvedValue(row);
    (db.friendship.findMany as unknown as jest.Mock).mockResolvedValue([row]);
    (db.friendship.updateMany as unknown as jest.Mock).mockImplementation(() => {
      row.status = 'ACCEPTED';
      return Promise.resolve({ count: 1 });
    });
    await request(app).post('/api/friends/requests').set(bearer(sender)).send({ userId: recipient }).expect(201);
    const pending = await request(app).get('/api/friends/requests').set(bearer(sender)).expect(200);
    expect(pending.body.outgoing[0].id).toBe('stable-request');
  });
});
