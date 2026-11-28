import express, { NextFunction, Request, Response } from 'express';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { randomUUID } from 'node:crypto';
import { requireEnv } from '../../src/config/env';
import { AuthenticationError, authenticateBearer, authenticatedHandler, requireAuth } from '../../src/middleware/auth';
import { createUser, db, resetDb } from '../helpers/fixtures';

const secret = () => requireEnv('JWT_SECRET');
const header = (token: string) => `Bearer ${token}`;

beforeEach(resetDb);
afterAll(() => db.$disconnect());

describe('Unmocked: authenticateBearer', () => {
  it.each([[undefined], [''], ['Basic abc'], ['bearer lowercase-scheme'], ['Bearer']])('MW-N1 rejects the header %p', async (value) => {
    const error = await authenticateBearer(value).catch((e) => e);
    expect(error).toBeInstanceOf(AuthenticationError);
    expect(error).toMatchObject({ status: 401, message: 'Unauthorized' });
  });

  it('MW-N2 rejects every kind of bad token with 401', async () => {
    const id = await createUser();
    const good = jwt.sign({ sub: id }, secret(), { expiresIn: '5m' });
    const bad: string[] = [
      'not.a.jwt',
      `${good.slice(0, -3)}abc`,                                               // tampered signature
      jwt.sign({ sub: id }, 'another-secret', { expiresIn: '5m' }),            // wrong secret
      jwt.sign({ sub: id }, secret(), { expiresIn: -10 }),                      // expired
      jwt.sign({ sub: id }, secret(), { algorithm: 'HS512' }),                  // only HS256 is accepted
      jwt.sign({ sub: id }, '', { algorithm: 'none' }),                         // unsigned token
      jwt.sign('just-a-string', secret()),                                      // payload is not an object
      jwt.sign({}, secret()),                                                   // no subject
    ];
    for (const token of bad) {
      await expect(authenticateBearer(header(token))).rejects.toMatchObject({ status: 401, message: 'Invalid or expired token' });
    }
  });

  it('MW-N3 an unknown user → 401 and a banned user → 403', async () => {
    const ghost = jwt.sign({ sub: randomUUID() }, secret(), { expiresIn: '5m' });
    await expect(authenticateBearer(header(ghost))).rejects.toMatchObject({ status: 401, message: 'User not found' });
    const id = await createUser();
    await db.user.update({ where: { id }, data: { isBanned: true } });
    const token = jwt.sign({ sub: id }, secret(), { expiresIn: '5m' });
    await expect(authenticateBearer(header(token))).rejects.toMatchObject({ status: 403, message: 'Account is banned' });
  });

  it('MW-N4 a valid token yields the user and its expiry; a token without expiry yields no expiry', async () => {
    const id = await createUser();
    const expiring = jwt.sign({ sub: id }, secret(), { expiresIn: '5m' });
    const { exp } = jwt.decode(expiring) as jwt.JwtPayload;
    await expect(authenticateBearer(header(expiring))).resolves.toEqual({ userId: id, expiresAt: (exp as number) * 1000 });
    await expect(authenticateBearer(header(jwt.sign({ sub: id }, secret())))).resolves.toEqual({ userId: id });
  });
});

describe('Unmocked: requireAuth and authenticatedHandler', () => {
  const guarded = jest.fn();
  const app = express();
  app.get('/plain', requireAuth, (req: Request, res: Response) => { guarded(); res.json({ userId: req.userId }); });
  app.get('/wrapped', authenticatedHandler(async (req, res) => { guarded(); res.json({ userId: req.userId }); }));
  app.get('/wrapped-fails', authenticatedHandler(async () => { throw new Error('handler exploded'); }));
  app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => { res.status(500).json({ error: err.message }); });

  beforeEach(() => guarded.mockClear());
  const token = (id: string) => header(jwt.sign({ sub: id }, secret(), { expiresIn: '5m' }));

  it.each(['/plain', '/wrapped'])('MW-N5 %s runs the handler with the caller’s user id', async (path) => {
    const id = await createUser();
    const res = await request(app).get(path).set('Authorization', token(id));
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ userId: id });
  });
  it.each(['/plain', '/wrapped'])('MW-N6 %s answers 401 or 403 as JSON and never runs the handler', async (path) => {
    const banned = await createUser();
    await db.user.update({ where: { id: banned }, data: { isBanned: true } });
    expect((await request(app).get(path)).body).toEqual({ error: 'Unauthorized' });
    expect((await request(app).get(path).set('Authorization', 'Bearer junk')).body).toEqual({ error: 'Invalid or expired token' });
    const res = await request(app).get(path).set('Authorization', token(banned));
    expect(res.status).toBe(403);
    expect(res.body).toEqual({ error: 'Account is banned' });
    expect(guarded).not.toHaveBeenCalled();
  });
  it('MW-N7 an error thrown by an authenticatedHandler goes to the error handler', async () => {
    const id = await createUser();
    const res = await request(app).get('/wrapped-fails').set('Authorization', token(id));
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'handler exploded' });
  });
});