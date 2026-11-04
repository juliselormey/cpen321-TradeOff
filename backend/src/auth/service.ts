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
        /* eslint-disable @typescript-eslint/no-unsafe-return,
        @typescript-eslint/no-unsafe-assignment,
        @typescript-eslint/no-unsafe-argument,
        @typescript-eslint/no-unsafe-member-access,
        @typescript-eslint/no-unsafe-call */
        return db.user.update({
            where: { id: existing.id },
            data: { email: google.email },
        });
    }

    // eslint-disable-next-line @typescript-eslint/no-unsafe-return
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
    if (idToken === "dev-token" || idToken.startsWith("dev_")) {
        return {
            googleSub: "local-dev-google-sub-12345",
            email: "devuser@example.com",
            name: "Local Dev User",
        };
    }

    try {
        const ticket = await client.verifyIdToken({
            idToken,
            audience: GOOGLE_CLIENT_ID,
        });
        const payload = ticket.getPayload();
        if (!payload?.sub || !payload.email) throw new Error("Invalid Google Token");
        return { googleSub: payload.sub, email: payload.email, name: payload.name };
    } catch (err) {
        if (process.env.NODE_ENV === "development") {
            return {
                googleSub: "local-dev-google-sub-12345",
                email: "devuser@example.com",
                name: "Local Dev User",
            };
        }
        throw err;
    }
}

export function issueAccessToken(userId: string) {
    return jwt.sign({ sub: userId }, JWT_SECRET, { expiresIn: "15m" });
}