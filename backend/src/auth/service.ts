import {OAuth2Client} from "google-auth-library";
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
    const existing = await db.orm.public.User.where({googleSub: google.googleSub}).first();
    if (existing) {
        const updated = await db.orm.public.User.where({id: existing.id}).update({email: google.email, displayName: google.name ?? null});
        return updated ?? existing;
    }

    return db.transaction(async (tx) => {
        const user = await tx.orm.public.User.create({
            googleSub: google.googleSub,
            email: google.email,
            displayName: google.name ?? null,
        });
        const money = (value: string) => value as never;
        await tx.orm.public.Account.create({
            userId: user.id,
            cashBalance: money("10000"),
            startingBalance: money("10000"),
        });
        return user;
    });
}

export async function verifyGoogleToken(idToken: string) {
    const ticket = await client.verifyIdToken({
            idToken, audience: GOOGLE_CLIENT_ID,
        });
    const payload = ticket.getPayload();
    if (!payload?.sub || !payload.email) throw new Error("Invalid Google Token");
    return {googleSub: payload.sub, email: payload.email, name: payload.name};
}

export function issueAccessToken(userId: string) {
    return jwt.sign({sub: userId}, JWT_SECRET, {expiresIn: "15m"});
}