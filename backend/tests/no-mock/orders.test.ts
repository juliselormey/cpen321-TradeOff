import request from "supertest";
import jwt from "jsonwebtoken";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";
import { requireEnv } from "../../src/config/env";
import { processPendingLimitOrders, stopLimitOrderWorker } from "../../src/market/limitWorker";
import { getServerPrice } from "../../src/market/settlement";
import { Prisma } from "../../src/generated/prisma/client";

const app = createApp();
const JWT_SECRET = requireEnv("JWT_SECRET");

// Set 20 second timeout for external Alpaca IEX network requests
jest.setTimeout(20000);

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
        stopLimitOrderWorker();
        if (testUserId) {
            await db.order.deleteMany({ where: { userId: testUserId } });
            await db.trade.deleteMany({ where: { userId: testUserId } });
            await db.position.deleteMany({ where: { userId: testUserId } });
            await db.account.deleteMany({ where: { userId: testUserId } });
            await db.user.delete({ where: { id: testUserId } });
        }
        await db.$disconnect();
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
                    limitPrice: 175,
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
    /* 2. Price Fulfillment Accuracy & Price Improvement Refunds                  */
    /* -------------------------------------------------------------------------- */
    describe("Price Fulfillment Accuracy & Price Improvement Refunds", () => {
        test("Limit BUY fills immediately at actual market price when limit price >= market price", async () => {
            const livePriceDecimal = await getServerPrice("AAPL");
            const livePrice = livePriceDecimal.toNumber();
            const limitPrice = livePrice + 50.0;

            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 2,
                    pricePerShare: livePrice,
                    limitPrice,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("FILLED");
            expect(res.body.ownedQuantity).toBe(2);
            expect(res.body.cashBalance).toBe(10000 - (2 * livePrice));

            const trades = await db.trade.findMany({ where: { userId: testUserId } });
            expect(trades).toHaveLength(1);
            expect(trades[0]).toBeDefined();
            expect(Number(trades[0]?.pricePerShare)).toBe(livePrice);
            expect(Number(trades[0]?.totalAmount)).toBe(2 * livePrice);
        }, 15000);

        test("Limit SELL fills immediately at higher market price when limit price <= market price", async () => {
            const livePriceDecimal = await getServerPrice("AAPL");
            const livePrice = livePriceDecimal.toNumber();
            const limitPrice = Math.max(1.0, livePrice - 50.0);

            // Buy 5 shares first
            await request(app)
                .post("/api/trades/execute/market")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ ticker: "AAPL", side: "BUY", quantity: 5, pricePerShare: livePrice });

            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "SELL",
                    quantity: 2,
                    pricePerShare: livePrice,
                    limitPrice,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("FILLED");
            expect(res.body.ownedQuantity).toBe(3);
            expect(res.body.cashBalance).toBe(10000 - (3 * livePrice));
        }, 15000);
    });

    /* -------------------------------------------------------------------------- */
    /* 3. Limit Order Reservation & Market Order Priority Interaction             */
    /* -------------------------------------------------------------------------- */
    describe("Limit Order Reservation & Market Order Priority Interaction", () => {
        test("Limit BUY reserves funds immediately when limit price < market price (stays PENDING)", async () => {
            const res = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "AAPL",
                    side: "BUY",
                    quantity: 2,
                    pricePerShare: 200,
                    limitPrice: 1.0,
                });

            expect(res.status).toBe(200);
            expect(res.body.status).toBe("PENDING");
            expect(res.body.cashBalance).toBe(9998);

            const dbOrders = await db.order.findMany({ where: { userId: testUserId } });
            expect(dbOrders).toHaveLength(1);
            expect(dbOrders[0]).toBeDefined();
            expect(dbOrders[0]?.status).toBe("PENDING");
            expect(Number(dbOrders[0]?.limitPrice)).toBe(1.0);
        }, 15000);

        test("Pending Limit BUY reserves cash and prevents a subsequent Market BUY from overcommitting funds", async () => {
            const livePriceDecimal = await getServerPrice("MSFT");
            const livePrice = livePriceDecimal.toNumber();

            const limitRes = await request(app)
                .post("/api/trades/execute/limit")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    ticker: "MSFT",
                    side: "BUY",
                    quantity: 20,
                    pricePerShare: livePrice,
                    limitPrice: 400.0,
                });

            expect(limitRes.status).toBe(200);

            if (limitRes.body.status === "PENDING") {
                expect(limitRes.body.cashBalance).toBe(2000);

                const marketRes = await request(app)
                    .post("/api/trades/execute/market")
                    .set("Authorization", `Bearer ${authToken}`)
                    .send({
                        ticker: "AAPL",
                        side: "BUY",
                        quantity: 20,
                        pricePerShare: 150,
                    });

                expect(marketRes.status).toBe(400);
                expect(marketRes.body).toEqual({ error: "Insufficient cash balance" });

                const pendingOrders = await request(app)
                    .get("/api/trades/orders/pending")
                    .set("Authorization", `Bearer ${authToken}`);

                expect(pendingOrders.body.orders[0]).toBeDefined();
                const orderId = pendingOrders.body.orders[0]?.id;

                await request(app)
                    .post(`/api/trades/orders/${orderId}/cancel`)
                    .set("Authorization", `Bearer ${authToken}`);

                const posRes = await request(app)
                    .get("/api/trades/position/MSFT")
                    .set("Authorization", `Bearer ${authToken}`);

                expect(posRes.body.cashBalance).toBe(10000);
            }
        }, 20000);
    });

    /* -------------------------------------------------------------------------- */
    /* 4. Background Limit Order Worker Execution                                */
    /* -------------------------------------------------------------------------- */
    describe("Background Limit Order Matching Engine Execution", () => {
        test("processPendingLimitOrders matches and fills pending orders correctly", async () => {
            const livePriceDecimal = await getServerPrice("AAPL");
            const livePrice = livePriceDecimal.toNumber();

            await db.order.create({
                data: {
                    userId: testUserId,
                    ticker: "AAPL",
                    side: "BUY",
                    type: "LIMIT",
                    status: "PENDING",
                    quantity: new Prisma.Decimal(2),
                    limitPrice: new Prisma.Decimal(livePrice + 100),
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
        }, 20000);
    });
});
