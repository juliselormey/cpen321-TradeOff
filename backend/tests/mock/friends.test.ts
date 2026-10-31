import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { friendsRouter } from "../../src/routes/friends";
import { db } from "../../src/prisma/db";

jest.mock("../../src/prisma/db", () => ({ db: {
    user: { findUnique: jest.fn() },
    friendship: { create: jest.fn(), findMany: jest.fn(), updateMany: jest.fn(), deleteMany: jest.fn() },
} }));

const sender = "00000000-0000-4000-8000-000000000001";
const recipient = "00000000-0000-4000-8000-000000000002";
const outsider = "00000000-0000-4000-8000-000000000003";
const secret = "friends-test-secret";
const token = (id: string) => `Bearer ${jwt.sign({ sub: id }, secret)}`;
const app = express();
app.use(express.json());
app.use("/api/friends", friendsRouter);
const users = jest.mocked(db.user.findUnique);
const friendships = jest.mocked(db.friendship);

beforeEach(() => {
    jest.resetAllMocks();
    process.env.JWT_SECRET = secret;
    users.mockResolvedValue({ id: sender, isBanned: false } as never);
});

test.each([
    ["get", "/"], ["get", "/requests"], ["post", "/requests"],
    ["post", "/requests/one/accept"], ["delete", "/requests/one"], ["delete", "/one"],
] as const)("%s %s requires authentication", async (method, path) => {
    // eslint-disable-next-line security/detect-object-injection -- Method is restricted to the literal test cases above.
    await request(app)[method](`/api/friends${path}`).expect(401);
    expect(friendships.create).not.toHaveBeenCalled();
});

test("invalid tokens and banned users are rejected", async () => {
    await request(app).get("/api/friends").set("Authorization", "Bearer invalid").expect(401);
    users.mockResolvedValue({ id: sender, isBanned: true } as never);
    await request(app).get("/api/friends").set("Authorization", token(sender)).expect(403);
});

test.each([{}, { userId: 12 }, { userId: "not-a-uuid" }, { userId: sender }])(
    "rejects invalid or self target %j", async body => {
        await request(app).post("/api/friends/requests").set("Authorization", token(sender))
            .send(body).expect(400);
        expect(friendships.create).not.toHaveBeenCalled();
    });

test.each([null, { id: recipient, isBanned: true }])("rejects unavailable target %j", async target => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce(target as never);
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ userId: recipient }).expect(404);
});

test("creates a pending request with a direction-independent pair key and public profiles", async () => {
    users.mockResolvedValueOnce({ id: recipient, isBanned: false } as never)
        .mockResolvedValueOnce({ id: sender, isBanned: false } as never);
    friendships.create.mockResolvedValue({ id: "one", status: "PENDING" } as never);
    const response = await request(app).post("/api/friends/requests").set("Authorization", token(recipient))
        .send({ userId: sender }).expect(201);
    expect(response.body.request.status).toBe("PENDING");
    expect(friendships.create).toHaveBeenCalledWith({
        data: { senderId: recipient, recipientId: sender, pairKey: `${sender}:${recipient}` },
        include: { sender: { select: { id: true, displayName: true, avatarUrl: true } },
            recipient: { select: { id: true, displayName: true, avatarUrl: true } } },
    });
});

test("duplicate or reciprocal requests return conflict", async () => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce({ id: recipient, isBanned: false } as never);
    friendships.create.mockRejectedValue({ code: "P2002" });
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ userId: recipient }).expect(409);
});

test("supports existing numeric user IDs preserved as strings by the migration", async () => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce({ id: "12", isBanned: false } as never);
    friendships.create.mockResolvedValue({ id: "one", status: "PENDING" } as never);
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ userId: "12" }).expect(201);
    expect(friendships.create).toHaveBeenCalledWith(expect.objectContaining({ data: {
        senderId: sender, recipientId: "12", pairKey: `${sender}:12`,
    } }));
});

test.each([sender, recipient, outsider])("only the recipient can accept (%s)", async actor => {
    users.mockResolvedValue({ id: actor, isBanned: false } as never);
    friendships.updateMany.mockResolvedValue({ count: actor === recipient ? 1 : 0 });
    await request(app).post("/api/friends/requests/one/accept").set("Authorization", token(actor))
        .expect(actor === recipient ? 204 : 404);
    expect(friendships.updateMany).toHaveBeenCalledWith({
        where: { id: "one", recipientId: actor, status: "PENDING" }, data: { status: "ACCEPTED" },
    });
});

test.each(["/requests/one", "/one"])("deletion is scoped to participants and state: %s", async path => {
    friendships.deleteMany.mockResolvedValueOnce({ count: 1 }).mockResolvedValueOnce({ count: 0 });
    await request(app).delete(`/api/friends${path}`).set("Authorization", token(sender)).expect(204);
    expect(friendships.deleteMany).toHaveBeenCalledWith({ where: {
        id: "one", status: path.startsWith("/requests") ? "PENDING" : "ACCEPTED",
        OR: [{ senderId: sender }, { recipientId: sender }],
    } });
    await request(app).delete(`/api/friends${path}`).set("Authorization", token(sender)).expect(404);
});

test("lists the other user's profile for either direction of friendship", async () => {
    const other = { id: recipient, displayName: "Other", avatarUrl: null };
    friendships.findMany.mockResolvedValue([
        { id: "one", senderId: sender, recipient: other },
        { id: "two", senderId: recipient, sender: other },
    ] as never);
    const response = await request(app).get("/api/friends").set("Authorization", token(sender)).expect(200);
    expect(response.body.friends).toEqual([
        { friendshipId: "one", user: other }, { friendshipId: "two", user: other },
    ]);
    expect(friendships.findMany).toHaveBeenCalledWith(expect.objectContaining({ where: {
        status: "ACCEPTED", OR: [{ senderId: sender }, { recipientId: sender }],
    } }));
});

test("separates incoming and outgoing pending requests", async () => {
    const incoming = { id: "one", senderId: recipient, recipientId: sender };
    const outgoing = { id: "two", senderId: sender, recipientId: recipient };
    friendships.findMany.mockResolvedValue([incoming, outgoing] as never);
    const response = await request(app).get("/api/friends/requests").set("Authorization", token(sender)).expect(200);
    expect(response.body).toEqual({ incoming: [incoming], outgoing: [outgoing] });
    expect(friendships.findMany).toHaveBeenCalledWith(expect.objectContaining({ where: {
        status: "PENDING", OR: [{ senderId: sender }, { recipientId: sender }],
    } }));
});
