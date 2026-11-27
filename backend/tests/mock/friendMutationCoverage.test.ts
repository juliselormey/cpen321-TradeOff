import request from 'supertest';
import jwt from 'jsonwebtoken';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import { notifyUser } from '../../src/firebase/notifier';

jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());
jest.mock('../../src/firebase/notifier', () => ({ notifyUser: jest.fn() }));

const app = createApp();
const secret = 'friend-mutation-coverage-secret';
const token = () => `Bearer ${jwt.sign({ sub: 'recipient' }, secret)}`;
const m = (value: unknown) => value as jest.Mock;

beforeEach(() => {
  jest.resetAllMocks();
  process.env.JWT_SECRET = secret;
  m(db.user.findUnique).mockResolvedValue({ id: 'recipient', isBanned: false });
});

it('returns success and sends an acceptance notification for a committed request', async () => {
  m(db.friendship.findFirst).mockResolvedValue({ senderId: 'sender', recipientId: 'recipient' });
  m(db.friendship.updateMany).mockResolvedValue({ count: 1 });
  m(db.friendship.findUnique).mockResolvedValue({
    senderId: 'sender', recipient: { displayName: 'Recipient' },
  });

  await request(app).post('/api/friends/requests/request-id/accept')
    .set('Authorization', token()).expect(204);
  await new Promise<void>(resolve => setImmediate(resolve));

  expect(db.friendship.findUnique).toHaveBeenCalledTimes(1);
  expect(jest.mocked(notifyUser)).toHaveBeenCalledWith(
    'sender', 'Friend request accepted', expect.stringContaining('accepted your friend request'),
  );
});

it('still succeeds if the participant lookup is absent or notification lookup fails', async () => {
  const log = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  m(db.friendship.findFirst).mockResolvedValue(null);
  m(db.friendship.updateMany).mockResolvedValue({ count: 1 });
  m(db.friendship.findUnique).mockRejectedValue(new Error('lookup unavailable'));

  await request(app).post('/api/friends/requests/request-id/accept')
    .set('Authorization', token()).expect(204);
  await new Promise<void>(resolve => setImmediate(resolve));

  expect(notifyUser).not.toHaveBeenCalled();
  expect(log).toHaveBeenCalledWith('Friend request notification failed', expect.any(Error));
  log.mockRestore();
});
