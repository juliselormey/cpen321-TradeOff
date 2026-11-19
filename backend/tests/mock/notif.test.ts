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
    process.env.JWT_SECRET = secret;
    users.mockResolvedValue({
        id: userId,
        isBanned: false,
    } as never);
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