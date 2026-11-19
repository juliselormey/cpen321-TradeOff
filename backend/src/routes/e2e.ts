import { Router } from 'express';
import { requireAuth } from '../middleware/auth';
import { db } from '../prisma/db';
import { asyncHandler } from '../util/asyncHandler';

/** Destructive fixture setup, enabled only against an explicitly disposable database. */
export function createE2ERouter(): Router {
    const router = Router();
    if (process.env.E2E_ENABLED !== 'true') return router;
    const database = new URL(process.env.DATABASE_URL ?? '').pathname.slice(1);
    if (!/^tradeoff_e2e_[a-z0-9_]+$/.test(database)) {
        throw new Error('E2E_ENABLED requires a disposable tradeoff_e2e_<name> database.');
    }
    const fixturePrefix = (userId: string) => `e2e-leaderboard:${userId}:`;
    // This suite needs many independent participants. They are real database records,
    // never replacement API responses or replacement market prices.
    router.post('/leaderboard/setup', requireAuth, asyncHandler(async (req, res) => {
        const userId = req.userId!;
        const prefix = fixturePrefix(userId);
        const users = await db.$transaction(async tx => {
            const participants: { id: string; displayName: string | null }[] = [];
            await tx.user.deleteMany({ where: { googleSub: { startsWith: prefix } } });
            await tx.friendship.deleteMany({ where: { OR: [{ senderId: userId }, { recipientId: userId }] } });
            for (let index = 0; index < (req.body?.empty === true ? 0 : 8); index++) {
                const user = await tx.user.create({ data: {
                    googleSub: `${prefix}${index}`, email: `${userId}.${index}@leaderboard-e2e.invalid`,
                    displayName: `E2E Trader ${index + 1}`,
                    account: { create: { cashBalance: 12000 - index * 1000,
                        startingBalance: index % 2 === 0 ? 20000 : 4000 } },
                    // Exercise real price valuation for one accepted participant.
                    ...(index === 0 ? { positions: { create: { ticker: 'MSFT', quantity: 1, averageCost: 100 } } } : {}),
                } });
                participants.push({ id: user.id, displayName: user.displayName });
                if (index < 7) await tx.friendship.create({ data: {
                    senderId: index % 2 === 0 ? userId : user.id,
                    recipientId: index % 2 === 0 ? user.id : userId,
                    pairKey: [userId.toLowerCase(), user.id.toLowerCase()].sort().join(':'),
                    status: index < 6 ? 'ACCEPTED' : 'PENDING',
                } });
            }
            return participants;
        });
        res.json({ ready: true, users });
    }));
    router.post('/leaderboard/cleanup', requireAuth, asyncHandler(async (req, res) => {
        await db.user.deleteMany({ where: { googleSub: { startsWith: fixturePrefix(req.userId!) } } });
        res.json({ ready: true });
    }));
    router.get(['/account/snapshot', '/leaderboard/snapshot'], requireAuth, asyncHandler(async (req, res) => {
        const userId = req.userId!;
        const ids = (await db.user.findMany({ where: { OR: [
            { id: userId }, { googleSub: { startsWith: fixturePrefix(userId) } },
        ] }, select: { id: true } })).map(user => user.id);
        const [accounts, positions, trades, orders, friendships] = await Promise.all([
            db.account.findMany({ where: { userId: { in: ids } }, orderBy: { userId: 'asc' } }),
            db.position.findMany({ where: { userId: { in: ids } }, orderBy: { id: 'asc' } }),
            db.trade.findMany({ where: { userId: { in: ids } }, orderBy: { id: 'asc' } }),
            db.order.findMany({ where: { userId: { in: ids } }, orderBy: { id: 'asc' } }),
            db.friendship.findMany({ where: { OR: [{ senderId: { in: ids } }, { recipientId: { in: ids } }] }, orderBy: { id: 'asc' } }),
        ]);
        res.json({ accounts, positions, trades, orders, friendships });
    }));
    router.post('/account/reset', requireAuth, asyncHandler(async (req, res) => {
        const userId = req.userId;
        if (!userId) {
            res.sendStatus(401);
            return;
        }
        // Never accept a user ID from the caller: reset only the authenticated account.
        await db.$transaction(async tx => {
            await tx.order.deleteMany({ where: { userId } });
            await tx.trade.deleteMany({ where: { userId } });
            await tx.position.deleteMany({ where: { userId } });
            await tx.account.upsert({
                where: { userId },
                create: { userId, cashBalance: 10000, startingBalance: 10000 },
                update: { cashBalance: 10000, startingBalance: 10000 },
            });
        });
        res.json({ ready: true });
    }));
    return router;
}
