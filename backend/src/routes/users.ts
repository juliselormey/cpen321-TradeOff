import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";

export const usersRouter = Router();


usersRouter.post("/me", requireAuth, async(req, res) => {
    const user = await db.user.findUnique({ where: { id: req.userId! } });
    const account = await db.account.findUnique({ where: { userId: req.userId! } });
    res.json({user, account});
})