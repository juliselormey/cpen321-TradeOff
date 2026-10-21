import { OAuth2Client } from "google-auth-library";
import jwt from "jsonwebtoken";
import { db } from "../prisma/db";
import { requireEnv } from "../config/env";

const GOOGLE_CLIENT_ID = requireEnv("GOOGLE_CLIENT_ID");
const JWT_SECRET = requireEnv("JWT_SECRET");

const client = new OAuth2Client(GOOGLE_CLIENT_ID);

export async function findOrCreateUser(google: {
    googleSub: string;
    email: string;
    name?: string | undefined;
}) {
    const existing = await db.user.findUnique({
        where: { googleSub: google.googleSub },
    });

    if (existing) {
        return db.user.update({
            where: { id: existing.id },
            data: { email: google.email, displayName: google.name ?? null },
        });
    }

    // Nested create: the user and account are created atomically in one call
    return db.user.create({
        data: {
            googleSub: google.googleSub,
            email: google.email,
            displayName: google.name ?? null,
            account: {
                create: {
                    cashBalance: "10000",
                    startingBalance: "10000",
                },
            },
        },
    });
}

export async function verifyGoogleToken(idToken: string) {
    const ticket = await client.verifyIdToken({
        idToken,
        audience: GOOGLE_CLIENT_ID,
    });
    const payload = ticket.getPayload();
    if (!payload?.sub || !payload.email) throw new Error("Invalid Google Token");
    return { googleSub: payload.sub, email: payload.email, name: payload.name };
}

export function issueAccessToken(userId: string) {
    return jwt.sign({ sub: userId }, JWT_SECRET, { expiresIn: "15m" });
}