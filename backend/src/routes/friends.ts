import { Request, Response, Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";
import { hasErrorCode } from "../util/dbErrors";
import { notifyUser } from "../firebase/notifier";
import { PriceUnavailableError, loadPrices, valuePortfolio } from "../market/valuation";
import { publishFriendsChanged } from "../realtime/friendEvents";

export const friendsRouter = Router();
const publicProfile = { id: true, username: true, displayName: true, avatarUrl: true } as const;
const profiles = { sender: { select: publicProfile }, recipient: { select: publicProfile } };

const orderedByNewest = [{ createdAt: "desc" as const }, { id: "asc" as const }];
const involving = (userId: string) => ({ OR: [{ senderId: userId }, { recipientId: userId }] });

/** Same valuation rule as GET /api/portfolio; null when a held price is unavailable. */
function tryValuePortfolio(...args: Parameters<typeof valuePortfolio>) {
    try {
        return valuePortfolio(...args);
    } catch (error) {
        if (error instanceof PriceUnavailableError) return null; // entry shows "Unavailable"
        throw error;
    }
}

friendsRouter.get("/leaderboard", asyncHandler(async (req, res) => {
    const userId = req.userId!;
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
    // One market-cache lookup per distinct symbol.
    const prices = await loadPrices(users.flatMap(user =>
        user.positions.filter(p => p.quantity.greaterThan(0)).map(p => p.ticker)));
    res.setHeader("Cache-Control", "no-store");
    res.json({ entries: users.map(user => {
        const valuation = user.account
            ? tryValuePortfolio({ account: user.account, positions: user.positions, pendingBuyOrders: user.orders }, prices)
            : null;
        return {
            user: { id: user.id, username: user.username, displayName: user.displayName, avatarUrl: user.avatarUrl },
            isYou: user.id === userId,
            cashBalance: user.account?.cashBalance.toString() ?? null,
            totalPortfolioValue: valuation?.totalValue.toString() ?? null,
            returnPercent: valuation?.returnPercent?.toNumber() ?? null,
        };
    }) });
}));

friendsRouter.get("/", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const rows = await db.friendship.findMany({
        where: { status: "ACCEPTED", ...involving(userId) },
        include: profiles,
        orderBy: orderedByNewest,
    });
    res.json({ friends: rows.map(row => ({
        friendshipId: row.id,
        user: row.senderId === userId ? row.recipient : row.sender,
    })) });
}));

friendsRouter.get("/requests", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const rows = await db.friendship.findMany({
        where: { status: "PENDING", ...involving(userId) },
        include: profiles,
        orderBy: orderedByNewest,
    });
    res.json({ incoming: rows.filter(row => row.recipientId === userId),
        outgoing: rows.filter(row => row.senderId === userId) });
}));

friendsRouter.post("/requests", asyncHandler(async (req, res) => {
    const userId = req.userId!;
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
        publishFriendsChanged([userId, recipient.id]);
        res.status(201).json({ request: friendship });
        void notifyUser(
            recipient.id,
            "New friend request",
            `${friendship.sender.displayName} sent you a friend request.`,
        );
    } catch (error: unknown) {
        // The database constraint also protects simultaneous reciprocal requests.
        if (hasErrorCode(error, "P2002")) {
            res.status(409).json({ error: "Already friends or a request is pending" });
            return;
        }
        throw error;
    }
}));

/**
 * Shared flow for accept / decline / unfriend. `write` is a conditional write scoped to the
 * caller, so outsiders, senders and stale requests change nothing and get a 404.
 * Returns the friendship id on success, or null after responding 404.
 */
async function changeFriendship(
    req: Request, res: Response, notFound: string,
    write: (id: string, userId: string) => Promise<{ count: number }>,
): Promise<string | null> {
    const userId = req.userId!;
    const id = String(req.params.id);
    const participants = await db.friendship.findFirst({
        where: { id, ...involving(userId) }, select: { senderId: true, recipientId: true },
    });
    const { count } = await write(id, userId);
    if (!count) {
        res.status(404).json({ error: notFound });
        return null;
    }
    if (participants) publishFriendsChanged([participants.senderId, participants.recipientId]);
    res.status(204).send();
    return id;
}

async function notifyAccepted(id: string): Promise<void> {
    try {
        if (!db?.friendship?.findUnique) return;
        const friendship = await db.friendship.findUnique({ where: { id }, include: profiles });
        if (friendship) {
            await notifyUser(
                friendship.senderId,
                "Friend request accepted",
                `${friendship.recipient.displayName} accepted your friend request.`,
            );
        }
    } catch (error) {
        console.error("Friend request notification failed", error);
    }
}

friendsRouter.post("/requests/:id/accept", asyncHandler(async (req, res) => {
    const id = await changeFriendship(req, res, "Incoming request not found", (friendshipId, userId) =>
        db.friendship.updateMany({
            where: { id: friendshipId, recipientId: userId, status: "PENDING" },
            data: { status: "ACCEPTED" },
        }));
    if (id) void notifyAccepted(id);
}));

friendsRouter.delete("/requests/:id", asyncHandler(async (req, res) => {
    await changeFriendship(req, res, "Request not found", (id, userId) =>
        db.friendship.deleteMany({ where: { id, status: "PENDING", ...involving(userId) } }));
}));

friendsRouter.delete("/:id", asyncHandler(async (req, res) => {
    await changeFriendship(req, res, "Friendship not found", (id, userId) =>
        db.friendship.deleteMany({ where: { id, status: "ACCEPTED", ...involving(userId) } }));
}));
