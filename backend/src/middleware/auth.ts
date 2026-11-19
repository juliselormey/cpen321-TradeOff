import { NextFunction, Request, RequestHandler, Response } from "express";
import jwt from "jsonwebtoken";
import { db } from "../prisma/db";
import { requireEnv } from "../config/env";

declare module "express-serve-static-core" {
    interface Request { userId?: string; }
}

export class AuthenticationError extends Error {
    constructor(readonly status: number, message: string) { super(message); }
}

/** Shared authentication for HTTP requests and WebSocket upgrades. */
export async function authenticateBearer(header: string | undefined): Promise<{ userId: string; expiresAt?: number }> {
    if (!header?.startsWith("Bearer ")) throw new AuthenticationError(401, "Unauthorized");
    let decoded: jwt.JwtPayload;
    try {
        const payload = jwt.verify(header.slice(7), requireEnv("JWT_SECRET"), { algorithms: ["HS256"] });
        if (typeof payload === "string" || typeof payload.sub !== "string") throw new Error("Bad payload");
        decoded = payload;
    } catch {
        throw new AuthenticationError(401, "Invalid or expired token");
    }
    const user = await db.user.findUnique({ where: { id: decoded.sub! } });
    if (!user) throw new AuthenticationError(401, "User not found");
    if (user.isBanned) throw new AuthenticationError(403, "Account is banned");
    return { userId: user.id, ...(decoded.exp === undefined ? {} : { expiresAt: decoded.exp * 1000 }) };
}

async function authenticate(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
        req.userId = (await authenticateBearer(req.headers.authorization)).userId;
    } catch (error) {
        if (!(error instanceof AuthenticationError)) throw error;
        res.status(error.status).json({ error: error.message });
        return;
    }
    next();
}

export const requireAuth: RequestHandler = (req, res, next) => {
    authenticate(req, res, next).catch((error: unknown) => next(error));
};
