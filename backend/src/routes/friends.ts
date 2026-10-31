import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";

export const friendsRouter = Router();
const publicProfile = { id: true, displayName: true, avatarUrl: true } as const;
const profiles = { sender: { select: publicProfile }, recipient: { select: publicProfile } };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const legacyUserId = /^[1-9][0-9]{0,9}$/;

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
        select: { ...publicProfile, account: { select: { cashBalance: true, startingBalance: true } } },
        orderBy: { id: "asc" },
    });
    res.setHeader("Cache-Control", "no-store");
    res.json({ entries: users.map(user => ({
        user: { id: user.id, displayName: user.displayName, avatarUrl: user.avatarUrl },
        isYou: user.id === userId,
        cashBalance: user.account?.cashBalance.toString() ?? null,
        returnPercent: user.account?.startingBalance.greaterThan(0)
            ? user.account.cashBalance.minus(user.account.startingBalance)
                .dividedBy(user.account.startingBalance).times(100).toNumber()
            : null,
    })) });
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
    const target: unknown = req.body?.userId;
    if (typeof target !== "string" || (!uuid.test(target) && !legacyUserId.test(target))) {
        res.status(400).json({ error: "userId must be a UUID or a legacy numeric ID string" });
        return;
    }
    const recipientId = target.toLowerCase();
    if (recipientId === userId.toLowerCase()) {
        res.status(400).json({ error: "You cannot add yourself" });
        return;
    }
    const recipient = await db.user.findUnique({ where: { id: recipientId },
        select: { id: true, isBanned: true } });
    if (!recipient || recipient.isBanned) {
        res.status(404).json({ error: "User not found" });
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
