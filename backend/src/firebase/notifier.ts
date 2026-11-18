import { getFirebaseMessaging } from "./firebase";
import { db } from "../prisma/db";

export async function sendNotification(
    installationId: string,
    title: string,
    body: string
): Promise<string> {
    return getFirebaseMessaging().send({
        fid: installationId,
        notification: {
            title,
            body
        }
    });
}

export async function notifyUser(
    userId: string,
    title: string,
    body: string
): Promise<void> {
    try {
        const devices = await db.notificationDevice.findMany({
            where: { userId },
        });

        await Promise.all(devices.map(async (device) => {
            try {
                await sendNotification(
                    device.installationId,
                    title,
                    body,
                );
            } catch (error) {
                if (
                    error instanceof Error &&
                    "code" in error &&
                    error.code === "messaging/installation-id-not-registered"
                ) {
                    await db.notificationDevice.deleteMany({
                        where: {
                            installationId: device.installationId,
                            userId,
                            updatedAt: device.updatedAt,
                        },
                    });
                } else {
                    console.error("Notification delivery failed", error);
                }
            }
        }));
    } catch (error) {
        console.error("User notification failed", error);
    }
}
