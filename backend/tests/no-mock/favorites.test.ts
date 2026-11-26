import { randomUUID } from "node:crypto";
import jwt from "jsonwebtoken";
import request from "supertest";
import { createApp } from "../../src/app";
import { requireEnv } from "../../src/config/env";
import { db } from "../../src/prisma/db";

const endpoint = "/api/users/me/favorites";

describe("Stock favorites", () => {
    const app = createApp();
    const userIds: string[] = [];

    async function createUserToken() {
        const suffix = randomUUID();
        const user = await db.user.create({
            data: { googleSub: `favorites-${suffix}`, email: `favorites-${suffix}@example.com` },
        });
        const userId = user.id as string;
        userIds.push(userId);
        return `Bearer ${jwt.sign({ sub: userId }, requireEnv("JWT_SECRET"), { expiresIn: "5m" })}`;
    }

    async function createUserWithId() {
        const auth = await createUserToken();
        return { auth, id: userIds[userIds.length - 1] as string };
    }

    afterAll(async () => {
        try {
            if (userIds.length) await db.user.deleteMany({ where: { id: { in: userIds } } });
        } finally {
            await db.$disconnect();
        }
    });

    test("requires an authenticated user for reads and writes", async () => {
        await request(app).get(endpoint).expect(401);
        await request(app).put(`${endpoint}/AAPL`).expect(401);
        await request(app).delete(`${endpoint}/AAPL`).expect(401);
    });

    test("stores independent favorites per user and makes repeated writes safe", async () => {
        const first = await createUserToken();
        const second = await createUserToken();

        await request(app).get(endpoint).set("Authorization", first).expect(200, { tickers: [] });
        await request(app).put(`${endpoint}/NOT_A_STOCK`).set("Authorization", first).expect(404);
        await request(app).put(`${endpoint}/aapl`).set("Authorization", first).expect(204);
        await request(app).put(`${endpoint}/AAPL`).set("Authorization", first).expect(204);
        await request(app).get(endpoint).set("Authorization", first).expect(200, { tickers: ["AAPL"] });
        await request(app).get(endpoint).set("Authorization", second).expect(200, { tickers: [] });

        await request(app).put(`${endpoint}/AAPL`).set("Authorization", second).expect(204);
        await request(app).put(`${endpoint}/MSFT`).set("Authorization", second).expect(204);
        await request(app).delete(`${endpoint}/AAPL`).set("Authorization", second).expect(204);
        await request(app).delete(`${endpoint}/AAPL`).set("Authorization", second).expect(204);
        await request(app).get(endpoint).set("Authorization", first).expect(200, { tickers: ["AAPL"] });
        await request(app).get(endpoint).set("Authorization", second).expect(200, { tickers: ["MSFT"] });
    });
    test("lists favorites alphabetically and never caches them", async () => {
        const auth = await createUserToken();
        await request(app).put(`${endpoint}/MSFT`).set("Authorization", auth).expect(204);
        await request(app).put(`${endpoint}/AAPL`).set("Authorization", auth).expect(204);
        const res = await request(app).get(endpoint).set("Authorization", auth).expect(200);
        expect(res.body).toEqual({ tickers: ["AAPL", "MSFT"] });
        expect(res.headers["cache-control"]).toBe("no-store");
    });

    test("removing an unknown or unsupported ticker is a harmless no-op", async () => {
        const auth = await createUserToken();
        await request(app).delete(`${endpoint}/NOT_A_STOCK`).set("Authorization", auth).expect(204);
        await request(app).delete(`${endpoint}/AAPL`).set("Authorization", auth).expect(204);
        await request(app).get(endpoint).set("Authorization", auth).expect(200, { tickers: [] });
    });

    test("an unsupported ticker is rejected without creating a row", async () => {
        const { auth, id } = await createUserWithId();
        await request(app).put(`${endpoint}/NOT_A_STOCK`).set("Authorization", auth).expect(404);
        expect(await db.stockFavorite.count({ where: { userId: id } })).toBe(0);
    });

    test("duplicate writes keep exactly one row", async () => {
        const { auth, id } = await createUserWithId();
        await Promise.all([1, 2, 3].map(() => request(app).put(`${endpoint}/AAPL`).set("Authorization", auth)));
        expect(await db.stockFavorite.count({ where: { userId: id, ticker: "AAPL" } })).toBe(1);
    });

    test("deleting the account removes its favorites", async () => {
        const { auth, id } = await createUserWithId();
        await request(app).put(`${endpoint}/AAPL`).set("Authorization", auth).expect(204);
        await db.user.delete({ where: { id } });
        expect(await db.stockFavorite.count({ where: { userId: id } })).toBe(0);
    });

    test("a banned user cannot read or change favorites", async () => {
        const { auth, id } = await createUserWithId();
        await db.user.update({ where: { id }, data: { isBanned: true } });
        await request(app).get(endpoint).set("Authorization", auth).expect(403);
        await request(app).put(`${endpoint}/AAPL`).set("Authorization", auth).expect(403);
        await request(app).delete(`${endpoint}/AAPL`).set("Authorization", auth).expect(403);
    });
});