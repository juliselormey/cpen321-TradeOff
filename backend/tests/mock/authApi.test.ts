import request from 'supertest';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import { verifyGoogleToken } from '../../src/auth/service';
import { expectError } from '../helpers/testkit';

// Mocked: Google is external (verifier), and the database is made to fail.
// Sign-in behaviour against a real database lives in unmocked/authGoogle.test.ts.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/auth/service', () => ({
  ...jest.requireActual('../../src/auth/service'),
  verifyGoogleToken: jest.fn(),
}));

const app = createApp();
const identity = { googleSub: 'g-1', email: 'new@test.dev', name: 'New User' };
const signIn = () => request(app).post('/api/auth/google').send({ idToken: 'good' });

beforeEach(() => {
  jest.resetAllMocks();
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
});

describe('Mocked API: POST /api/auth/google (Google and database failures)', () => {
  it.each<[string, unknown]>([
    ['AUTH-02 Google rejects the token', new Error('bad token')],
    ['AUTH-05 Google is unreachable', Object.assign(new Error('socket hang up'), { code: 'ECONNRESET' })],
    ['AUTH-05b the verifier rejects with a non-Error value', 'invalid'],
  ])('%s → 401 and no database access', async (_name, failure) => {
    jest.mocked(verifyGoogleToken).mockRejectedValue(failure);
    const res = await signIn();
    expect([401, 503]).toContain(res.status);
    expect(res.body.accessToken).toBeUndefined();
    expect(db.user.findUnique).not.toHaveBeenCalled();
  });

  it('AUTH-09 a verified identity but the database is down → 500 and no token', async () => {
    jest.mocked(verifyGoogleToken).mockResolvedValue(identity as never);
    jest.mocked(db.user.findUnique).mockRejectedValue(new Error('db down'));
    const res = await signIn();
    expectError(res, 500);
    expect(res.body).toEqual({ error: 'Server error' });
    expect(res.body.accessToken).toBeUndefined();
  });
});