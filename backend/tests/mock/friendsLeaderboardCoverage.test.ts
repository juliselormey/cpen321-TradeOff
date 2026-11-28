import request from 'supertest';
import jwt from 'jsonwebtoken';
import { Prisma } from '../../src/generated/prisma/client';
import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';

jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());

const app = createApp();
const secret = 'friends-leaderboard-coverage-secret';
const auth = () => `Bearer ${jwt.sign({ sub: 'you' }, secret)}`;
const row = (id: string, cash: number | null) => ({
  id, username: id, displayName: id, avatarUrl: null,
  account: cash == null ? null : {
    cashBalance: new Prisma.Decimal(cash),
    startingBalance: new Prisma.Decimal(cash),
  },
  positions: [], orders: [],
});

beforeEach(() => {
  jest.resetAllMocks();
  process.env.JWT_SECRET = secret;
  jest.mocked(db.user.findUnique).mockResolvedValue({ id: 'you', isBanned: false } as never);
});

describe('Mocked leaderboard ranking tie and unavailable-total cases', () => {
  const leaderboard = async (rows: ReturnType<typeof row>[]) => {
    jest.mocked(db.user.findMany).mockResolvedValue(rows as never);
    return (await request(app).get('/api/friends/leaderboard').set('Authorization', auth())).body.entries;
  };

  it('ranks equal valid totals with the caller first, then by ID', async () => {
    const entries = await leaderboard([row('b', 500), row('you', 500), row('a', 500)]);
    expect(entries.map((entry: { user: { id: string } }) => entry.user.id)).toEqual(['you', 'a', 'b']);
  });

  it('puts valued accounts ahead of unavailable totals in either input order', async () => {
    const highFirst = await leaderboard([row('valued', 1000), row('unavailable', null), row('you', 500)]);
    expect(highFirst.map((entry: { user: { id: string } }) => entry.user.id)).toEqual(['valued', 'you', 'unavailable']);

    const unavailableFirst = await leaderboard([row('unavailable', null), row('valued', 1000), row('you', 500)]);
    expect(unavailableFirst.map((entry: { user: { id: string } }) => entry.user.id)).toEqual(['valued', 'you', 'unavailable']);
  });

  it('orders unavailable totals by caller then ID and handles a zero starting balance', async () => {
    const entries = await leaderboard([row('b', null), row('you', 0), row('a', null)]);
    expect(entries.map((entry: { user: { id: string } }) => entry.user.id)).toEqual(['you', 'a', 'b']);
    expect(entries[0].returnPercent).toBeNull();
    expect(entries[1].cashBalance).toBeNull();
    expect(entries[1].totalPortfolioValue).toBeNull();
  });
});
