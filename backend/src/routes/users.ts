import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";

export const usersRouter = Router();


usersRouter.post("/me", requireAuth, async(req, res) => {
    const user = await db.orm.public.User.where({ id: req.userId!}).first();
    const account = await db.orm.public.Account.where({ userId: req.userId!}).first();
    res.json({user, account});
})