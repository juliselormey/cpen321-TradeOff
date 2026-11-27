import request from 'supertest';
import jwt from 'jsonwebtoken';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';

// Mocked: handlers fail in different ways; this checks how the app's error handler classifies them.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());

const secret = 'app-errors-secret';
const app = createApp();
const m = (f: unknown) => f as jest.Mock;
const withProps = (props: object) => Object.assign(new Error('boom'), props);

beforeEach(() => {
  jest.resetAllMocks();
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
  process.env.JWT_SECRET = secret;
  m(db.user.findUnique).mockResolvedValue({ id: 'u1', isBanned: false });
});

describe('Mocked: app error handler', () => {
  // The favorites list rethrows database errors, so whatever it throws reaches the handler untouched.
  it.each<[string, unknown, number, string]>([
    ['a 413 client error', withProps({ status: 413 }), 413, 'Request body too large'],
    ['a 400 client error', withProps({ status: 400 }), 400, 'Invalid request body'],
    ['a 404 client error', withProps({ status: 404 }), 404, 'Invalid request body'],
    ['a 5xx status', withProps({ status: 503 }), 500, 'Server error'],
    ['a status below 400', withProps({ status: 399 }), 500, 'Server error'],
    ['a non-numeric status', withProps({ status: 'abc' }), 500, 'Server error'],
    ['an error without a status', new Error('boom'), 500, 'Server error'],
    ['a plain object', { message: 'boom' }, 500, 'Server error'],
    ['a string', 'boom', 500, 'Server error'],
    ['null', null, 500, 'Server error'],
  ])('APP-1 %s → %i', async (_name, thrown, status, message) => {
    m(db.stockFavorite.findMany).mockRejectedValue(thrown);
    const res = await request(app).get('/api/auth/me/favorites').set('Authorization', `Bearer ${jwt.sign({ sub: 'u1' }, secret)}`);
    expect(res.status).toBe(status);
    expect(res.body).toEqual({ error: message });
  });

  it('APP-2 server faults are logged, client errors are not', async () => {
    const token = `Bearer ${jwt.sign({ sub: 'u1' }, secret)}`;
    m(db.stockFavorite.findMany).mockRejectedValue(withProps({ status: 400 }));
    await request(app).get('/api/auth/me/favorites').set('Authorization', token);
    expect(console.error).not.toHaveBeenCalled();
    m(db.stockFavorite.findMany).mockRejectedValue(new Error('real fault'));
    await request(app).get('/api/auth/me/favorites').set('Authorization', token);
    expect(console.error).toHaveBeenCalledTimes(1);
  });

  it('APP-3 once a response has started, the error is handed to the default handler instead of a second reply', () => {
    const anyApp = app as unknown as { _router?: { stack: { handle: (...a: unknown[]) => void }[] }; router?: { stack: { handle: (...a: unknown[]) => void }[] } };
    const stack = (anyApp._router ?? anyApp.router)!.stack;
    const errorLayer = [...stack].reverse().find((layer) => layer.handle.length === 4)!;
    const next = jest.fn();
    const error = new Error('too late');
    const res = { headersSent: true, status: jest.fn(), json: jest.fn() };
    errorLayer.handle(error, {}, res, next);
    expect(next).toHaveBeenCalledWith(error);
    expect(res.status).not.toHaveBeenCalled();
    expect(res.json).not.toHaveBeenCalled();
  });
});