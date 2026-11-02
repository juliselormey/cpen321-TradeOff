import { Router, Request, Response } from "express";
import { db } from "../prisma/db";
import { requireAuth } from "../middleware/auth";

export const portfolioRouter = Router();
portfolioRouter.use(requireAuth);

/**
 * GET /api/portfolio
 * Returns complete user portfolio summary: cash balance, holdings, recent trades, and pending orders
 */
portfolioRouter.get("/", async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        return res.status(401).json({ error: "Unauthorized" });
    }

    try {
        const [account, positions, recentTrades, pendingOrders] = await Promise.all([
            db.account.findUnique({ where: { userId } }),
            db.position.findMany({ where: { userId } }),
            db.trade.findMany({
                where: { userId },
                orderBy: { createdAt: "desc" },
                take: 10,
            }),
            db.order.findMany({
                where: { userId, status: "PENDING" },
                orderBy: { createdAt: "desc" },
            }),
        ]);

        return res.json({
            cashBalance: account ? Number(account.cashBalance) : 10000.0,
            startingBalance: account ? Number(account.startingBalance) : 10000.0,
            holdings: positions.map((p) => ({
                ticker: p.ticker,
                quantity: Number(p.quantity),
                averageCost: Number(p.averageCost),
            })),
            recentTrades: recentTrades.map((t) => ({
                id: t.id,
                ticker: t.ticker,
                type: t.type,
                quantity: Number(t.quantity),
                pricePerShare: Number(t.pricePerShare),
                totalAmount: Number(t.totalAmount),
                createdAt: t.createdAt.toISOString(),
            })),
            pendingOrders: pendingOrders.map((o) => ({
                id: o.id,
                ticker: o.ticker,
                side: o.side,
                type: o.type,
                status: o.status,
                quantity: Number(o.quantity),
                limitPrice: o.limitPrice ? Number(o.limitPrice) : null,
                createdAt: o.createdAt.toISOString(),
            })),
        });
    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: "Failed to fetch portfolio summary" });
    }
});
