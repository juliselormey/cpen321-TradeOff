import { NextFunction, Request, RequestHandler, Response } from "express";
import jwt from "jsonwebtoken";
import { db } from "../prisma/db";
import { requireEnv } from "../config/env";

declare module "express-serve-static-core" {
    interface Request {
        userId?: string;
    }
}

async function authenticate(req: Request, res: Response, next: NextFunction): Promise<void> {
    const header = req.headers.authorization;
    if (!header?.startsWith("Bearer ")) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    let userId: string;
    try {
        const decoded = jwt.verify(header.slice(7), requireEnv("JWT_SECRET"), {
            algorithms: ["HS256"],
        });
        if (typeof decoded === "string" || typeof decoded.sub !== "string") {
            throw new Error("Bad payload");
        }
        userId = decoded.sub;
    } catch {
        res.status(401).json({ error: "Invalid or expired token" });
        return;
    }

    const user = await db.user.findUnique({ where: { id: userId } });
    if (!user) {
        res.status(401).json({ error: "User not found" });
        return;
    }
    if (user.isBanned) {
        res.status(403).json({ error: "Account is banned" });
        return;
    }

    req.userId = user.id;
    next();
}

export const requireAuth: RequestHandler = (req, res, next) => {
    authenticate(req, res, next).catch(next);
};