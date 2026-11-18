import { Router } from "express";
import { LIVE_SYMBOLS } from "../market/catalog";
import { requireAuth } from "../middleware/auth";
import { db } from "../prisma/db";
import { asyncHandler } from "../util/asyncHandler";

export const favoritesRouter = Router();

favoritesRouter.use(requireAuth);

favoritesRouter.get("/", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) {
        res.sendStatus(401);
        return;
    }
    const favorites = await db.stockFavorite.findMany({
        where: { userId },
        select: { ticker: true },
        orderBy: { ticker: "asc" },
    });
    res.set("Cache-Control", "no-store").json({ tickers: favorites.map(({ ticker }) => ticker) });
}));

favoritesRouter.put("/:ticker", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) {
        res.sendStatus(401);
        return;
    }
    const ticker = req.params.ticker?.toString().toUpperCase() ?? "";
    if (!LIVE_SYMBOLS.includes(ticker)) {
        res.status(404).json({ error: "Unsupported market symbol" });
        return;
    }
    await db.stockFavorite.createMany({ data: [{ userId, ticker }], skipDuplicates: true });
    res.sendStatus(204);
}));

favoritesRouter.delete("/:ticker", asyncHandler(async (req, res) => {
    const userId = req.userId;
    if (!userId) {
        res.sendStatus(401);
        return;
    }
    const ticker = req.params.ticker?.toString().toUpperCase() ?? "";
    await db.stockFavorite.deleteMany({ where: { userId, ticker } });
    res.sendStatus(204);
}));
