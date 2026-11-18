import request from "supertest";
import jwt from "jsonwebtoken";
import { randomUUID } from "node:crypto";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";
import { requireEnv } from "../../src/config/env";

const app = createApp();
const JWT_SECRET = requireEnv("JWT_SECRET");

describe("No-Mock Integration Tests: Notifications", () => {
    let testUserId: string;
    let authToken: string;

    beforeAll(async () => {
        const uniqueId = randomUUID();

        const testUser = await db.user.create({
            data: {
                googleSub: `test-notif-${uniqueId}`,
                email: `test-notif-${uniqueId}@example.com`,
                displayName: "Notification Test User",
            },
        });

        testUserId = testUser.id;

        authToken = jwt.sign(
            { sub: testUserId },
            JWT_SECRET,
            { expiresIn: "1h" },
        );
    });

    beforeEach(async () => {
        await db.notificationDevice.deleteMany({
            where: { userId: testUserId },
        });
    });

    afterAll(async () => {
        if (testUserId) {
            await db.user.delete({
                where: { id: testUserId },
            });
        }

        await db.$disconnect();
    });

    /* -------------------------------------------------------------------------- */
    /* 1. Notification Device Registration                                        */
    /* -------------------------------------------------------------------------- */
    describe("Notification Device Registration", () => {
        // Input: Valid installation ID and authenticated user
        // Expected status code: 204
        // Expected behavior: Installation is associated with the user
        // Expected output: No response body
        test("PUT /api/notif/device with valid installation ID returns 204 No Content", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            const res = await request(app)
                .put("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({
                    installationId,
                });

            expect(res.status).toBe(204);
            expect(res.text).toBe("");

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device).not.toBeNull();
            expect(device?.userId).toBe(testUserId);
        });
        // Input: Same installation ID registered twice by an authenticated user
        // Expected status code: 204 for both requests
        // Expected behavior: Only one notification device record exists
        // Expected output: No response body
        test("PUT /api/notif/device with existing installation ID returns 204 No Content", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            const firstRes = await request(app)
                .put("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId });

            const secondRes = await request(app)
                .put("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId });

            expect(firstRes.status).toBe(204);
            expect(secondRes.status).toBe(204);
            expect(secondRes.text).toBe("");

            const devices = await db.notificationDevice.findMany({
                where: { userId: testUserId },
            });

            expect(devices).toHaveLength(1);
            expect(devices[0]?.installationId).toBe(installationId);
        });
        // Input: Invalid installation ID and authenticated user
        // Expected status code: 400
        // Expected behavior: No notification device is registered
        // Expected output: Invalid installation ID error
        test("PUT /api/notif/device with invalid installation ID returns 400 Bad Request", async () => {
            const installationId = "invalid-id";

            const res = await request(app)
                .put("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({
                error: "Invalid installation ID",
            });

            const devices = await db.notificationDevice.findMany({
                where: { userId: testUserId },
            });

            expect(devices).toHaveLength(0);
        });
        // Input: Valid installation ID without an authentication token
        // Expected status code: 401
        // Expected behavior: Registration is rejected
        // Expected output: Unauthorized error
        test("PUT /api/notif/device without authentication returns 401 Unauthorized", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            const res = await request(app)
                .put("/api/notif/device")
                .send({ installationId });

            expect(res.status).toBe(401);

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device).toBeNull();
        });
    });
    /* -------------------------------------------------------------------------- */
    /* 2. Notification Device Unregistration                                      */
    /* -------------------------------------------------------------------------- */
    describe("Notification Device Unregistration", () => {
        // Input: Registered installation ID and authenticated user
        // Expected status code: 204
        // Expected behavior: Notification device registration is removed
        // Expected output: No response body
        test("DELETE /api/notif/device with registered installation ID returns 204 No Content", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            await db.notificationDevice.create({
                data: {
                    installationId,
                    userId: testUserId,
                },
            });

            const res = await request(app)
                .delete("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId });

            expect(res.status).toBe(204);
            expect(res.text).toBe("");

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device).toBeNull();
        });
        // Input: Nonexistent installation ID and authenticated user
        // Expected status code: 204
        // Expected behavior: No notification device is removed
        // Expected output: No response body
        test("DELETE /api/notif/device with nonexistent installation ID returns 204 No Content", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            const res = await request(app)
                .delete("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId });

            expect(res.status).toBe(204);
            expect(res.text).toBe("");

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device).toBeNull();
        });
        // Input: Installation ID belonging to another user
        // Expected status code: 204
        // Expected behavior: Other user's notification device remains registered
        // Expected output: No response body
        test("DELETE /api/notif/device belonging to another user returns 204 No Content", async () => {
            const uniqueId = randomUUID();
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            const otherUser = await db.user.create({
                data: {
                    googleSub: `test-notif-${uniqueId}`,
                    email: `test-notif-${uniqueId}@example.com`,
                    displayName: "Other Notification Test User",
                },
            });

            try {
                await db.notificationDevice.create({
                    data: {
                        installationId,
                        userId: otherUser.id,
                    },
                });

                const res = await request(app)
                    .delete("/api/notif/device")
                    .set("Authorization", `Bearer ${authToken}`)
                    .send({ installationId });

                expect(res.status).toBe(204);
                expect(res.text).toBe("");

                const device = await db.notificationDevice.findUnique({
                    where: { installationId },
                });

                expect(device).not.toBeNull();
                expect(device?.userId).toBe(otherUser.id);
            } finally {
                await db.user.delete({
                    where: { id: otherUser.id },
                });
            }
        });
        // Input: Invalid installation ID and authenticated user
        // Expected status code: 400
        // Expected behavior: No notification device is removed
        // Expected output: Invalid installation ID error
        test("DELETE /api/notif/device with invalid installation ID returns 400 Bad Request", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            await db.notificationDevice.create({
                data: {
                    installationId,
                    userId: testUserId,
                },
            });

            const res = await request(app)
                .delete("/api/notif/device")
                .set("Authorization", `Bearer ${authToken}`)
                .send({ installationId: "invalid-id" });

            expect(res.status).toBe(400);
            expect(res.body).toEqual({
                error: "Invalid installation ID",
            });

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device?.userId).toBe(testUserId);
        });
        // Input: Registered installation ID without an authentication token
        // Expected status code: 401
        // Expected behavior: Unregistration is rejected
        // Expected output: Unauthorized error
        test("DELETE /api/notif/device without authentication returns 401 Unauthorized", async () => {
            const installationId = "cABCDEFGHIJKLMNOPQRSTU";

            await db.notificationDevice.create({
                data: {
                    installationId,
                    userId: testUserId,
                },
            });

            const res = await request(app)
                .delete("/api/notif/device")
                .send({ installationId });

            expect(res.status).toBe(401);

            const device = await db.notificationDevice.findUnique({
                where: { installationId },
            });

            expect(device?.userId).toBe(testUserId);
        });
    });
});
