import { Router } from "express";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";

export const notifRouter = Router();

notifRouter.use(requireAuth);

function isValidInstallationId(value: unknown): value is string {
    return typeof value === "string" && /^[cdef][A-Za-z0-9_-]{21}$/.test(value);
}

// Both /device routes (PUT, DELETE) need a valid installation ID.
notifRouter.use("/device", (req, res, next) => {
    if (!isValidInstallationId(req.body?.installationId)) {
        res.status(400).json({ error: "Invalid installation ID" });
        return;
    }
    next();
});

notifRouter.put("/device", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const installationId = req.body.installationId as string;

    const device = await db.notificationDevice.upsert({
        where: { installationId },
        update: {},
        create: { installationId, userId },
    });

    if (device.userId !== userId) {
        res.status(409).json({ error: "Installation belongs to another user" });
        return;
    }
    res.status(204).end();
}));

notifRouter.delete("/device", asyncHandler(async (req, res) => {
    await db.notificationDevice.deleteMany({
        where: { installationId: req.body.installationId as string, userId: req.userId! },
    });
    res.status(204).end();
}));