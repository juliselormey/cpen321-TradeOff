import { Router } from "express";
import { authenticatedHandler } from "../middleware/auth";
import { db } from "../prisma/db";

export const usersRouter = Router();

// TODO: the app should read the profile with GET /me; remove this POST once nothing else calls it.
usersRouter.post("/me", authenticatedHandler(async (req, res) => {
        const userId = req.userId;

        const [user, account] = await Promise.all([
            db.user.findUnique({ where: { id: userId } }),
            db.account.findUnique({ where: { userId } }),
        ]);

        res.json({ user, account });
    }),
);
const editableProfile = { id: true, email: true, displayName: true, username: true } as const;
usersRouter.get("/me", authenticatedHandler(async (req, res) => {
    const userId = req.userId;
    const user = await db.user.findUnique({ where: { id: userId }, select: editableProfile });
    res.json({ user });
}));
usersRouter.patch("/me", authenticatedHandler(async (req, res) => {
    const userId = req.userId;
    const body: unknown = req.body;
    if (!body || typeof body !== "object" || Array.isArray(body)) {
        res.status(400).json({ error: "Invalid profile" }); return;
    }
    const input = body as Record<string, unknown>;
    const displayName = typeof input.displayName === "string" ? input.displayName.trim() : "";
    const username = typeof input.username === "string" ? input.username.trim().toLowerCase() : "";
    if (!displayName || displayName.length > 60 || !/^[a-z0-9_]{3,24}$/.test(username) ||
        Object.keys(input).some(key => !["displayName", "username"].includes(key))) {
        res.status(400).json({ error: "Invalid name or username" }); return;
    }
    try {
        const user = await db.user.update({ where: { id: userId },
            data: { displayName, username }, select: editableProfile });
        res.json({ user });
    } catch (error) {
        if (typeof error === "object" && error !== null && "code" in error && error.code === "P2002") {
            res.status(409).json({ error: "Username is already taken" }); return;
        }
        throw error;
    }
}));

// UC-05 Delete Account. Account, positions, orders, trades, tokens and friendships are removed by the schema's cascades.
usersRouter.delete("/me", authenticatedHandler(async (req, res) => {
    const userId = req.userId;
    await db.user.delete({ where: { id: userId } });
    res.status(204).send();
}));
