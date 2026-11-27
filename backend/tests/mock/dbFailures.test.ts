import request from 'supertest';
import jwt from 'jsonwebtoken';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import { marketService } from '../../src/market/service';
import { LIVE_SYMBOLS } from '../../src/market/catalog';

// Mocked: the database is made to fail. Normal behaviour of every route is covered against MySQL
// in the unmocked suite; this file proves each route degrades to a clean error instead of crashing.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/market/service', () => ({
  ...jest.requireActual('../../src/market/service'),
  marketService: { getStock: jest.fn() },
}));

const secret = 'db-failures-secret';
const app = createApp();
const auth = () => `Bearer ${jwt.sign({ sub: 'u1' }, secret)}`;
const m = (f: unknown) => f as jest.Mock;
const fail = (f: unknown) => m(f).mockRejectedValue(new Error('db down'));
// requireAuth looks the user up first; this lets that lookup pass and fails the handler's own lookup.
const failHandlerLookup = () =>
  m(db.user.findUnique).mockResolvedValueOnce({ id: 'u1', isBanned: false }).mockRejectedValueOnce(new Error('db down'));

const SYMBOL = LIVE_SYMBOLS[0] as string;
const UUID = '00000000-0000-4000-8000-000000000001';
const INSTALLATION = 'cABCDEFGHIJKLMNOPQRSTU';

beforeEach(() => {
  jest.resetAllMocks();
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
  process.env.JWT_SECRET = secret;
  m(db.user.findUnique).mockResolvedValue({ id: 'u1', isBanned: false });
  m(marketService.getStock).mockResolvedValue({ currentPrice: '100', status: 'ok' });
});

type Method = 'get' | 'post' | 'put' | 'patch' | 'delete';
type Case = [name: string, method: Method, path: string, body: object | undefined, arrange: () => void, error: string];
const cases: Case[] = [
  ['position lookup', 'get', `/api/trades/position/${SYMBOL}`, undefined, () => fail(db.account.findUnique), 'Failed to fetch position'],
  ['pending orders', 'get', '/api/trades/orders/pending', undefined, () => fail(db.order.findMany), 'Server error'],
  ['order cancel', 'post', `/api/trades/orders/${UUID}/cancel`, undefined, () => fail(db.$transaction), 'Failed to cancel order'],
  ['market order', 'post', '/api/trades/execute/market', { ticker: SYMBOL, side: 'buy', quantity: 1 }, () => fail(db.$transaction), 'Failed to execute market order'],
  ['limit order', 'post', '/api/trades/execute/limit', { ticker: SYMBOL, side: 'buy', quantity: 1, limitPrice: 100 }, () => fail(db.$transaction), 'Failed to execute limit order'],
  ['portfolio', 'get', '/api/portfolio', undefined, () => fail(db.account.findUnique), 'Failed to fetch portfolio summary'],
  ['device registration', 'put', '/api/notif/device', { installationId: INSTALLATION }, () => fail(db.notificationDevice.upsert), 'Server error'],
  ['device removal', 'delete', '/api/notif/device', { installationId: INSTALLATION }, () => fail(db.notificationDevice.deleteMany), 'Server error'],
  ['favorites list', 'get', '/api/auth/me/favorites', undefined, () => fail(db.stockFavorite.findMany), 'Server error'],
  ['favorite add', 'put', `/api/auth/me/favorites/${SYMBOL}`, undefined, () => fail(db.stockFavorite.createMany), 'Server error'],
  ['favorite remove', 'delete', `/api/auth/me/favorites/${SYMBOL}`, undefined, () => fail(db.stockFavorite.deleteMany), 'Server error'],
  ['friends list', 'get', '/api/friends', undefined, () => fail(db.friendship.findMany), 'Server error'],
  ['friend requests list', 'get', '/api/friends/requests', undefined, () => fail(db.friendship.findMany), 'Server error'],
  ['leaderboard', 'get', '/api/friends/leaderboard', undefined, () => fail(db.user.findMany), 'Server error'],
  ['friend request send', 'post', '/api/friends/requests', { username: 'other_user' }, failHandlerLookup, 'Server error'],
  ['friend request accept', 'post', '/api/friends/requests/x/accept', undefined, () => fail(db.friendship.findFirst), 'Server error'],
  ['friend request decline', 'delete', '/api/friends/requests/x', undefined, () => fail(db.friendship.findFirst), 'Server error'],
  ['unfriend', 'delete', '/api/friends/x', undefined, () => fail(db.friendship.findFirst), 'Server error'],
  ['profile read', 'get', '/api/auth/me', undefined, failHandlerLookup, 'Server error'],
  ['profile read (legacy POST)', 'post', '/api/auth/me', undefined, failHandlerLookup, 'Server error'],
  ['profile update', 'patch', '/api/auth/me', { displayName: 'Jerry', username: 'jerry' }, () => fail(db.user.update), 'Server error'],
  ['account deletion', 'delete', '/api/auth/me', undefined, () => fail(db.user.delete), 'Server error'],
];

describe('Mocked API: database failures degrade to clean 500s', () => {
  it.each(cases)('DBF-1 %s', async (_name, method, path, body, arrange, error) => {
    arrange();
    const res = await request(app)[method](path).set('Authorization', auth()).send(body);
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error });
  });

  it('DBF-2 write conflicts that never clear → 503 "busy" after ten attempts', async () => {
    m(db.$transaction).mockRejectedValue({ code: 'P2034' });
    const res = await request(app).post('/api/trades/execute/market').set('Authorization', auth())
      .send({ ticker: SYMBOL, side: 'buy', quantity: 1 });
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ error: 'The server is busy. Please try again.' });
    expect(db.$transaction).toHaveBeenCalledTimes(10);
  });

  it('DBF-3 a friend request failing with an unrelated database error is not reported as a duplicate', async () => {
    m(db.user.findUnique).mockResolvedValueOnce({ id: 'u1', isBanned: false }).mockResolvedValueOnce({ id: 'u2', isBanned: false });
    m(db.friendship.create).mockRejectedValue({ code: 'P2003' });
    const res = await request(app).post('/api/friends/requests').set('Authorization', auth()).send({ username: 'other_user' });
    expect(res.status).toBe(500);
  });

  it('DBF-4 a profile update failing with an unrelated database error is not reported as a taken username', async () => {
    m(db.user.update).mockRejectedValue({ code: 'P2003' });
    const res = await request(app).patch('/api/auth/me').set('Authorization', auth()).send({ displayName: 'Jerry', username: 'jerry' });
    expect(res.status).toBe(500);
  });

  it('DBF-5 the authentication lookup failing never lets the request through', async () => {
    m(db.user.findUnique).mockRejectedValue(new Error('db down'));
    const res = await request(app).get('/api/portfolio').set('Authorization', auth());
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Server error' });
    expect(db.account.findUnique).not.toHaveBeenCalled();
  });

  it('DBF-6 malformed stored data has unavailable totals instead of a wrong ranking', async () => {
    m(db.user.findMany).mockResolvedValue([{
      id: 'u1', username: 'me', displayName: 'Me', avatarUrl: null, positions: [], orders: [],
      account: { cashBalance: 1, startingBalance: 1 }, // plain numbers instead of Decimals
    }]);
    const res = await request(app).get('/api/friends/leaderboard').set('Authorization', auth());
    expect(res.status).toBe(200);
    expect(res.body.entries[0]).toEqual(expect.objectContaining({ totalPortfolioValue: null, returnPercent: null }));
  });
});
