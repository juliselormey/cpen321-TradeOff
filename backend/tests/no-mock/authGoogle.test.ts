import jwt from 'jsonwebtoken';
import request from 'supertest';
import { randomUUID } from 'node:crypto';
import { createApp } from '../../src/app';
import { verifyGoogleToken } from '../../src/auth/service';
import { expectError } from '../helpers/testkit';
import { db, resetDb } from '../helpers/fixtures';

// Only Google (external) is mocked, and only the token verification. Everything else is the real app and MySQL.
jest.mock('../../src/auth/service', () => ({
  ...jest.requireActual('../../src/auth/service'),
  verifyGoogleToken: jest.fn(),
}));

const app = createApp();
const google = jest.mocked(verifyGoogleToken);
const identity = (o: object = {}) => ({ googleSub: `google-${randomUUID()}`, email: 'new@test.dev', name: 'New User', ...o });
const signIn = (body: object = { idToken: 'good' }) => request(app).post('/api/auth/google').send(body);

beforeEach(async () => { await resetDb(); jest.resetAllMocks(); });
afterAll(() => db.$disconnect());

describe('Unmocked API: POST /api/auth/google (verified Google identity)', () => {
  it('AUTHG-N1 a new identity creates the user and a starting account, and returns a working token (UC-01)', async () => {
    google.mockResolvedValue(identity({ name: 'Ada' }) as never);
    const res = await signIn();
    expect(res.status).toBe(200);
    expect(typeof res.body.accessToken).toBe('string');
    const userId = (jwt.decode(res.body.accessToken) as jwt.JwtPayload).sub as string;
    const row = await db.user.findUniqueOrThrow({ where: { id: userId }, include: { account: true } });
    expect(row.displayName).toBe('Ada');
    expect(Number(row.account?.cashBalance)).toBe(10000);
    const me = await request(app).get('/api/auth/me').set('Authorization', `Bearer ${res.body.accessToken}`);
    expect(me.status).toBe(200);
    expect(me.body.user.id).toBe(userId);
  });
  it('AUTHG-N2 the same identity signs in as the same user with nothing new created (UC-02)', async () => {
    google.mockResolvedValue(identity() as never);
    const first = await signIn();
    const second = await signIn();
    const sub = (r: { body: { accessToken: string } }) => (jwt.decode(r.body.accessToken) as jwt.JwtPayload).sub;
    expect(second.status).toBe(200);
    expect(sub(second)).toBe(sub(first));
    expect(await db.user.count()).toBe(1);
    expect(await db.account.count()).toBe(1);
  });
  it('AUTHG-N3 a banned account is refused and gets no token', async () => {
    const who = identity();
    google.mockResolvedValue(who as never);
    await signIn();
    await db.user.update({ where: { googleSub: who.googleSub }, data: { isBanned: true } });
    const res = await signIn();
    expectError(res, 403);
    expect(res.body.accessToken).toBeUndefined();
  });
  it('AUTHG-N4 a token Google rejects → 401 and nothing is stored', async () => {
    google.mockRejectedValue(new Error('bad token'));
    expectError(await signIn(), 401);
    expect(await db.user.count()).toBe(0);
  });
  it.each([[{}], [{ idToken: 123 }], [{ idToken: '' }], [{ idToken: null }]])('AUTHG-N5 invalid body %j → 400 without calling Google', async (body) => {
    expectError(await signIn(body), 400);
    expect(google).not.toHaveBeenCalled();
  });
});