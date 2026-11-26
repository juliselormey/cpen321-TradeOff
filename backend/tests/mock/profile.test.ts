import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { usersRouter } from "../../src/routes/users";
import { favoritesRouter } from "../../src/routes/favorites";
import { LIVE_SYMBOLS } from "../../src/market/catalog";
import { db } from "../../src/prisma/db";

jest.mock("../../src/prisma/db", () => ({ db: {
    user: { findUnique: jest.fn(), update: jest.fn(), delete: jest.fn() },
    account: { findUnique: jest.fn() },
    stockFavorite: { findMany: jest.fn(), createMany: jest.fn(), deleteMany: jest.fn() },
} }));
const app = express();
app.use(express.json());
app.use("/api/users", usersRouter);
app.use("/api/favorites", favoritesRouter);
const secret = "profile-test-secret";
const auth = () => "Bearer " + jwt.sign({ sub: "self" }, secret);
const users = jest.mocked(db.user);
beforeEach(() => {
    jest.resetAllMocks();
    process.env.JWT_SECRET = secret;
    users.findUnique.mockResolvedValue({ id: "self", isBanned: false } as never);
});
test("profile read and update require authentication", async () => {
    await request(app).get("/api/users/me").expect(401);
    await request(app).patch("/api/users/me").send({ displayName: "A", username: "abc" }).expect(401);
    expect(users.update).not.toHaveBeenCalled();
});
test("reads only the authenticated user's public account fields", async () => {
    await request(app).get("/api/users/me").set("Authorization", auth()).expect(200);
    expect(users.findUnique).toHaveBeenLastCalledWith({ where: { id: "self" },
        select: { id: true, email: true, displayName: true, username: true } });
});
test.each([
    {}, { displayName: "", username: "abc" }, { displayName: "a".repeat(61), username: "abc" },
    { displayName: "A", username: "ab" }, { displayName: "A", username: "a".repeat(25) },
    { displayName: "A", username: "bad name" }, { displayName: "A", username: "abc", role: "ADMIN" },
    { displayName: "A", username: "abc", id: "other" },
])("rejects invalid or unauthorized profile input %j", async body => {
    await request(app).patch("/api/users/me").set("Authorization", auth()).send(body).expect(400);
    expect(users.update).not.toHaveBeenCalled();
});
test("normalizes username and updates only the caller", async () => {
    users.update.mockResolvedValue({ id: "self", displayName: "Jerry", username: "jerry_123" } as never);
    const response = await request(app).patch("/api/users/me").set("Authorization", auth())
        .send({ displayName: " Jerry ", username: " JERRY_123 " }).expect(200);
    expect(response.body.user.username).toBe("jerry_123");
    expect(users.update).toHaveBeenCalledWith({ where: { id: "self" },
        data: { displayName: "Jerry", username: "jerry_123" },
        select: { id: true, email: true, displayName: true, username: true } });
});
test("duplicate usernames return 409", async () => {
    users.update.mockRejectedValue({ code: "P2002" });
    await request(app).patch("/api/users/me").set("Authorization", auth())
        .send({ displayName: "Jerry", username: "jerry" }).expect(409);
});
test("banned users cannot edit profiles", async () => {
    users.findUnique.mockResolvedValue({ id: "self", isBanned: true } as never);
    await request(app).patch("/api/users/me").set("Authorization", auth())
        .send({ displayName: "Jerry", username: "jerry" }).expect(403);
    expect(users.update).not.toHaveBeenCalled();
});

test("POST /me returns the user and their account, and requires authentication", async () => {
    await request(app).post("/api/users/me").expect(401);
    jest.mocked(db.account.findUnique).mockResolvedValue({ userId: "self", cashBalance: "10000" } as never);
    const res = await request(app).post("/api/users/me").set("Authorization", auth()).expect(200);
    expect(res.body.user).toMatchObject({ id: "self" });
    expect(res.body.account).toEqual({ userId: "self", cashBalance: "10000" });
    expect(db.account.findUnique).toHaveBeenCalledWith({ where: { userId: "self" } });
});
test("DELETE /me removes only the caller's account", async () => {
    await request(app).delete("/api/users/me").expect(401);
    expect(users.delete).not.toHaveBeenCalled();
    users.delete.mockResolvedValue({} as never);
    await request(app).delete("/api/users/me").set("Authorization", auth()).expect(204);
    expect(users.delete).toHaveBeenCalledWith({ where: { id: "self" } });
});
test("PATCH /me rejects a non-object body", async () => {
    const res = await request(app).patch("/api/users/me").set("Authorization", auth()).send([]).expect(400);
    expect(res.body).toEqual({ error: "Invalid profile" });
});
test("PATCH /me reports validation failures with the generic message", async () => {
    const res = await request(app).patch("/api/users/me").set("Authorization", auth()).send({}).expect(400);
    expect(res.body).toEqual({ error: "Invalid name or username" });
});
test("PATCH /me does not swallow unexpected database errors", async () => {
    const errors = jest.spyOn(console, "error").mockImplementation(() => undefined);
    users.update.mockRejectedValue(new Error("db down"));
    await request(app).patch("/api/users/me").set("Authorization", auth())
        .send({ displayName: "Jerry", username: "jerry" }).expect(500);
    errors.mockRestore();
});

describe("stock favorites", () => {
    const favorites = jest.mocked(db.stockFavorite);
    const SYMBOL = LIVE_SYMBOLS[0] as string;

    test.each([["get", ""], ["put", `/${SYMBOL}`], ["delete", `/${SYMBOL}`]] as const)(
        "%s requires authentication", async (method, path) => {
            await request(app)[method](`/api/favorites${path}`).expect(401);
            expect(favorites.findMany).not.toHaveBeenCalled();
            expect(favorites.createMany).not.toHaveBeenCalled();
            expect(favorites.deleteMany).not.toHaveBeenCalled();
        });

    test("lists the caller's favorite tickers, uncached", async () => {
        favorites.findMany.mockResolvedValue([{ ticker: "AAPL" }, { ticker: "MSFT" }] as never);
        const res = await request(app).get("/api/favorites").set("Authorization", auth()).expect(200);
        expect(res.body).toEqual({ tickers: ["AAPL", "MSFT"] });
        expect(res.headers["cache-control"]).toBe("no-store");
        expect(favorites.findMany).toHaveBeenCalledWith({
            where: { userId: "self" }, select: { ticker: true }, orderBy: { ticker: "asc" },
        });
    });

    test("adding a favorite normalizes the ticker and ignores duplicates", async () => {
        favorites.createMany.mockResolvedValue({ count: 1 } as never);
        await request(app).put(`/api/favorites/${SYMBOL.toLowerCase()}`).set("Authorization", auth()).expect(204);
        expect(favorites.createMany).toHaveBeenCalledWith({
            data: [{ userId: "self", ticker: SYMBOL }], skipDuplicates: true,
        });
    });

    test("adding an unsupported symbol returns 404 and writes nothing", async () => {
        await request(app).put("/api/favorites/NOTREAL").set("Authorization", auth()).expect(404);
        expect(favorites.createMany).not.toHaveBeenCalled();
    });

    test("removing a favorite is scoped to the caller and idempotent", async () => {
        favorites.deleteMany.mockResolvedValue({ count: 0 } as never);
        await request(app).delete("/api/favorites/aapl").set("Authorization", auth()).expect(204);
        expect(favorites.deleteMany).toHaveBeenCalledWith({ where: { userId: "self", ticker: "AAPL" } });
    });
});