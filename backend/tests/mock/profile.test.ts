import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { usersRouter } from "../../src/routes/users";
import { db } from "../../src/prisma/db";

jest.mock("../../src/prisma/db", () => ({ db: {
    user: { findUnique: jest.fn(), update: jest.fn() },
    account: { findUnique: jest.fn() },
} }));
const app = express();
app.use(express.json());
app.use("/api/users", usersRouter);
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
