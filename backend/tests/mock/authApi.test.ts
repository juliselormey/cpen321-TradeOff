import request from 'supertest';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import { verifyGoogleToken } from '../../src/auth/service';
import { expectError } from '../helpers/testkit';

jest.mock('../../src/prisma/db', () => ({
  db: {
    user: {
      findUnique: jest.fn(),
      findFirst: jest.fn(),
      create: jest.fn(),
      update: jest.fn(),
    },
    $transaction: jest.fn((cb) => cb(db)),
  },
}));

jest.mock('../../src/auth/service', () => ({
  ...jest.requireActual('../../src/auth/service'),
  verifyGoogleToken: jest.fn(),
}));

const app = createApp();
const identity = { googleSub: 'g-1', email: 'new@test.dev', name: 'New User' };
const USER = { id: 'u1', name: 'New User', username: 'newuser', isBanned: false };

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(db.$transaction).mockImplementation(async (cb: unknown) => (cb as (tx: typeof db) => Promise<unknown>)(db));
});

const signIn = (body: object = { idToken: 'good' }) => request(app).post('/api/auth/google').send(body);

describe('Mocked API: POST /api/auth/google', () => {
  it('AUTH-01 missing idToken → 400', async () => { expectError(await signIn({}), 400); });

  it('AUTH-02 verifier rejects the token → 401', async () => {
    jest.mocked(verifyGoogleToken).mockRejectedValue(new Error('bad token'));
    expectError(await signIn(), 401);
  });

  it('AUTH-03 new identity → account created, accessToken returned (UC-01)', async () => {
    jest.mocked(verifyGoogleToken).mockResolvedValue(identity);
    jest.mocked(db.user.findUnique).mockResolvedValue(null);
    jest.mocked(db.user.create).mockResolvedValue(USER as any);
    const res = await signIn();
    expect(res.status).toBe(200);
    expect(typeof res.body.accessToken).toBe('string');
    expect(db.user.create).toHaveBeenCalledTimes(1);
  });

  it('AUTH-04 existing identity → same user, no new account (UC-02)', async () => {
    jest.mocked(verifyGoogleToken).mockResolvedValue(identity);
    jest.mocked(db.user.findUnique).mockResolvedValue(USER as any);
    jest.mocked(db.user.update).mockResolvedValue(USER as any);
    const res = await signIn();
    expect(res.status).toBe(200);
    expect(typeof res.body.accessToken).toBe('string');
    expect(db.user.create).not.toHaveBeenCalled();
  });

  it.todo('AUTH-05 verifier throws because Google is unreachable → (spec) 503 or 401');
});
