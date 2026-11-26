import express from 'express';
import request from 'supertest';
import jwt from 'jsonwebtoken';
import { createE2ERouter } from '../../src/routes/e2e';
import { db } from '../../src/prisma/db';

jest.mock('../../src/prisma/db', () => ({ db: {
    user: { findUnique: jest.fn(), findMany: jest.fn(), deleteMany: jest.fn(), updateMany: jest.fn() },
    account: { findMany: jest.fn() },
    position: { findMany: jest.fn() },
    trade: { findMany: jest.fn() },
    order: { findMany: jest.fn() },
    friendship: { findMany: jest.fn() },
    $transaction: jest.fn(),
} }));

describe('POST /__e2e/account/reset - fixture safeguards (mock database)', () => {
    const original = { ...process.env };
    const tx = {
        user: { deleteMany: jest.fn(), create: jest.fn(async () => ({ id: 'fixture-user', username: 'fixture_name' })),
            findUnique: jest.fn(async () => ({ username: 'existing_name' })), update: jest.fn() },
        friendship: { deleteMany: jest.fn(), create: jest.fn() },
        order: { deleteMany: jest.fn() },
        trade: { deleteMany: jest.fn() },
        position: { deleteMany: jest.fn() },
        account: { upsert: jest.fn() },
    };
    beforeEach(() => {
        jest.clearAllMocks();
        process.env.E2E_ENABLED = 'true';
        process.env.DATABASE_URL = 'mysql://localhost/tradeoff_e2e_tests';
        process.env.JWT_SECRET = 'fixture-test-secret';
        (db.user.findUnique as jest.Mock).mockImplementation(async ({ where }) => ({ id: where.id }));
        (db.$transaction as jest.Mock).mockImplementation(async callback => callback(tx));
    });
    afterEach(() => { process.env = { ...original }; });
    function app() {
        return express().use(express.json()).use('/__e2e', createE2ERouter());
    }
    test('disabled by default: returns 404 without modifying data', async () => {
        delete process.env.E2E_ENABLED;
        await request(app()).post('/__e2e/account/reset').expect(404);
        expect(db.$transaction).not.toHaveBeenCalled();
    });
    test('refuses startup against a non-test database', () => {
        process.env.DATABASE_URL = 'mysql://localhost/tradeoff';
        expect(app).toThrow('disposable');
        expect(db.$transaction).not.toHaveBeenCalled();
    });
    test('rejects missing or invalid authentication without modifying data', async () => {
        await request(app()).post('/__e2e/account/reset').expect(401);
        await request(app()).post('/__e2e/account/reset').set('Authorization', 'Bearer invalid').expect(401);
        expect(db.$transaction).not.toHaveBeenCalled();
    });
    test('resets only the authenticated user, ignoring a different user ID in the body', async () => {
        const token = jwt.sign({ sub: 'current-user' }, process.env.JWT_SECRET!);
        await request(app()).post('/__e2e/account/reset')
            .set('Authorization', `Bearer ${token}`).send({ userId: 'other-user' })
            .expect(200, { ready: true });
        for (const table of [tx.order, tx.trade, tx.position]) {
            expect(table.deleteMany).toHaveBeenCalledWith({ where: { userId: 'current-user' } });
        }
        expect(tx.account.upsert).toHaveBeenCalledWith({
            where: { userId: 'current-user' },
            create: { userId: 'current-user', cashBalance: 10000, startingBalance: 10000 },
            update: { cashBalance: 10000, startingBalance: 10000 },
        });
        expect(db.$transaction).toHaveBeenCalledTimes(1);
    });
    test('leaderboard setup requires authentication and creates real records scoped to the caller', async () => {
        await request(app()).post('/__e2e/leaderboard/setup').expect(401);
        expect(db.$transaction).not.toHaveBeenCalled();
        const token = jwt.sign({ sub: 'current-user' }, process.env.JWT_SECRET!);
        await request(app()).post('/__e2e/leaderboard/setup')
            .set('Authorization', `Bearer ${token}`).expect(200);
        expect(tx.user.create).toHaveBeenCalledTimes(8);
        expect(tx.friendship.create).toHaveBeenCalledTimes(7);
        expect(tx.user.update).not.toHaveBeenCalled(); // Preserve an existing chosen username.
        expect(tx.friendship.create.mock.calls.filter(call =>
            (call as unknown as [{ data: { status: string } }])[0].data.status === 'ACCEPTED')).toHaveLength(6);
        expect(tx.user.deleteMany).toHaveBeenCalledWith({ where: { googleSub: { startsWith: 'e2e-leaderboard:current-user:' } } });
    });
    test('self-only setup creates no participants; cleanup cannot select another caller fixtures', async () => {
        const token = jwt.sign({ sub: 'current-user' }, process.env.JWT_SECRET!);
        await request(app()).post('/__e2e/leaderboard/setup').send({ empty: true })
            .set('Authorization', `Bearer ${token}`).expect(200);
        expect(tx.user.create).not.toHaveBeenCalled();
        await request(app()).post('/__e2e/leaderboard/cleanup').send({ userId: 'other-user' })
            .set('Authorization', `Bearer ${token}`).expect(200);
        expect(db.user.deleteMany).toHaveBeenCalledWith({ where: { googleSub: { startsWith: 'e2e-leaderboard:current-user:' } } });
    });
    test('assigns a temporary self username only when missing, then scopes restoration to that exact value', async () => {
        (tx.user.findUnique as jest.Mock).mockResolvedValueOnce({ username: null });
        const token = jwt.sign({ sub: 'current-user' }, process.env.JWT_SECRET!);
        await request(app()).post('/__e2e/leaderboard/setup').send({ empty: true })
            .set('Authorization', `Bearer ${token}`).expect(200);
        const update = (tx.user.update as jest.Mock).mock.calls[0][0];
        expect(update.where).toEqual({ id: 'current-user' });
        expect(update.data.username).toMatch(/^e2e_[a-f0-9]{12}_self$/);
        await request(app()).post('/__e2e/leaderboard/cleanup')
            .set('Authorization', `Bearer ${token}`).expect(200);
        expect(db.user.updateMany).toHaveBeenCalledWith({
            where: { id: 'current-user', username: update.data.username }, data: { username: null },
        });
    });
    test.each(['/__e2e/account/snapshot', '/__e2e/leaderboard/snapshot'])('%s requires authentication', async path => {
        await request(app()).get(path).expect(401);
        expect(db.user.findMany).not.toHaveBeenCalled();
    });
    test.each(['/__e2e/account/snapshot', '/__e2e/leaderboard/snapshot'])(
        '%s returns every table scoped to the caller and their fixtures', async path => {
            const token = jwt.sign({ sub: 'current-user' }, process.env.JWT_SECRET!);
            (db.user.findMany as jest.Mock).mockResolvedValue([{ id: 'current-user' }, { id: 'fixture-0' }]);
            (db.account.findMany as jest.Mock).mockResolvedValue([{ userId: 'current-user' }]);
            (db.position.findMany as jest.Mock).mockResolvedValue([{ id: 1 }]);
            (db.trade.findMany as jest.Mock).mockResolvedValue([{ id: 2 }]);
            (db.order.findMany as jest.Mock).mockResolvedValue([{ id: 3 }]);
            (db.friendship.findMany as jest.Mock).mockResolvedValue([{ id: 4 }]);
            const res = await request(app()).get(path).set('Authorization', `Bearer ${token}`).expect(200);
            expect(res.body).toEqual({
                accounts: [{ userId: 'current-user' }], positions: [{ id: 1 }], trades: [{ id: 2 }],
                orders: [{ id: 3 }], friendships: [{ id: 4 }],
            });
            expect(db.user.findMany).toHaveBeenCalledWith({ where: { OR: [
                { id: 'current-user' }, { googleSub: { startsWith: 'e2e-leaderboard:current-user:' } },
            ] }, select: { id: true } });
            const ids = ['current-user', 'fixture-0'];
            expect(db.account.findMany).toHaveBeenCalledWith({ where: { userId: { in: ids } }, orderBy: { userId: 'asc' } });
            expect(db.friendship.findMany).toHaveBeenCalledWith({
                where: { OR: [{ senderId: { in: ids } }, { recipientId: { in: ids } }] }, orderBy: { id: 'asc' },
            });
        });
});