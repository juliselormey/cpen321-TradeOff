import request from 'supertest';
import jwt from 'jsonwebtoken';
import { randomUUID } from 'node:crypto';
import { requireEnv } from '../../src/config/env';
import { createApp } from '../../src/app';
import { bearer, expiredBearer, expectError, tamperedBearer } from '../helpers/testkit';
import { countRowsFor, createUser, db, getCash, giveShares, befriend, resetDb, getQty } from '../helpers/fixtures';

const app = createApp();

beforeEach(resetDb);
afterAll(() => db.$disconnect());

describe('Unmocked API: POST /api/auth/google', () => {
  it('AUTH-N1 missing token → 400, malformed token → 401', async () => {
    expectError(await request(app).post('/api/auth/google').send({}), 400);
    expectError(await request(app).post('/api/auth/google').send({ idToken: 'not-a-google-token' }), 401);
  });
  it.each(['dev-token', 'dev_abc'])('AUTH-N2 %s is rejected (bypass removed)', async (idToken) => {
    expectError(await request(app).post('/api/auth/google').send({ idToken }), 401);
  });
});

describe('Unmocked API: GET /api/auth/me', () => {
  it('AUTH-N3 expired and tampered JWT → 401', async () => {
    const id = await createUser();
    expect((await request(app).get('/api/auth/me').set(expiredBearer(id))).status).toBe(401);
    expect((await request(app).get('/api/auth/me').set(tamperedBearer(id))).status).toBe(401);
  });
  it('AUTH-N4 returns the caller’s own profile', async () => {
    const id = await createUser({ name: 'Ada' });
    const res = await request(app).get('/api/auth/me').set(bearer(id));
    expect(res.status).toBe(200);
    expect(res.body.user).toEqual(expect.objectContaining({ id, displayName: 'Ada' }));
  });
});

describe('Unmocked API: PATCH /api/auth/me', () => {
  const patch = (id: string, body: object) => request(app).patch('/api/auth/me').set(bearer(id)).send(body);

  it('AUTH-N5 valid name + username → 200 and persisted (UC-04)', async () => {
    const id = await createUser();
    const res = await patch(id, { displayName: 'Grace', username: 'grace_h' });
    expect(res.status).toBe(200);
    const row = await db.user.findUnique({ where: { id } });
    expect(row).toEqual(expect.objectContaining({ displayName: 'Grace', username: 'grace_h' }));
  });
  it('AUTH-N6 empty name → 400, over-length name → 400, taken username → 409', async () => {
    const a = await createUser();
    await createUser({ username: 'taken_name' });
    expectError(await patch(a, { displayName: '' }), 400);
    expectError(await patch(a, { displayName: 'x'.repeat(500) }), 400);
    expectError(await patch(a, { username: 'taken_name' }), 409);
  });
  it('AUTH-N7 cannot change cash or id through PATCH', async () => {
    const id = await createUser({ cash: 500 });
    await patch(id, { cash: 999999, id: 'someone-else' });
    expect(await getCash(id)).toBe(500);
    expect((await request(app).get('/api/auth/me').set(bearer(id))).body.user.id).toBe(id);
  });
});

describe('Unmocked API: DELETE /api/auth/me', () => {
  it('AUTH-N8 removes the account, its data, and its friendships (UC-05)', async () => {
    const a = await createUser();
    const b = await createUser();
    await giveShares(a, 'AAPL', 3);
    await befriend(a, b);
    const res = await request(app).delete('/api/auth/me').set(bearer(a));
    expect([200, 204]).toContain(res.status);
    expect(await db.user.findUnique({ where: { id: a } })).toBeNull();
    expect(await countRowsFor(a)).toBe(0);
    expect(await getQty(a, 'AAPL')).toBe(0);
    expect(await countRowsFor(b)).toBe(0); // B no longer has a friendship with A
  });
  it('AUTH-N9 the old JWT stops working after deletion', async () => {
    const a = await createUser();
    await request(app).delete('/api/auth/me').set(bearer(a));
    const res = await request(app).get('/api/auth/me').set(bearer(a));
    expect([401, 404]).toContain(res.status);
  });
});

describe('Unmocked API: sign-in input handling', () => {
  it.each([[{ idToken: 123 }], [{ idToken: '' }], [{ idToken: null }]])('AUTH-N10 invalid idToken %j → 400', async (body) => {
    expectError(await request(app).post('/api/auth/google').send(body), 400);
  });
  it('AUTH-N11 no body at all → 400', async () => {
    expectError(await request(app).post('/api/auth/google'), 400);
  });
});

describe('Unmocked: bearer token handling', () => {
  const sign = (payload: object, secret = requireEnv('JWT_SECRET')) => jwt.sign(payload, secret, { expiresIn: '5m' });

  it.each([
    ['no Authorization header', undefined],
    ['a non-Bearer scheme', 'Basic abc'],
    ['an empty Bearer value', 'Bearer '],
    ['a malformed token', 'Bearer not.a.jwt'],
  ])('AUTH-N12 %s → 401', async (_name, header) => {
    const req = request(app).get('/api/auth/me');
    expect((await (header === undefined ? req : req.set('Authorization', header))).status).toBe(401);
  });
  it('AUTH-N13 a token without a subject, or signed with another secret → 401', async () => {
    const id = await createUser();
    expect((await request(app).get('/api/auth/me').set('Authorization', `Bearer ${sign({})}`)).status).toBe(401);
    expect((await request(app).get('/api/auth/me').set('Authorization', `Bearer ${sign({ sub: id }, 'another-secret')}`)).status).toBe(401);
  });
  it('AUTH-N14 a valid token for a user that does not exist → rejected', async () => {
    const res = await request(app).get('/api/auth/me').set('Authorization', `Bearer ${sign({ sub: randomUUID() })}`);
    expect([401, 404]).toContain(res.status);
  });
  it('AUTH-N15 a banned user is refused on every protected route with a still-valid token', async () => {
    const id = await createUser();
    await db.user.update({ where: { id }, data: { isBanned: true } });
    for (const path of ['/api/auth/me', '/api/portfolio', '/api/friends', '/api/auth/me/favorites']) {
      expect((await request(app).get(path).set(bearer(id))).status).toBe(403);
    }
  });
});

describe('Unmocked API: profile read and update', () => {
  const patch = (id: string, body: unknown) => request(app).patch('/api/auth/me').set(bearer(id)).send(body as object);

  it('AUTH-N16 GET /me exposes only the editable profile fields', async () => {
    const id = await createUser();
    const res = await request(app).get('/api/auth/me').set(bearer(id));
    expect(Object.keys(res.body.user).sort()).toEqual(['displayName', 'email', 'id', 'username']);
  });
  it('AUTH-N17 name is trimmed and username is lower-cased before saving', async () => {
    const id = await createUser();
    const res = await patch(id, { displayName: '  Jerry  ', username: '  JERRY_123  ' });
    expect(res.status).toBe(200);
    expect(res.body.user).toEqual(expect.objectContaining({ id, displayName: 'Jerry', username: 'jerry_123' }));
    expect(await db.user.findUnique({ where: { id } })).toEqual(expect.objectContaining({ displayName: 'Jerry', username: 'jerry_123' }));
  });
  it('AUTH-N18 re-saving your own username is not a conflict', async () => {
    const id = await createUser({ username: 'same_name' });
    expect((await patch(id, { displayName: 'Same', username: 'same_name' })).status).toBe(200);
  });
  it.each([
    [{ displayName: 'A', username: 'ab' }],
    [{ displayName: 'A', username: 'a'.repeat(25) }],
    [{ displayName: 'A', username: 'bad name' }],
    [{ displayName: 'a'.repeat(61), username: 'abc' }],
    [{ displayName: 'A' }],
    [{ displayName: 'A', username: 'abc', role: 'ADMIN' }],
    [{ displayName: 'A', username: 'abc', id: 'other' }],
  ])('AUTH-N19 invalid or unauthorized input %j → 400, nothing saved', async (body) => {
    const id = await createUser({ name: 'Original' });
    const before = await db.user.findUnique({ where: { id } });
    const res = await patch(id, body);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Invalid name or username');
    expect(await db.user.findUnique({ where: { id } })).toEqual(before);
  });
  it('AUTH-N20 a non-object body → 400 Invalid profile', async () => {
    const id = await createUser();
    const res = await patch(id, []);
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'Invalid profile' });
  });
  it('AUTH-N21 POST /me (legacy) returns the caller’s user and account', async () => {
    const id = await createUser({ cash: 1234 });
    const res = await request(app).post('/api/auth/me').set(bearer(id));
    expect(res.status).toBe(200);
    expect(res.body.user.id).toBe(id);
    expect(Number(res.body.account.cashBalance)).toBe(1234);
  });
  it('AUTH-N22 profile routes require authentication', async () => {
    expect((await request(app).post('/api/auth/me')).status).toBe(401);
    expect((await request(app).patch('/api/auth/me').send({ displayName: 'A', username: 'abc' })).status).toBe(401);
    expect((await request(app).delete('/api/auth/me')).status).toBe(401);
  });
});