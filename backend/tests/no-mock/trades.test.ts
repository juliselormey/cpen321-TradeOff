import request from "supertest";
import jwt from "jsonwebtoken";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";
import { requireEnv } from "../../src/config/env";

const app = createApp();
const JWT_SECRET = requireEnv("JWT_SECRET");

describe("No-Mock Integration Tests: Stock Trading System", () => {
    let testUserId: string;
    let authToken: string;

    beforeAll(async () => {
        const testUser = await db.user.create({
            data: {
                googleSub: `test-sub-trades-${Date.now()}`,
                email: `test-trades-${Date.now()}@example.com`,
                displayName: "Trades Integration Test User",
                account: {
                    create: {
                        cashBalance: "10000.0000",
                        startingBalance: "10000.0000",
                    },
                },
            },
        });

        testUserId = testUser.id;
        authToken = jwt.sign({ sub: testUserId }, JWT_SECRET, { expiresIn: "1h" });
    });

    afterAll(async () => {
        if (testUserId) {
            await db.trade.deleteMany({ where: { userId: testUserId } });
            await db.position.deleteMany({ where: { userId: testUserId } });
            await db.account.deleteMany({ where: { userId: testUserId } });
            await db.user.delete({ where: { id: testUserId } });
        }
    });

    beforeEach(async () => {
        await db.trade.deleteMany({ where: { userId: testUserId } });
        await db.position.deleteMany({ where: { userId: testUserId } });
        await db.account.update({
            where: { userId: testUserId },
            data: { cashBalance: "10000.0000" },
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 1. Authentication & Authorization Enforcement                              */
    /* -------------------------------------------------------------------------- */
    describe("Authentication & Authorization Enforcement", () => {
        test("GET /api/trades/position/AAPL without token returns 401 Unauthorized", async () => {
            const res = await request(app).get("/api/trades/position/AAPL");
            expect(res.status).toBe(401);
            expect(res.body).toEqual({ error: "Unauthorized" });
        });

        test("POST /api/trades/execute without token returns 401 Unauthorized", async () => {
            const res = await request(app)
                .post("/api/trades/execute")
                .send({ ticker: "AAPL", type: "BUY", quantity: 1, pricePerShare: 100 });
            expect(res.status).toBe(401);
            expect(res.body).toEqual({ error: "Unauthorized" });
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 2. Basic Buy and Sell Execution (Path & Database Persistence)        */
    /* -------------------------------------------------------------------------- */
    describe("Basic Order Execution & Database State Verification", () => {
        test("BUY 5 shares of AAPL @ $100 updates cash balance and creates position", async () => {
            const res = await request(app)
                .post("/api/trades/execute")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "AAPL", type: "BUY", quantity: 5, pricePerShare: 100 });

            expect(res.status).toBe(200);
            expect(res.body.success).toBe(true);
            expect(res.body.cashBalance).toBe(9500);
            expect(res.body.ownedQuantity).toBe(5);

            // Verify Database Persistence
            const dbAccount = await db.account.findUnique({ where: { userId: testUserId } });
            expect(Number(dbAccount?.cashBalance)).toBe(9500);

            const dbPosition = await db.position.findUnique({
                where: { userId_ticker: { userId: testUserId, ticker: "AAPL" } },
            });
            expect(Number(dbPosition?.quantity)).toBe(5);
        });

        test("BUY followed by SELL updates position and cash balance correctly", async () => {
            await request(app)
                .post("/api/trades/execute")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "AAPL", type: "BUY", quantity: 5, pricePerShare: 100 });

            const sellRes = await request(app)
                .post("/api/trades/execute")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "AAPL", type: "SELL", quantity: 2, pricePerShare: 150 });

            expect(sellRes.status).toBe(200);
            expect(sellRes.body.cashBalance).toBe(9800);
            expect(sellRes.body.ownedQuantity).toBe(3);
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 3. Financial Constraints & Balance Validation                              */
    /* -------------------------------------------------------------------------- */
    describe("Financial Constraints & Balance Validation", () => {
        test("BUY exceeding cash balance returns 400 Insufficient cash balance", async () => {
            const res = await request(app)
                .post("/api/trades/execute")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "MSFT", type: "BUY", quantity: 100, pricePerShare: 400 });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({ error: "Insufficient cash balance" });
        });

        test("SELL without owning shares returns 400 Insufficient shares owned", async () => {
            const res = await request(app)
                .post("/api/trades/execute")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "NVDA", type: "SELL", quantity: 5, pricePerShare: 100 });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({ error: "Insufficient shares owned" });
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 4. Non-Controllable Concurrency & Double-Spend Race Condition Tests       */
    /* -------------------------------------------------------------------------- */
    describe("Non-Controllable Concurrency & Race Condition Stress Tests", () => {
        test("Simultaneous parallel BUY requests prevent negative cash balance race conditions", async () => {
            await db.account.update({
                where: { userId: testUserId },
                data: { cashBalance: "1000.0000" },
            });

            // Fire 5 simultaneous BUY orders of $800 each ($4,000 total vs $1,000 cash)
            const requests = Array.from({ length: 5 }, () =>
                request(app)
                    .post("/api/trades/execute")
                    .set("Authorization", `Bearer ${authToken}`)
                    .send({ ticker: "AMZN", type: "BUY", quantity: 1, pricePerShare: 800 })
            );

            const responses = await Promise.all(requests);

            const successful = responses.filter((r) => r.status === 200);
            const failed = responses.filter((r) => r.status === 400);

            // Exactly 1 request MUST succeed, and 4 MUST fail due to insufficient funds
            expect(successful).toHaveLength(1);
            expect(failed).toHaveLength(4);

            // Verify final database state: cash balance is exactly $200
            const dbAccount = await db.account.findUnique({ where: { userId: testUserId } });
            expect(Number(dbAccount?.cashBalance)).toBe(200);
        });
    });
});