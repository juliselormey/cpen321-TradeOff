import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";
import { Prisma } from "../generated/prisma/client";
import { marketService } from "../market/service";

export const friendsRouter = Router();
const publicProfile = { id: true, username: true, displayName: true, avatarUrl: true } as const;
const profiles = { sender: { select: publicProfile }, recipient: { select: publicProfile } };
friendsRouter.use(requireAuth);

friendsRouter.get("/leaderboard", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    // Query relationships in the same statement as profiles, so only currently
    // accepted friends (in either direction) and the caller are returned.
    const users = await db.user.findMany({
        where: { isBanned: false, OR: [
            { id: userId },
            { sentFriendships: { some: { recipientId: userId, status: "ACCEPTED" } } },
            { receivedFriendships: { some: { senderId: userId, status: "ACCEPTED" } } },
        ] },
        select: { ...publicProfile, account: { select: { cashBalance: true, startingBalance: true } },
            positions: { select: { ticker: true, quantity: true } },
            orders: { where: { status: "PENDING", side: "BUY", type: "LIMIT" },
                select: { quantity: true, limitPrice: true } },
        },
        orderBy: { id: "asc" },
    });
    // Reuse the shared market cache once per distinct symbol, not once per friend.
    const rawSymbols: string[] = [];
    for (const user of users) {
        for (const pos of user.positions) {
            if (pos.quantity.greaterThan(0)) {
                rawSymbols.push(String(pos.ticker));
            }
        }
    }
    const symbols = Array.from(new Set(rawSymbols));
    const prices = new Map(await Promise.all(symbols.map(async ticker => {
        try {
            const stock = await marketService.getStock(ticker);
            const price = stock.currentPrice == null ? null : new Prisma.Decimal(stock.currentPrice);
            return [ticker, price?.isFinite() && price.greaterThan(0) ? price : null] as const;
        } catch {
            return [ticker, null] as const;
        }
    })));
    res.setHeader("Cache-Control", "no-store");
    res.json({ entries: users.map(user => {
        let total = user.account?.cashBalance ?? null;
        for (const order of user.orders) {
            // Pending buys reserve cash at placement; it still belongs to the user.
            if (total && order.limitPrice) total = total.plus(order.quantity.times(order.limitPrice));
        }
        for (const position of user.positions) {
            if (!position.quantity.greaterThan(0)) continue;
            const price = prices.get(position.ticker);
            if (!price) { total = null; break; }
            if (total) total = total.plus(position.quantity.times(price));
        }
        return {
            user: { id: user.id, username: user.username, displayName: user.displayName, avatarUrl: user.avatarUrl },
            isYou: user.id === userId,
            cashBalance: user.account?.cashBalance.toString() ?? null,
            totalPortfolioValue: total?.toString() ?? null,
            returnPercent: total && user.account?.startingBalance.greaterThan(0)
                ? total.minus(user.account.startingBalance)
                    .dividedBy(user.account.startingBalance).times(100).toNumber()
                : null,
        };
    }) });
}));

friendsRouter.get("/", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    const rows = await db.friendship.findMany({
        where: { status: "ACCEPTED", OR: [{ senderId: userId }, { recipientId: userId }] },
        include: profiles,
        orderBy: [{ createdAt: "desc" }, { id: "asc" }],
    });
    res.json({ friends: rows.map(row => ({
        friendshipId: row.id,
        user: row.senderId === userId ? row.recipient : row.sender,
    })) });
}));

friendsRouter.get("/requests", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    const rows = await db.friendship.findMany({
        where: { status: "PENDING", OR: [{ senderId: userId }, { recipientId: userId }] },
        include: profiles,
        orderBy: [{ createdAt: "desc" }, { id: "asc" }],
    });
    res.json({ incoming: rows.filter(row => row.recipientId === userId),
        outgoing: rows.filter(row => row.senderId === userId) });
}));

friendsRouter.post("/requests", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    const target: unknown = req.body?.username;
    const username = typeof target === "string" ? target.trim().toLowerCase() : "";
    if (!/^[a-z0-9_]{3,24}$/.test(username)) {
        res.status(400).json({ error: "Username must be 3–24 letters, numbers or underscores" });
        return;
    }
    const recipient = await db.user.findUnique({ where: { username },
        select: { id: true, isBanned: true } });
    if (!recipient || recipient.isBanned) {
        res.status(404).json({ error: "Username not found" });
        return;
    }
    if (recipient.id === userId) {
        res.status(400).json({ error: "You cannot add yourself" });
        return;
    }
    try {
        const friendship = await db.friendship.create({ data: {
            senderId: userId, recipientId: recipient.id,
            pairKey: [userId.toLowerCase(), recipient.id.toLowerCase()].sort().join(":"),
        }, include: profiles });
        res.status(201).json({ request: friendship });
    } catch (error: unknown) {
        // The database constraint also protects simultaneous reciprocal requests.
        if (typeof error === "object" && error !== null && "code" in error && error.code === "P2002") {
            res.status(409).json({ error: "Already friends or a request is pending" });
            return;
        }
        throw error;
    }
}));

friendsRouter.post("/requests/:id/accept", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    // Conditional writes prevent outsiders, senders, and stale requests from changing state.
    const result = await db.friendship.updateMany({
        where: { id: String(req.params.id), recipientId: userId, status: "PENDING" },
        data: { status: "ACCEPTED" },
    });
    if (!result.count) {
        res.status(404).json({ error: "Incoming request not found" });
        return;
    }
    res.status(204).send();
}));

friendsRouter.delete("/requests/:id", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    const result = await db.friendship.deleteMany({ where: {
        id: String(req.params.id), status: "PENDING",
        OR: [{ senderId: userId }, { recipientId: userId }],
    } });
    if (!result.count) {
        res.status(404).json({ error: "Request not found" });
        return;
    }
    res.status(204).send();
}));

friendsRouter.delete("/:id", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) { res.status(401).json({ error: "Unauthorized" }); return; }
    const result = await db.friendship.deleteMany({ where: {
        id: String(req.params.id), status: "ACCEPTED",
        OR: [{ senderId: userId }, { recipientId: userId }],
    } });
    if (!result.count) {
        res.status(404).json({ error: "Friendship not found" });
        return;
    }
    res.status(204).send();
}));