import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { Prisma } from "../../src/generated/prisma/client";
import { db } from "../../src/prisma/db";
import { friendsRouter } from "../../src/routes/friends";

jest.mock("../../src/prisma/db", () => ({ db: { user: {
    findUnique: jest.fn(), findMany: jest.fn(),
} } }));
const app = express();
app.use("/api/friends", friendsRouter);
const decimal = (value: number) => new Prisma.Decimal(value);

beforeEach(() => {
    jest.resetAllMocks();
    process.env.JWT_SECRET = "leaderboard-test";
    jest.mocked(db.user.findUnique).mockResolvedValue({ id: "me", isBanned: false } as never);
});

test("requires authentication", async () => {
    await request(app).get("/api/friends/leaderboard").expect(401);
    expect(db.user.findMany).not.toHaveBeenCalled();
});

test("scopes profiles to self and accepted friends, calculates cash change, handles missing accounts", async () => {
    jest.mocked(db.user.findMany).mockResolvedValue([
        { id: "me", displayName: "Me", avatarUrl: null,
            account: { cashBalance: decimal(11000), startingBalance: decimal(10000) } },
        { id: "friend", displayName: null, avatarUrl: null, account: null },
        { id: "zero", displayName: "Zero", avatarUrl: null,
            account: { cashBalance: decimal(100), startingBalance: decimal(0) } },
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
        { user: { id: "me", displayName: "Me", avatarUrl: null }, isYou: true, cashBalance: "11000", returnPercent: 10 },
        { user: { id: "friend", displayName: null, avatarUrl: null }, isYou: false, cashBalance: null, returnPercent: null },
        { user: { id: "zero", displayName: "Zero", avatarUrl: null }, isYou: false, cashBalance: "100", returnPercent: null },
    ]);
    expect(response.headers["cache-control"]).toBe("no-store");
});
