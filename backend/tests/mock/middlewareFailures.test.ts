import express, { NextFunction, Request, Response } from 'express';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { db } from '../../src/prisma/db';
import { authenticatedHandler, requireAuth } from '../../src/middleware/auth';

// Mocked: the user lookup behind authentication fails or returns something impossible.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());

const secret = 'middleware-failures-secret';
const m = (f: unknown) => f as jest.Mock;
const handler = jest.fn();
const app = express();
app.get('/plain', requireAuth, (_req: Request, res: Response) => { handler(); res.json({ ok: true }); });
app.get('/wrapped', authenticatedHandler(async (_req, res) => { handler(); res.json({ ok: true }); }));
app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => { res.status(500).json({ error: err.message }); });
const auth = () => `Bearer ${jwt.sign({ sub: 'u1' }, secret, { expiresIn: '5m' })}`;

beforeEach(() => {
  jest.resetAllMocks();
  process.env.JWT_SECRET = secret;
});

describe('Mocked: authentication middleware failures', () => {
  it.each(['/plain', '/wrapped'])('MWF-1 %s: a failing user lookup is a server error, not a pass or a 401', async (path) => {
    m(db.user.findUnique).mockRejectedValue(new Error('db down'));
    const res = await request(app).get(path).set('Authorization', auth());
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'db down' });
    expect(handler).not.toHaveBeenCalled();
  });
  it('MWF-2 authenticatedHandler refuses a session that has no user id', async () => {
    m(db.user.findUnique).mockResolvedValue({ id: '', isBanned: false });
    const res = await request(app).get('/wrapped').set('Authorization', auth());
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Authenticated request has no user ID' });
    expect(handler).not.toHaveBeenCalled();
  });
});