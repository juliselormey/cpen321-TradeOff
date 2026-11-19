import express from "express";
import request from "supertest";
import jwt from "jsonwebtoken";
import { usersRouter } from "../../src/routes/users";
import { friendsRouter } from "../../src/routes/friends";
import { db } from "../../src/prisma/db";
import { notifyUser } from "../../src/firebase/notifier";

jest.mock("../../src/prisma/db", () => ({ db: {
    user: { findUnique: jest.fn(), update: jest.fn() },
    friendship: {
        findFirst: jest.fn(),
        findUnique: jest.fn(),
        create: jest.fn(),
        findMany: jest.fn(),
        updateMany: jest.fn(),
        deleteMany: jest.fn(),
    },
} }));

jest.mock("../../src/firebase/notifier", () => ({
    notifyUser: jest.fn(),
}));

const sender = "00000000-0000-4000-8000-000000000001";
const recipient = "00000000-0000-4000-8000-000000000002";
const outsider = "00000000-0000-4000-8000-000000000003";
const secret = "friends-test-secret";
const token = (id: string) => `Bearer ${jwt.sign({ sub: id }, secret)}`;
const app = express();
app.use(express.json());
app.use("/api/friends", friendsRouter);
app.use("/api/auth", usersRouter);
const users = jest.mocked(db.user.findUnique);
const friendships = jest.mocked(db.friendship);

beforeEach(() => {
    jest.resetAllMocks();
    process.env.JWT_SECRET = secret;
    users.mockResolvedValue({ id: sender, isBanned: false } as never);
    jest.mocked(notifyUser).mockResolvedValue(undefined);
    friendships.findFirst.mockResolvedValue(null);
    friendships.findUnique.mockResolvedValue(null);
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

test.each([{}, { username: 12 }, { username: "bad name" }, { username: "ab" }, { username: "a".repeat(25) }, { username: "self" }])(
    "rejects invalid or self target %j", async body => {
        await request(app).post("/api/friends/requests").set("Authorization", token(sender))
            .send(body).expect(400);
        expect(friendships.create).not.toHaveBeenCalled();
    });

test.each([null, { id: recipient, isBanned: true }])("rejects unavailable target %j", async target => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce(target as never);
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "recipient" }).expect(404);
});

test("creates a pending request with a direction-independent pair key and public profiles", async () => {
    users.mockResolvedValueOnce({ id: recipient, isBanned: false } as never)
        .mockResolvedValueOnce({ id: sender, isBanned: false } as never);
    friendships.create.mockResolvedValue({
        id: "one",
        status: "PENDING",
        sender: { displayName: "Recipient" },
    } as never);
    const response = await request(app).post("/api/friends/requests").set("Authorization", token(recipient))
        .send({ username: " SENDER " }).expect(201);
    expect(response.body.request.status).toBe("PENDING");
    expect(users).toHaveBeenLastCalledWith({ where: { username: "sender" }, select: { id: true, isBanned: true } });
    expect(friendships.create).toHaveBeenCalledWith({
        data: { senderId: recipient, recipientId: sender, pairKey: `${sender}:${recipient}` },
        include: { sender: { select: { id: true, username: true, displayName: true, avatarUrl: true } },
            recipient: { select: { id: true, username: true, displayName: true, avatarUrl: true } } },
    });
    expect(notifyUser).toHaveBeenCalledTimes(1);
    expect(notifyUser).toHaveBeenCalledWith(
        sender,
        "New friend request",
        "Recipient sent you a friend request.",
    );
});

test("duplicate or reciprocal requests return conflict", async () => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce({ id: recipient, isBanned: false } as never);
    friendships.create.mockRejectedValue({ code: "P2002" });
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "recipient" }).expect(409);
    expect(notifyUser).not.toHaveBeenCalled();
});

test("supports existing numeric user IDs preserved as strings by the migration", async () => {
    users.mockResolvedValueOnce({ id: sender, isBanned: false } as never)
        .mockResolvedValueOnce({ id: "12", isBanned: false } as never);
    friendships.create.mockResolvedValue({
        id: "one",
        status: "PENDING",
        sender: { displayName: "Sender" },
    } as never);
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "legacy_user" }).expect(201);
    expect(friendships.create).toHaveBeenCalledWith(expect.objectContaining({ data: {
        senderId: sender, recipientId: "12", pairKey: `${sender}:12`,
    } }));
    expect(notifyUser).toHaveBeenCalledWith(
        "12",
        "New friend request",
        "Sender sent you a friend request.",
    );
});

test.each([sender, recipient, outsider])("only the recipient can accept (%s)", async actor => {
    users.mockResolvedValue({ id: actor, isBanned: false } as never);
    friendships.findFirst.mockResolvedValue(
        actor === outsider ? null : { senderId: sender, recipientId: recipient } as never
    );
    friendships.updateMany.mockResolvedValue({ count: actor === recipient ? 1 : 0 });
    friendships.findUnique.mockResolvedValue({
        senderId: sender,
        recipient: { displayName: "Recipient" },
    } as never);
    await request(app).post("/api/friends/requests/one/accept").set("Authorization", token(actor))
        .expect(actor === recipient ? 204 : 404);
    expect(friendships.updateMany).toHaveBeenCalledWith({
        where: { id: "one", recipientId: actor, status: "PENDING" }, data: { status: "ACCEPTED" },
    });
    if (actor === recipient) {
        expect(notifyUser).toHaveBeenCalledTimes(1);
        expect(notifyUser).toHaveBeenCalledWith(
            sender,
            "Friend request accepted",
            "Recipient accepted your friend request.",
        );
    } else {
        expect(notifyUser).not.toHaveBeenCalled();
    }
});

test.each(["/requests/one", "/one"])("deletion is scoped to participants and state: %s", async path => {
    friendships.findFirst.mockResolvedValue({
        senderId: sender,
        recipientId: recipient,
    } as never);
    friendships.deleteMany.mockResolvedValueOnce({ count: 1 }).mockResolvedValueOnce({ count: 0 });
    await request(app).delete(`/api/friends${path}`).set("Authorization", token(sender)).expect(204);
    expect(friendships.deleteMany).toHaveBeenCalledWith({ where: {
        id: "one", status: path.startsWith("/requests") ? "PENDING" : "ACCEPTED",
        OR: [{ senderId: sender }, { recipientId: sender }],
    } });
    await request(app).delete(`/api/friends${path}`).set("Authorization", token(sender)).expect(404);
});

test("lists the other user's profile for either direction of friendship", async () => {
    const other = { id: recipient, displayName: "Other", username: "other", avatarUrl: null };
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

test("renaming preserves pending and accepted friendships and uses the new username", async () => {
    const alice = { id: sender, username: "alice", displayName: "Alice", isBanned: false };
    const bob = { id: recipient, username: "bob", displayName: "Bob", isBanned: false };
    const row = { id: "stable-request", senderId: sender, recipientId: recipient,
        pairKey: `${sender}:${recipient}`, status: "PENDING", sender: alice, recipient: bob };
    users.mockImplementation((args) => {
        const where = args.where;
        return Promise.resolve([alice, bob].find(user => where.id ? user.id === where.id : user.username === where.username) ?? null) as never;
    });
    jest.mocked(db.user.update).mockImplementation((args) => {
        Object.assign(bob, args.data);
        return Promise.resolve(bob) as never;
    });
    friendships.create.mockResolvedValue(row as never);
    friendships.findFirst.mockResolvedValue(row as never);
    friendships.findUnique.mockResolvedValue(row as never);
    friendships.findMany.mockResolvedValue([row] as never);
    friendships.updateMany.mockImplementation(() => {
        row.status = "ACCEPTED";
        return Promise.resolve({ count: 1 }) as never;
    });
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "bob" }).expect(201);
    const rename = (username: string) => request(app).patch("/api/auth/me")
        .set("Authorization", token(recipient)).send({ displayName: "Bob", username });
    await rename("bob_new").expect(200);
    const pending = await request(app).get("/api/friends/requests").set("Authorization", token(sender)).expect(200);
    expect(pending.body.outgoing[0].recipient.username).toBe("bob_new");
    expect(pending.body.outgoing[0].id).toBe("stable-request");
    await request(app).post("/api/friends/requests/stable-request/accept")
        .set("Authorization", token(recipient)).expect(204);
    await rename("bob_latest").expect(200);
    const accepted = await request(app).get("/api/friends").set("Authorization", token(sender)).expect(200);
    expect(accepted.body.friends[0]).toMatchObject({ friendshipId: "stable-request",
        user: { id: recipient, username: "bob_latest" } });
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "bob" }).expect(404);
    friendships.create.mockRejectedValue({ code: "P2002" });
    await request(app).post("/api/friends/requests").set("Authorization", token(sender))
        .send({ username: "bob_latest" }).expect(409);
    expect(row.pairKey).toBe(`${sender}:${recipient}`);
    expect(friendships.deleteMany).not.toHaveBeenCalled();
});