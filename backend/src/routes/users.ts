import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";

export const usersRouter = Router();

usersRouter.post("/me", requireAuth, asyncHandler(async (req, res) => {
        const userId = req.userId;
        if (!userId) {
            res.status(401).json({ error: "Unauthorized" });
            return;
        }

        const [user, account] = await Promise.all([
            db.user.findUnique({ where: { id: userId } }),
            db.account.findUnique({ where: { userId } }),
        ]);

        res.json({ user, account });
    }),
);