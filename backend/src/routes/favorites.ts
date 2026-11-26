import { Router } from "express";
import { LIVE_SYMBOLS } from "../market/catalog";
import { authenticatedHandler } from "../middleware/auth";
import { db } from "../prisma/db";

export const favoritesRouter = Router();


favoritesRouter.get("/", authenticatedHandler(async (req, res) => {
    const favorites = await db.stockFavorite.findMany({
        where: { userId: req.userId! },
        select: { ticker: true },
        orderBy: { ticker: "asc" },
    });
    res.set("Cache-Control", "no-store").json({ tickers: favorites.map(({ ticker }) => ticker) });
}));


favoritesRouter.put("/:ticker", authenticatedHandler(async (req, res) => {
    const ticker = String(req.params.ticker).toUpperCase();
    if (!LIVE_SYMBOLS.includes(ticker)) {
        res.status(404).json({ error: "Unsupported market symbol" });
        return;
    }
    await db.stockFavorite.createMany({ data: [{ userId: req.userId!, ticker }], skipDuplicates: true });
    res.sendStatus(204);
}));

favoritesRouter.delete("/:ticker", authenticatedHandler(async (req, res) => {
    const ticker = String(req.params.ticker).toUpperCase();
    await db.stockFavorite.deleteMany({ where: { userId: req.userId!, ticker } });
    res.sendStatus(204);
}));