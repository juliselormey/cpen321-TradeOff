import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";

export const notifRouter = Router();

notifRouter.use(requireAuth);

function isValidInstallationId(value: unknown): value is string {
    return typeof value === "string" &&
        /^[cdef][A-Za-z0-9_-]{21}$/.test(value);
}

notifRouter.put("/device", asyncHandler(async (req, res) => {
    const userId = req.userId;

    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    const installationId: unknown = req.body?.installationId;

    if (!isValidInstallationId(installationId)) {
        res.status(400).json({ error: "Invalid installation ID" });
        return;
    }

    const device = await db.notificationDevice.upsert({
        where: { installationId },
        update: {},
        create: { installationId, userId },
    });

    if (device.userId !== userId) {
        res.status(409).json({
            error: "Installation belongs to another user",
        });
        return;
    }

    res.status(204).end();
}));

notifRouter.delete("/device", asyncHandler(async (req, res) => {
    const userId = req.userId;

    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    const installationId: unknown = req.body?.installationId;

    if (!isValidInstallationId(installationId)) {
        res.status(400).json({ error: "Invalid installation ID" });
        return;
    }

    await db.notificationDevice.deleteMany({
        where: { installationId, userId }
    });

    res.status(204).end();
}));