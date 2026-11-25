import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { Prisma } from "../../src/generated/prisma/client";
import { db } from "../../src/prisma/db";
import { marketService } from "../../src/market/service";
import { friendsRouter } from "../../src/routes/friends";
import { PriceUnavailableError, STARTING_BALANCE, loadPrices, valuePortfolio } from "../../src/market/valuation";

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
    jest.mocked(marketService.getStock).mockResolvedValue({ currentPrice: "120", status: "ok" } as never);
    const response = await request(app).get("/api/friends/leaderboard").set("Authorization", auth()).expect(200);
    expect(response.body.entries[0]).toMatchObject({ cashBalance: "0", totalPortfolioValue: "12300", returnPercent: 23 });
    expect(response.body.entries[1]).toMatchObject({ totalPortfolioValue: "9120", returnPercent: -8.8 });
    expect(marketService.getStock).toHaveBeenCalledTimes(1);
    expect(marketService.getStock).toHaveBeenCalledWith("AAPL");
    // A price change affects the portfolio without any cash movement.
    jest.mocked(marketService.getStock).mockResolvedValue({ currentPrice: "130", status: "ok" } as never);
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

describe("valuation helpers", () => {
    const getStock = jest.mocked(marketService.getStock);
    const acct = (cash: number, starting: number) => ({ cashBalance: decimal(cash), startingBalance: decimal(starting) });

    test("loadPrices looks up each distinct ticker once", async () => {
        getStock.mockResolvedValue({ currentPrice: "120", status: "ok" } as never);
        const prices = await loadPrices(["AAPL", "AAPL", "MSFT"]);
        expect(getStock).toHaveBeenCalledTimes(2);
        expect(prices.get("AAPL")?.toString()).toBe("120");
        expect(prices.size).toBe(2);
    });

    test.each([
        ["stale quote", { currentPrice: "120", status: "stale" }],
        ["unavailable quote", { currentPrice: null, status: "unavailable" }],
        ["ok without a price", { currentPrice: null, status: "ok" }],
        ["zero price", { currentPrice: "0", status: "ok" }],
        ["negative price", { currentPrice: "-5", status: "ok" }],
        ["NaN price", { currentPrice: "NaN", status: "ok" }],
        ["garbage price", { currentPrice: "abc", status: "ok" }],
    ])("loadPrices maps a %s to null", async (_name, stock) => {
        getStock.mockResolvedValue(stock as never);
        expect((await loadPrices(["AAPL"])).get("AAPL")).toBeNull();
    });

    test("loadPrices maps a lookup failure to null", async () => {
        getStock.mockRejectedValue(new Error("Unsupported market symbol"));
        expect((await loadPrices(["NOPE"])).get("NOPE")).toBeNull();
    });

    test("defaults to the starting balance when the user has no account", () => {
        const v = valuePortfolio({ account: null, positions: [], pendingBuyOrders: [] }, new Map());
        expect(v.totalValue.equals(STARTING_BALANCE)).toBe(true);
        expect(v.profitLoss.toString()).toBe("0");
        expect(v.returnPercent?.toString()).toBe("0");
    });

    test("adds reserved buy cash and values holdings at the current price", () => {
        const v = valuePortfolio({
            account: acct(500, 1000),
            positions: [{ ticker: "AAPL", quantity: decimal(10), averageCost: decimal(40) }],
            pendingBuyOrders: [{ quantity: decimal(2), limitPrice: decimal(100) }, { quantity: decimal(5), limitPrice: null }],
        }, new Map([["AAPL", decimal(50)]]));
        expect(v.reservedCash.toString()).toBe("200");
        expect(v.holdingsValue.toString()).toBe("500");
        expect(v.totalValue.toString()).toBe("1200");
        expect(v.profitLoss.toString()).toBe("200");
        expect(v.returnPercent?.toString()).toBe("20");
        expect(v.holdings[0]?.profitLoss?.toString()).toBe("100");
        expect(v.allocation[0]?.label).toBe("Cash");
        expect(v.allocation[0]?.percent).toBeCloseTo((700 / 1200) * 100);
        expect(v.allocation[1]?.label).toBe("AAPL");
        expect(v.allocation[1]?.percent).toBeCloseTo((500 / 1200) * 100);
    });

    test("a holding without a cost basis has unknown profit/loss", () => {
        const v = valuePortfolio({ account: acct(0, 100), positions: [{ ticker: "AAPL", quantity: decimal(1) }],
            pendingBuyOrders: [] }, new Map([["AAPL", decimal(50)]]));
        expect(v.holdings[0]).toMatchObject({ averageCost: null, profitLoss: null });
    });

    test("empty positions are ignored even without a price", () => {
        const v = valuePortfolio({ account: acct(100, 100), positions: [{ ticker: "AAPL", quantity: decimal(0) }],
            pendingBuyOrders: [] }, new Map());
        expect(v.holdings).toEqual([]);
    });

    test.each([[new Map()], [new Map([["AAPL", null]])]])("a held ticker with no price throws (%j)", prices => {
        const run = () => valuePortfolio({ account: acct(100, 100), positions: [{ ticker: "AAPL", quantity: decimal(1) }],
            pendingBuyOrders: [] }, prices as Map<string, Prisma.Decimal | null>);
        expect(run).toThrow(PriceUnavailableError);
        expect(run).toThrow("Price unavailable for AAPL");
    });

    test("a zero-value portfolio has zero allocation, and no return when it started from zero", () => {
        const empty = valuePortfolio({ account: acct(0, 100), positions: [], pendingBuyOrders: [] }, new Map());
        expect(empty.allocation).toEqual([{ label: "Cash", percent: 0 }]);
        expect(empty.profitLoss.toString()).toBe("-100");
        const zeroStart = valuePortfolio({ account: acct(100, 0), positions: [], pendingBuyOrders: [] }, new Map());
        expect(zeroStart.returnPercent).toBeNull();
    });
});