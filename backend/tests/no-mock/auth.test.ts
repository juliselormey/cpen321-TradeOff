import request from 'supertest';
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
