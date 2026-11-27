import request from "supertest";
import jwt from "jsonwebtoken";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";

jest.mock("../../src/prisma/db", () => ({
    db: {
        user: { findUnique: jest.fn() },
        notificationDevice: {
            upsert: jest.fn(),
            deleteMany: jest.fn(),
        },
    },
}));

const app = createApp();
const secret = "notification-test-secret";
const userId = "notification-test-user";
const installationId = "cABCDEFGHIJKLMNOPQRSTU";
const devices = jest.mocked(db.notificationDevice);
const users = jest.mocked(db.user.findUnique);
const token = jwt.sign({ sub: userId }, secret);

beforeEach(() => {
    jest.resetAllMocks();
    jest.spyOn(console, "error").mockImplementation(() => undefined);
    process.env.JWT_SECRET = secret;
    users.mockResolvedValue({
        id: userId,
        isBanned: false,
    } as never);
});

afterEach(() => {
    jest.restoreAllMocks();
});

// Interface: PUT /api/notif/device
describe("Mocked: PUT /api/notif/device", () => {
    // Input: Authenticated request with valid installation ID
    // Mocked behavior: Database upsert rejects
    // Expected status code: 500
    // Expected behavior: Database failure is handled gracefully
    // Expected output: Server error
    test("database failure returns 500", async () => {
        devices.upsert.mockRejectedValue(
            new Error("Database unavailable"),
        );

        const res = await request(app)
            .put("/api/notif/device")
            .set("Authorization", `Bearer ${token}`)
            .send({ installationId });

        expect(res.status).toBe(500);
        expect(res.body).toEqual({ error: "Server error" });
        expect(devices.upsert).toHaveBeenCalledTimes(1);
    });
});

// Interface: DELETE /api/notif/device
describe("Mocked: DELETE /api/notif/device", () => {
    // Input: Authenticated request with valid installation ID
    // Mocked behavior: Database deletion rejects
    // Expected status code: 500
    // Expected behavior: Database failure is handled gracefully
    // Expected output: Server error
    test("database failure returns 500", async () => {
        devices.deleteMany.mockRejectedValue(
            new Error("Database unavailable"),
        );

        const res = await request(app)
            .delete("/api/notif/device")
            .set("Authorization", `Bearer ${token}`)
            .send({ installationId });

        expect(res.status).toBe(500);
        expect(res.body).toEqual({ error: "Server error" });
        expect(devices.deleteMany).toHaveBeenCalledWith({
            where: { installationId, userId },
        });
    });
});

// Interface: /api/notif/device input validation and ownership
describe("Mocked: /api/notif/device validation and ownership", () => {
    const invalidIds: unknown[] = [
        undefined, 5, ["cABCDEFGHIJKLMNOPQRSTU"], "xABCDEFGHIJKLMNOPQRSTU", "cshort", "cABCDEFGHIJKLMNOPQRST!",
    ];
    const authed = `Bearer ${token}`;

    test.each(["put", "delete"] as const)("%s without authentication returns 401", async (method) => {
        await request(app)[method]("/api/notif/device").send({ installationId }).expect(401);
        expect(devices.upsert).not.toHaveBeenCalled();
        expect(devices.deleteMany).not.toHaveBeenCalled();
    });

    test.each(invalidIds)("PUT rejects invalid installation id %j", async (id) => {
        const res = await request(app).put("/api/notif/device").set("Authorization", authed)
            .send({ installationId: id }).expect(400);
        expect(res.body).toEqual({ error: "Invalid installation ID" });
        expect(devices.upsert).not.toHaveBeenCalled();
    });

    test.each(invalidIds)("DELETE rejects invalid installation id %j", async (id) => {
        await request(app).delete("/api/notif/device").set("Authorization", authed)
            .send({ installationId: id }).expect(400);
        expect(devices.deleteMany).not.toHaveBeenCalled();
    });

    test("PUT registers the device for the caller", async () => {
        devices.upsert.mockResolvedValue({ installationId, userId } as never);
        await request(app).put("/api/notif/device").set("Authorization", authed).send({ installationId }).expect(204);
        expect(devices.upsert).toHaveBeenCalledWith({
            where: { installationId }, update: {}, create: { installationId, userId },
        });
    });

    test("PUT refuses an installation that belongs to another user", async () => {
        devices.upsert.mockResolvedValue({ installationId, userId: "someone-else" } as never);
        const res = await request(app).put("/api/notif/device").set("Authorization", authed)
            .send({ installationId }).expect(409);
        expect(res.body).toEqual({ error: "Installation belongs to another user" });
    });

    test("DELETE unregisters only the caller's device", async () => {
        devices.deleteMany.mockResolvedValue({ count: 1 } as never);
        await request(app).delete("/api/notif/device").set("Authorization", authed).send({ installationId }).expect(204);
        expect(devices.deleteMany).toHaveBeenCalledWith({ where: { installationId, userId } });
    });
});
