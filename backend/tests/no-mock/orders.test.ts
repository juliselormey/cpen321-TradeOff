import request from "supertest";
import jwt from "jsonwebtoken";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";
import { requireEnv } from "../../src/config/env";
import { processPendingLimitOrders } from "../../src/market/limitWorker";

const app = createApp();
const JWT_SECRET = requireEnv("JWT_SECRET");

describe("No-Mock Integration Tests: Market & Limit Orders System", () => {
    let testUserId: string;
    let authToken: string;

    beforeAll(async () => {
        const testUser = await db.user.create({
            data: {
                googleSub: `test-sub-orders-${Date.now()}`,
                email: `test-orders-${Date.now()}@example.com`,
                displayName: "Orders System Integration Test User",
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
            await db.order.deleteMany({ where: { userId: testUserId } });
            await db.trade.deleteMany({ where: { userId: testUserId } });
            await db.position.deleteMany({ where: { userId: testUserId } });
            await db.account.deleteMany({ where: { userId: testUserId } });
            await db.user.delete({ where: { id: testUserId } });
        }
    });

    beforeEach(async () => {
        await db.order.deleteMany({ where: { userId: testUserId } });
        await db.trade.deleteMany({ where: { userId: testUserId } });
        await db.position.deleteMany({ where: { userId: testUserId } });
        await db.account.update({
            where: { userId: testUserId },
            data: { cashBalance: "10000.0000" },
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 1. Market vs Limit Order Parameter Validation                              */
    /* -------------------------------------------------------------------------- */
    describe("Parameter Validation for Market & Limit Orders", () => {
        test("POST /api/trades/execute/market with limitPrice returns 400 Bad Request", async () => {
            const res = await request(app)
                .post("/api/trades/execute/market")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 1,
                    pricePerShare: 180,
                    limitPrice: 175, // Forbidden for market orders
                });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({ error: "Limit price is forbidden for market orders" });
        });

        test("POST /api/trades/execute/limit missing limitPrice returns 400 Bad Request", async () => {
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 1,
                    pricePerShare: 180,
                });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({
                error: "Limit price is required for limit orders and must be positive",
            });
        });

        test("POST /api/trades/execute/limit with negative or zero limitPrice returns 400 Bad Request", async () => {
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 1,
                    pricePerShare: 180,
                    limitPrice: -50,
                });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({
                error: "Limit price is required for limit orders and must be positive",
            });
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 2. Limit Buy Order Fund Reservation & Immediate Fill                       */
    /* -------------------------------------------------------------------------- */
    describe("Limit Buy Order Fund Reservation & Immediate Fill", () => {
        test("Limit BUY reserves funds immediately upon order placement", async () => {
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 2,
                    pricePerShare: 180, // Market price > limit price ($150), stays PENDING
                    limitPrice: 150,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("PENDING");
            expect(res.body.cashBalance).toBe(9700); // $10,000 - $300 reserved

            // Verify order in database
            const dbOrders = await db.order.findMany({ where: { userId: testUserId } });
            expect(dbOrders).toHaveLength(1);
            expect(dbOrders[0].status).toBe("PENDING");
            expect(Number(dbOrders[0].limitPrice)).toBe(150);
        });

        test("Limit BUY fills immediately when market price <= limit price", async () => {
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 2,
                    pricePerShare: 180,
                    limitPrice: 190,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("FILLED");
            expect(res.body.ownedQuantity).toBe(2);
            expect(res.body.cashBalance).toBe(9640);
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 3. Limit Sell Order Share Reservation & Immediate Fill                      */
    /* -------------------------------------------------------------------------- */
    describe("Limit Sell Order Share Reservation & Immediate Fill", () => {
        test("Limit SELL reserves shares from position upon placement", async () => {
            // Buy 5 shares first via Market Order
            await request(app)
                .post("/api/trades/execute/market")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 5,
                    pricePerShare: 100,
                });

            // Place Limit SELL for 3 shares @ $150 when market price is $120
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "SELL",
                    quantity: 3,
                    pricePerShare: 120,
                    limitPrice: 150,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("PENDING");
            expect(res.body.ownedQuantity).toBe(2); // 5 - 3 reserved = 2 remaining

            // Trying to sell 3 shares again should fail due to insufficient unreserved shares
            const res2 = await request(app)
                .post("/api/trades/execute/market")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "SELL",
                    quantity: 3,
                    pricePerShare: 120,
                });

            expect(res2.status).toBe(400);
            expect(res2.body).toEqual({ error: "Insufficient shares owned" });
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 4. Order Cancellation & Reserved Funds / Shares Release                   */
    /* -------------------------------------------------------------------------- */
    describe("Order Cancellation & Asset Release", () => {
        test("Cancelling a pending Limit BUY releases reserved cash funds", async () => {
            const placeRes = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "MSFT",
                    side: "BUY",
                    quantity: 2,
                    pricePerShare: 400,
                    limitPrice: 150,
                });

            const pendingOrders = await request(app)
                .get("/api/trades/orders/pending")
                .set("Authorization", `Bearer ${authToken}`);

            expect(pendingOrders.body.orders).toHaveLength(1);
            const orderId = pendingOrders.body.orders[0].id;

            const cancelRes = await request(app)
                .post(`/api/trades/orders/${orderId}/cancel`)
                .set("Authorization", `Bearer ${authToken}`);

            expect(cancelRes.status).toBe(200);
            expect(cancelRes.body.success).toBe(true);

            const posRes = await request(app)
                .get("/api/trades/position/MSFT")
                .set("Authorization", `Bearer ${authToken}`);

            expect(posRes.body.cashBalance).toBe(10000);
        });
    });

    /* -------------------------------------------------------------------------- */
    /* 5. Background Limit Order Worker Matching                                 */
    /* -------------------------------------------------------------------------- */
    describe("Background Limit Order Matching Engine", () => {
        test("processPendingLimitOrders matches and fills pending orders correctly", async () => {
            await db.order.create({
                data: {
                    userId: testUserId,
                    ticker: "AAPL",
                    side: "BUY",
                    type: "LIMIT",
                    status: "PENDING",
                    quantity: new Prisma.Decimal(2),
                    limitPrice: new Prisma.Decimal(300),
                },
            });

            await db.account.update({
                where: { userId: testUserId },
                data: { cashBalance: "9400.0000" },
            });

            const result = await processPendingLimitOrders();
            expect(result.processedCount).toBeGreaterThanOrEqual(1);

            const orders = await db.order.findMany({ where: { userId: testUserId } });
            const filledOrder = orders.find((o) => o.status === "FILLED");
            expect(filledOrder).toBeDefined();
            expect(filledOrder?.side).toBe("BUY");
        });
    });
});
