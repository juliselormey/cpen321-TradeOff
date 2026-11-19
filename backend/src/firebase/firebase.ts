import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";

export function getFirebaseMessaging() {
    if (getApps().length === 0) {
        initializeApp({
            credential: applicationDefault()
        });
    }

    return getMessaging();
}
