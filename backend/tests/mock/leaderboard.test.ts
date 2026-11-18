import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { Prisma } from "../../src/generated/prisma/client";
import { db } from "../../src/prisma/db";
import { marketService } from "../../src/market/service";
import { friendsRouter } from "../../src/routes/friends";

jest.mock("../../src/prisma/db", () => ({ db: { user: {
    findUnique: jest.fn(), findMany: jest.fn(),
} } }));
jest.mock("../../src/market/service", () => ({ marketService: { getStock: jest.fn() } }));
const app = express();
app.use("/api/friends", friendsRouter);
const decimal = (value: number): Prisma.Decimal => new Prisma.Decimal(value);

beforeEach(() => {
    jest.resetAllMocks();
    process.env.JWT_SECRET = "leaderboard-test";
    jest.mocked(db.user.findUnique).mockResolvedValue({ id: "me", isBanned: false } as never);
});

test("requires authentication", async () => {
    await request(app).get("/api/friends/leaderboard").expect(401);
    expect(db.user.findMany).not.toHaveBeenCalled();
});

test("scopes profiles to self and accepted friends, calculates portfolio return, handles missing accounts", async () => {
    jest.mocked(db.user.findMany).mockResolvedValue([
        { id: "me", displayName: "Me", avatarUrl: null,
            positions: [], orders: [], account: { cashBalance: decimal(11000), startingBalance: decimal(10000) } },
        { id: "friend", displayName: null, avatarUrl: null, positions: [], orders: [], account: null },
        { id: "zero", displayName: "Zero", avatarUrl: null,
            positions: [], orders: [], account: { cashBalance: decimal(100), startingBalance: decimal(0) } },
    ] as never);
    const response = await request(app).get("/api/friends/leaderboard")
        .set("Authorization", `Bearer ${jwt.sign({ sub: "me" }, "leaderboard-test")}`).expect(200);
    expect(db.user.findMany).toHaveBeenCalledWith(expect.objectContaining({ where: {
        isBanned: false, OR: [
            { id: "me" },
            { sentFriendships: { some: { recipientId: "me", status: "ACCEPTED" } } },
            { receivedFriendships: { some: { senderId: "me", status: "ACCEPTED" } } },
        ],
    } }));
    expect(response.body.entries).toEqual([
        { user: { id: "me", displayName: "Me", avatarUrl: null }, isYou: true, cashBalance: "11000", totalPortfolioValue: "11000", returnPercent: 10 },
        { user: { id: "friend", displayName: null, avatarUrl: null }, isYou: false, cashBalance: null, totalPortfolioValue: null, returnPercent: null },
        { user: { id: "zero", displayName: "Zero", avatarUrl: null }, isYou: false, cashBalance: "100", totalPortfolioValue: "100", returnPercent: null },
    ]);
    expect(response.headers["cache-control"]).toBe("no-store");
});

const auth = () => `Bearer ${jwt.sign({ sub: "me" }, "leaderboard-test")}`;
const investor = (id: string, cash: number, quantity: number) => ({
    id, displayName: id, avatarUrl: null,
    account: { cashBalance: decimal(cash), startingBalance: decimal(10000) },
    positions: [{ ticker: "AAPL", quantity: decimal(quantity) }], orders: [],
});

test("values holdings and reserved buy cash; reads each symbol once for all friends", async () => {
    jest.mocked(db.user.findMany).mockResolvedValue([
        { ...investor("me", 0, 100), orders: [{ quantity: decimal(2), limitPrice: decimal(150) }] },
        investor("friend", 9000, 1),
    ] as never);
    jest.mocked(marketService.getStock).mockResolvedValue({ currentPrice: "120" } as never);
    const response = await request(app).get("/api/friends/leaderboard").set("Authorization", auth()).expect(200);
    expect(response.body.entries[0]).toMatchObject({ cashBalance: "0", totalPortfolioValue: "12300", returnPercent: 23 });
    expect(response.body.entries[1]).toMatchObject({ totalPortfolioValue: "9120", returnPercent: -8.8 });
    expect(marketService.getStock).toHaveBeenCalledTimes(1);
    expect(marketService.getStock).toHaveBeenCalledWith("AAPL");
    // A price change affects the portfolio without any cash movement.
    jest.mocked(marketService.getStock).mockResolvedValue({ currentPrice: "130" } as never);
    const updated = await request(app).get("/api/friends/leaderboard").set("Authorization", auth()).expect(200);
    expect(updated.body.entries[0].totalPortfolioValue).toBe("13300");
});

test.each([null, "0", "NaN"])("does not rank a partial valuation when a price is %s", async price => {
    jest.mocked(db.user.findMany).mockResolvedValue([investor("me", 500, 10)] as never);
    jest.mocked(marketService.getStock).mockResolvedValue({ currentPrice: price } as never);
    const response = await request(app).get("/api/friends/leaderboard").set("Authorization", auth()).expect(200);
    expect(response.body.entries[0]).toMatchObject({ totalPortfolioValue: null, returnPercent: null });
});

test("an unsupported symbol does not break the leaderboard or undervalue its owner", async () => {
    jest.mocked(db.user.findMany).mockResolvedValue([investor("me", 500, 10)] as never);
    jest.mocked(marketService.getStock).mockRejectedValue(new Error("Unsupported market symbol"));
    const response = await request(app).get("/api/friends/leaderboard").set("Authorization", auth()).expect(200);
    expect(response.body.entries[0].totalPortfolioValue).toBeNull();
});
