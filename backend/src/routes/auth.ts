import { Router } from "express";
import { verifyGoogleToken, issueAccessToken, findOrCreateUser } from "../auth/service";

export const authRouter = Router();

authRouter.use((req, res, next) => {
    next();
})

authRouter.post("/google", async (req, res) => {
    const { idToken } = req.body;
    if(!idToken) return res.status(400).json({error: "idToken required"});

    let google;
    try {
        google = await verifyGoogleToken(idToken);
    } catch {
        return res.status(401).json({error: "Invalid Google Token"});
    }

    const user = await findOrCreateUser(google);
    if(user.isBanned) return res.status(403).json({error: "Account is banned"});

    res.json({ accessToken: issueAccessToken(String(user.id))});
})

