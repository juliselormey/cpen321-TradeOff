import {
    getApps,
    applicationDefault,
    initializeApp,
} from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { notifyUser } from "../../src/firebase/notifier";
import { db } from "../../src/prisma/db";

jest.mock("../../src/prisma/db", () => ({
    db: {
        notificationDevice: {
            findMany: jest.fn(),
            deleteMany: jest.fn(),
        },
    },
}));

jest.mock("firebase-admin/app", () => ({
    getApps: jest.fn(),
    applicationDefault: jest.fn(),
    initializeApp: jest.fn(),
}));

jest.mock("firebase-admin/messaging", () => {
    const send = jest.fn();
    return {
        getMessaging: () => ({ send }),
    };
});

const devices = jest.mocked(db.notificationDevice);
const send = jest.mocked(getMessaging().send);
const userId = "notification-test-user";
const title = "TradeOff";
const body = "mocked test notif";
const timestamp = new Date("2026-01-01T00:00:00Z");

const firstDevice = {
    installationId: "cABCDEFGHIJKLMNOPQRSTU",
    userId,
    createdAt: timestamp,
    updatedAt: timestamp,
};

const secondDevice = {
    ...firstDevice,
    installationId: "dABCDEFGHIJKLMNOPQRSTU",
};

beforeEach(() => {
    jest.resetAllMocks();

    jest.mocked(getApps).mockReturnValue([
        { name: "[DEFAULT]", options: {} },
    ]);

    send.mockResolvedValue("message-id");
    devices.deleteMany.mockResolvedValue({ count: 1 });
});

// Internal interface: notifyUser
// Expected HTTP status: Not applicable
// Expected output: Promise<void>
describe("Mocked: notifyUser", () => {
    // Input: User with one registered device
    // Mocked behavior: Firebase is not initialized; delivery succeeds
    // Expected behavior: Firebase initializes and sends one message
    test("initializes Firebase and sends to one device", async () => {
        devices.findMany.mockResolvedValue([firstDevice]);
        jest.mocked(getApps).mockReturnValue([]);

        const credential = { getAccessToken: jest.fn() };
        jest.mocked(applicationDefault).mockReturnValue(credential);

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(initializeApp).toHaveBeenCalledWith({ credential });

        expect(devices.findMany).toHaveBeenCalledWith({
            where: { userId },
        });

        expect(send).toHaveBeenCalledTimes(1);
        expect(send).toHaveBeenCalledWith({
            fid: firstDevice.installationId,
            data: { title, body, userId },
            android: { priority: "high" },
        });
    });

    // Input: User with two registered devices
    // Mocked behavior: Firebase is initialized; both deliveries succeed
    // Expected behavior: Both devices are targeted
    test("sends notifications to multiple devices", async () => {
        devices.findMany.mockResolvedValue([
            firstDevice,
            secondDevice,
        ]);

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(initializeApp).not.toHaveBeenCalled();
        expect(send).toHaveBeenCalledTimes(2);

        expect(send).toHaveBeenCalledWith({
            fid: firstDevice.installationId,
            data: { title, body, userId },
            android: { priority: "high" },
        });

        expect(send).toHaveBeenCalledWith({
            fid: secondDevice.installationId,
            data: { title, body, userId },
            android: { priority: "high" },
        });
    });

    // Input: User without registered devices
    // Mocked behavior: Database returns an empty list
    // Expected behavior: No notification is sent
    test("handles users without registered devices", async () => {
        devices.findMany.mockResolvedValue([]);

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(send).not.toHaveBeenCalled();
    });

    // Input: User with an invalidated installation
    // Mocked behavior: Firebase rejects the installation ID
    // Expected behavior: Invalid registration is removed
    test("removes invalidated installation IDs", async () => {
        devices.findMany.mockResolvedValue([firstDevice]);

        send.mockRejectedValue(Object.assign(
            new Error("NotRegistered"),
            { code: "messaging/installation-id-not-registered" },
        ));

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(devices.deleteMany).toHaveBeenCalledWith({
            where: {
                installationId: firstDevice.installationId,
                userId,
                updatedAt: timestamp,
            },
        });
    });

    // Input: User with two registered devices
    // Mocked behavior: First delivery fails; second succeeds
    // Expected behavior: Both deliveries are attempted
    test("continues delivery after a transient failure", async () => {
        devices.findMany.mockResolvedValue([
            firstDevice,
            secondDevice,
        ]);

        send.mockRejectedValueOnce(
            new Error("Firebase unavailable"),
        );

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(send).toHaveBeenCalledTimes(2);

        expect(send).toHaveBeenCalledWith({
            fid: secondDevice.installationId,
            data: { title, body, userId },
            android: { priority: "high" },
        });

        expect(devices.deleteMany).not.toHaveBeenCalled();
    });

    // Input: User notification request
    // Mocked behavior: Database lookup fails
    // Expected behavior: Failure does not propagate
    test("handles database lookup failures", async () => {
        devices.findMany.mockRejectedValue(
            new Error("Database unavailable"),
        );

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(send).not.toHaveBeenCalled();
    });

    // Input: User with an invalidated installation
    // Mocked behavior: Firebase rejects FID; database deletion fails
    // Expected behavior: Cleanup failure does not propagate
    test("handles failed invalidated-device cleanup", async () => {
        devices.findMany.mockResolvedValue([firstDevice]);

        send.mockRejectedValue(Object.assign(
            new Error("NotRegistered"),
            { code: "messaging/installation-id-not-registered" },
        ));

        devices.deleteMany.mockRejectedValue(
            new Error("Database unavailable"),
        );

        await expect(
            notifyUser(userId, title, body),
        ).resolves.toBeUndefined();

        expect(devices.deleteMany).toHaveBeenCalledTimes(1);
    });
});