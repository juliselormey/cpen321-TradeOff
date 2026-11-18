import { db } from '../../src/prisma/db';
export { db };

let n = 0;

export async function resetDb() {
  for (const m of ['trade', 'order', 'position', 'friendship', 'account', 'user']) {
    await (db as any)[m].deleteMany();
  }
}

export async function createUser(o: { cash?: number; name?: string; username?: string } = {}) {
  n += 1;
  const u = await db.user.create({
    data: {
      googleSub: `g-${n}-${Math.random().toString(36).slice(2)}`,
      email: `u${n}-${Math.random().toString(36).slice(2, 6)}@test.dev`,
      displayName: o.name ?? `User ${n}`,
      username: o.username ?? `user${n}${Math.random().toString(36).slice(2, 6)}`,
      account: {
        create: {
          cashBalance: o.cash ?? 10000,
          startingBalance: o.cash ?? 10000,
        },
      },
    },
  });
  return u.id as string;
}

export const getCash = async (id: string) => {
  const acc = await db.account.findUnique({ where: { userId: id } });
  return acc ? Number(acc.cashBalance) : 0;
};

export const giveShares = (userId: string, ticker = 'AAPL', quantity: number = 1, avgCost = 100) =>
  db.position.create({
    data: {
      userId,
      ticker: (ticker ?? 'AAPL').toUpperCase(),
      quantity,
      averageCost: avgCost,
    },
  });

export const getQty = async (userId: string, ticker = 'AAPL') => {
  const t = (ticker ?? 'AAPL').toUpperCase();
  const p = await db.position.findUnique({
    where: { userId_ticker: { userId, ticker: t } },
  });
  return p ? Number(p.quantity) : 0;
};

export const getAvgCost = async (userId: string, ticker = 'AAPL') => {
  const t = (ticker ?? 'AAPL').toUpperCase();
  const p = await db.position.findUnique({
    where: { userId_ticker: { userId, ticker: t } },
  });
  return p ? Number(p.averageCost) : 0;
};

export const countTrades = (userId: string) => db.trade.count({ where: { userId } });
export const getOrder = (id: string) => db.order.findUnique({ where: { id } });

export const befriend = async (a: string, b: string) => {
  const pairKey = [a.toLowerCase(), b.toLowerCase()].sort().join(":");
  return db.friendship.create({
    data: {
      senderId: a,
      recipientId: b,
      status: 'ACCEPTED',
      pairKey,
    },
  });
};

export const requestFrom = async (senderId: string, receiverId: string) => {
  const pairKey = [senderId.toLowerCase(), receiverId.toLowerCase()].sort().join(":");
  return db.friendship.create({
    data: {
      senderId,
      recipientId: receiverId,
      status: 'PENDING',
      pairKey,
    },
  });
};

const between = (a: string, b: string) => ({
  OR: [
    { senderId: a, recipientId: b },
    { senderId: b, recipientId: a },
  ],
});

export const countRequestsBetween = (a: string, b: string) =>
  db.friendship.count({ where: { ...between(a, b), status: 'PENDING' } });

export const countFriendshipsBetween = (a: string, b: string) =>
  db.friendship.count({ where: { ...between(a, b), status: 'ACCEPTED' } });

export const countRowsFor = async (id: string) => {
  const [t, o, p, f] = await Promise.all([
    db.trade.count({ where: { userId: id } }),
    db.order.count({ where: { userId: id } }),
    db.position.count({ where: { userId: id } }),
    db.friendship.count({ where: { OR: [{ senderId: id }, { recipientId: id }] } }),
  ]);
  return t + o + p + f;
};
