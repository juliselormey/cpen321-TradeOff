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
});