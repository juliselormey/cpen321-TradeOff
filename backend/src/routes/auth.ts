import { Router } from "express";
import { verifyGoogleToken, issueAccessToken, findOrCreateUser } from "../auth/service";
import { asyncHandler } from "../util/asyncHandler";

export const authRouter = Router();

authRouter.post("/google", asyncHandler(async (req, res) => {
        const body: unknown = req.body;

        const idToken =
            typeof body === "object" && body !== null && "idToken" in body
                ? (body as { idToken: unknown }).idToken
                : undefined;

        if (typeof idToken !== "string" || idToken.length === 0) {
            res.status(400).json({ error: "idToken required" });
            return;
        }

        let google: Awaited<ReturnType<typeof verifyGoogleToken>>;
        try {
            google = await verifyGoogleToken(idToken);
        } catch {
            res.status(401).json({ error: "Invalid Google Token" });
            return;
        }

        const user = await findOrCreateUser(google);
        if (user.isBanned) {
            res.status(403).json({ error: "Account is banned" });
            return;
        }
        // eslint-disable-next-line @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return
        res.json({ accessToken: issueAccessToken(user.id) });

    }),
);