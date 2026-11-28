import { Prisma } from "../generated/prisma/client";
import { Router } from "express";
import { db } from "../prisma/db";
import { authenticatedHandler } from "../middleware/auth";
import { PriceUnavailableError, loadPrices, valuePortfolio } from "../market/valuation";

export const portfolioRouter = Router();

/**
 * GET /api/portfolio
 * Cash, reserved cash, holdings valued at current prices, total value, profit/loss, allocation,
 * pending orders and recent trades. Returns 503 when a held stock's price is unavailable (UC-13 3a).
 */
portfolioRouter.get("/", authenticatedHandler(async (req, res) => {
    const userId = req.userId!;

    try {
        const [account, positions, recentTrades, pendingOrders] = await db.$transaction(async tx => Promise.all([
            tx.account.findUnique({ where: { userId } }),
            tx.position.findMany({ where: { userId } }),
            tx.trade.findMany({ where: { userId }, orderBy: { createdAt: "desc" }, take: 100 }),
            tx.order.findMany({ where: { userId, status: "PENDING" }, orderBy: { createdAt: "desc" } }),
        ]), { isolationLevel: Prisma.TransactionIsolationLevel.RepeatableRead });

        const prices = await loadPrices(positions.filter(p => p.quantity.greaterThan(0)).map(p => p.ticker));
        const valuation = valuePortfolio({
            account, positions,
            pendingBuyOrders: pendingOrders.filter(o => o.side === "BUY" && o.type === "LIMIT"),
        }, prices);

        res.json({
            cashBalance: valuation.cash.toNumber(),
            startingBalance: (account?.startingBalance ?? valuation.cash).toNumber(),
            reservedCash: valuation.reservedCash.toNumber(),
            totalValue: valuation.totalValue.toNumber(),
            profitLoss: valuation.profitLoss.toNumber(),
            returnPercent: valuation.returnPercent?.toNumber() ?? null,
            holdings: valuation.holdings.map(h => ({
                ticker: h.ticker,
                quantity: h.quantity.toNumber(),
                averageCost: h.averageCost?.toNumber() ?? 0,
                price: h.price.toNumber(),
                value: h.value.toNumber(),
                profitLoss: h.profitLoss?.toNumber() ?? null,
                returnPercent: h.averageCost?.greaterThan(0) && h.profitLoss !== null
                    ? h.profitLoss.div(h.averageCost.mul(h.quantity)).mul(100).toNumber() : null,
            })),
            allocation: valuation.allocation.map(slice => ({
                ...slice,
                value: slice.label === "Cash" ? valuation.cash.plus(valuation.reservedCash).toNumber()
                    : valuation.holdings.find(holding => holding.ticker === slice.label)?.value.toNumber() ?? null,
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
        if (error instanceof PriceUnavailableError) {
            res.status(503).json({ error: "Prices are unavailable, so the portfolio cannot be valued right now" });
            return;
        }
        console.error(error);
        res.status(500).json({ error: "Failed to fetch portfolio summary" });
    }
}));