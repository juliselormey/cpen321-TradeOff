import { verifyGoogleToken } from '../../src/auth/service';

// Mocked: Google (external). The OAuth2Client's network call is replaced; our own parsing is real.
const mockVerifyIdToken = jest.fn();
jest.mock('google-auth-library', () => ({
  // Resolved lazily: the service builds its client while it is being imported.
  OAuth2Client: jest.fn().mockImplementation(() => ({ verifyIdToken: (...args: unknown[]) => mockVerifyIdToken(...args) })),
}));
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());

const ticket = (payload: unknown) => ({ getPayload: () => payload });

beforeEach(() => mockVerifyIdToken.mockReset());

describe('Mocked: verifyGoogleToken', () => {
  it('GV-1 returns the Google identity and verifies against our client id', async () => {
    mockVerifyIdToken.mockResolvedValue(ticket({ sub: 'g-1', email: 'a@b.c', name: 'Ada' }));
    await expect(verifyGoogleToken('token-1')).resolves.toEqual({ googleSub: 'g-1', email: 'a@b.c', name: 'Ada' });
    expect(mockVerifyIdToken).toHaveBeenCalledWith({ idToken: 'token-1', audience: process.env.GOOGLE_CLIENT_ID });
  });
  it('GV-2 the name is optional', async () => {
    mockVerifyIdToken.mockResolvedValue(ticket({ sub: 'g-1', email: 'a@b.c' }));
    await expect(verifyGoogleToken('t')).resolves.toEqual({ googleSub: 'g-1', email: 'a@b.c', name: undefined });
  });
  it.each([[undefined], [{}], [{ email: 'a@b.c' }], [{ sub: 'g-1' }], [{ sub: '', email: 'a@b.c' }], [{ sub: 'g-1', email: '' }]])(
    'GV-3 an incomplete payload %j is rejected', async (payload) => {
      mockVerifyIdToken.mockResolvedValue(ticket(payload));
      await expect(verifyGoogleToken('t')).rejects.toThrow('Invalid Google Token');
    });
  it.each(['Token used too late', 'Wrong recipient, payload audience != requiredAudience', 'getaddrinfo ENOTFOUND www.googleapis.com'])(
    'GV-4 a Google failure (%s) is passed on', async (message) => {
      mockVerifyIdToken.mockRejectedValue(new Error(message));
      await expect(verifyGoogleToken('t')).rejects.toThrow(message);
    });
});