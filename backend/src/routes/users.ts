import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";
import { hasErrorCode } from "../util/dbErrors";

export const usersRouter = Router();
usersRouter.use(requireAuth);

const editableProfile = { id: true, email: true, displayName: true, username: true } as const;

// TODO: the app should read the profile with GET /me; remove this POST once nothing else calls it.
usersRouter.post("/me", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const [user, account] = await Promise.all([
        db.user.findUnique({ where: { id: userId } }),
        db.account.findUnique({ where: { userId } }),
    ]);
    res.json({ user, account });
}));

usersRouter.get("/me", asyncHandler(async (req, res) => {
    const user = await db.user.findUnique({ where: { id: req.userId! }, select: editableProfile });
    res.json({ user });
}));

usersRouter.patch("/me", asyncHandler(async (req, res) => {
    const body: unknown = req.body;
    if (!body || typeof body !== "object" || Array.isArray(body)) {
        res.status(400).json({ error: "Invalid profile" });
        return;
    }
    const input = body as Record<string, unknown>;
    const displayName = typeof input.displayName === "string" ? input.displayName.trim() : "";
    const username = typeof input.username === "string" ? input.username.trim().toLowerCase() : "";
    if (!displayName || displayName.length > 60 || !/^[a-z0-9_]{3,24}$/.test(username) ||
        Object.keys(input).some(key => !["displayName", "username"].includes(key))) {
        res.status(400).json({ error: "Invalid name or username" });
        return;
    }
    try {
        const user = await db.user.update({ where: { id: req.userId! },
            data: { displayName, username }, select: editableProfile });
        res.json({ user });
    } catch (error) {
        if (hasErrorCode(error, "P2002")) {
            res.status(409).json({ error: "Username is already taken" });
            return;
        }
        throw error;
    }
}));

// UC-05 Delete Account. Account, positions, orders, trades, tokens and friendships are removed by the schema's cascades.
usersRouter.delete("/me", asyncHandler(async (req, res) => {
    await db.user.delete({ where: { id: req.userId! } });
    res.status(204).send();
}));