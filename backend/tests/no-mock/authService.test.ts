import jwt from 'jsonwebtoken';
import request from 'supertest';
import { randomUUID } from 'node:crypto';
import { createApp } from '../../src/app';
import { findOrCreateUser, issueAccessToken } from '../../src/auth/service';
import { requireEnv } from '../../src/config/env';
import { db, resetDb } from '../helpers/fixtures';

const app = createApp();
const identity = (o: object = {}) => ({ googleSub: `google-${randomUUID()}`, email: 'new@example.com', name: 'Google Name', ...o });

beforeEach(resetDb);
afterAll(() => db.$disconnect());

describe('Unmocked: Google identity → user (findOrCreateUser)', () => {
  it('AUTHSVC-N1 first login creates the user from the Google profile with a starting account', async () => {
    const google = identity();
    const user = await findOrCreateUser(google);
    const row = await db.user.findUniqueOrThrow({ where: { id: user.id }, include: { account: true } });
    expect(row).toEqual(expect.objectContaining({ googleSub: google.googleSub, email: google.email, displayName: 'Google Name', isBanned: false }));
    expect(Number(row.account?.cashBalance)).toBe(10000);
    expect(Number(row.account?.startingBalance)).toBe(10000);
  });

  it('AUTHSVC-N2 logging in again returns the same user and creates nothing new', async () => {
    const google = identity();
    const first = await findOrCreateUser(google);
    const second = await findOrCreateUser(google);
    expect(second.id).toBe(first.id);
    expect(await db.user.count()).toBe(1);
    expect(await db.account.count()).toBe(1);
  });

  it('AUTHSVC-N3 a returning user keeps a customized name and username, and the email is refreshed', async () => {
    const google = identity();
    const first = await findOrCreateUser(google);
    await db.user.update({ where: { id: first.id }, data: { displayName: 'Custom', username: 'custom' } });
    await findOrCreateUser({ ...google, email: 'changed@example.com', name: 'Different Google Name' });
    const row = await db.user.findUniqueOrThrow({ where: { id: first.id } });
    expect(row).toEqual(expect.objectContaining({ displayName: 'Custom', username: 'custom', email: 'changed@example.com' }));
  });
});

describe('Unmocked: access tokens (issueAccessToken)', () => {
  it('AUTHSVC-N4 the token identifies the user, expires in the future, and is accepted by the API', async () => {
    const user = await findOrCreateUser(identity());
    const token = issueAccessToken(user.id);
    const decoded = jwt.verify(token, requireEnv('JWT_SECRET')) as jwt.JwtPayload;
    expect(decoded.sub).toBe(user.id);
    expect(decoded.exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
    const res = await request(app).get('/api/auth/me').set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(200);
    expect(res.body.user.id).toBe(user.id);
  });

  it('AUTHSVC-N5 tokens are bound to the server secret', async () => {
    const user = await findOrCreateUser(identity());
    expect(() => jwt.verify(issueAccessToken(user.id), 'not-the-secret')).toThrow();
  });
});

describe('Unmocked: auth service details', () => {
  it('AUTHSVC-N6 a Google profile without a name creates a user with no display name', async () => {
    const user = await findOrCreateUser({ googleSub: `google-${randomUUID()}`, email: 'noname@example.com' });
    expect(user.displayName).toBeNull();
    expect((await db.user.findUniqueOrThrow({ where: { id: user.id }, include: { account: true } })).account).not.toBeNull();
  });
  it('AUTHSVC-N7 access tokens last fifteen minutes', async () => {
    const user = await findOrCreateUser(identity());
    const decoded = jwt.verify(issueAccessToken(user.id), requireEnv('JWT_SECRET')) as jwt.JwtPayload;
    expect((decoded.exp as number) - (decoded.iat as number)).toBe(15 * 60);
  });
});