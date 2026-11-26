import { OAuth2Client } from 'google-auth-library';
import { verifyGoogleToken, findOrCreateUser } from '../../src/auth/service';
import { db } from '../../src/prisma/db';
import { STARTING_BALANCE } from '../../src/config/trading';

jest.mock('google-auth-library', () => ({ OAuth2Client: jest.fn(() => ({ verifyIdToken: jest.fn() })) }));
jest.mock('../../src/prisma/db', () => ({ db: { user: { findUnique: jest.fn(), create: jest.fn() } } }));

const verifier = (jest.mocked(OAuth2Client).mock.results[0]!.value as { verifyIdToken: jest.Mock }).verifyIdToken;
const originalMode = process.env.NODE_ENV;
beforeEach(() => jest.clearAllMocks());
afterEach(() => { process.env.NODE_ENV = originalMode; });

test.each(['production', 'development', 'test'])('rejects shortcuts and invalid credentials in %s', async mode => {
    process.env.NODE_ENV = mode;
    verifier.mockRejectedValue(new Error('Invalid Google Token'));
    for (const idToken of ['dev-token', 'dev_abc', 'made-up-token']) {
        await expect(verifyGoogleToken(idToken)).rejects.toThrow();
        expect(verifier).toHaveBeenLastCalledWith(expect.objectContaining({ idToken }));
    }
    expect(db.user.create).not.toHaveBeenCalled();
});

test('accepts only an identity returned by Google verification', async () => {
    verifier.mockResolvedValue({ getPayload: () => ({ sub: 'google-sub', email: 'user@example.test', name: 'User' }) });
    await expect(verifyGoogleToken('verified')).resolves.toEqual({ googleSub: 'google-sub', email: 'user@example.test', name: 'User' });
});

test('rejects a verified payload without the required identity fields', async () => {
    verifier.mockResolvedValue({ getPayload: () => ({ sub: 'google-sub' }) });
    await expect(verifyGoogleToken('missing-email')).rejects.toThrow('Invalid Google Token');
});

test('new accounts use the configured starting balance for both fields', async () => {
    jest.mocked(db.user.findUnique).mockResolvedValue(null);
    await findOrCreateUser({ googleSub: 'new', email: 'new@example.test' });
    expect(db.user.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({
        account: { create: { cashBalance: STARTING_BALANCE.toString(), startingBalance: STARTING_BALANCE.toString() } },
    }) }));
});
