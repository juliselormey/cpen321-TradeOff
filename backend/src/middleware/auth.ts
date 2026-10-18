import {RequestHandler} from "express";
import jwt from "jsonwebtoken";
import {db} from "../prisma/db";
import {requireEnv} from "../config/env";

declare global {
    namespace Express {
        interface Request {
            userId?: number;
        }
    }
}

export const requireAuth: RequestHandler = async (req, res, next) => {
    const header = req.headers.authorization;
    if(!header?.startsWith("Bearer ")) {
        return res.status(400).json({ error: "Unauthorized"});
    }

    let userId: number;
    try {
        const decoded = jwt.verify(header.slice(7), requireEnv("JWT_SECRET"), {
            algorithms: ["HS256"],
        });

        if (typeof decoded === "string" || !decoded.sub) throw new Error("Bad payload");
        userId = Number(decoded.sub);
        if(!Number.isInteger(userId)) throw new Error("Bad sub");
    } catch {
        return res.status(401).json({error: "Invalid or expired token"});
    }

    const user = await db.orm.public.User.where({id: userId}).first();
    if (!user) return res.status(401).json({error: "User not found"});
    if (user.isBanned) return res.status(403).json({error: "Account is banned"});

    req.userId = user.id;
    next();
};