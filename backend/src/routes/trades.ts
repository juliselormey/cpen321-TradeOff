import { Router, Request, Response } from "express";
import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { requireAuth } from "../middleware/auth";

export const tradesRouter = Router();

tradesRouter.use(requireAuth);

/**
 * GET /api/trades/position/:ticker
 */
tradesRouter.get("/position/:ticker", async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        return res.status(401).json({ error: "Unauthorized" });
    }

    const tickerParam = req.params.ticker;

    if (typeof tickerParam !== "string") {
      return res.status(400).json({ error: "Invalid or missing ticker" });
    }

    const ticker = tickerParam.toUpperCase();

    const account = await db.account.findUnique({
        where: { userId },
    });

    const cashBalance = account ? Number(account.cashBalance) : 10000.0;

    const position = await db.position.findUnique({
        where: {
            userId_ticker: { userId, ticker },
        },
    });

    return res.json({
        cashBalance,
        ownedQuantity: position ? Number(position.quantity) : 0,
        averageCost: position ? Number(position.averageCost) : 0,
    });
});

/**
 * POST /api/trades/execute
 */
tradesRouter.post("/execute", async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        return res.status(401).json({ error: "Unauthorized" });
    }

    const { ticker, type, quantity, pricePerShare } = req.body as {
        ticker?: string;
        type?: string;
        quantity?: number;
        pricePerShare?: number;
    };

    if (!ticker || typeof ticker !== "string") {
        return res.status(400).json({ error: "Ticker symbol is required" });
    }
    if (type !== "BUY" && type !== "SELL") {
        return res.status(400).json({ error: "Type must be BUY or SELL" });
    }
    if (typeof quantity !== "number" || quantity <= 0) {
        return res.status(400).json({ error: "Quantity must be a positive number" });
    }
    if (typeof pricePerShare !== "number" || pricePerShare <= 0) {
        return res.status(400).json({ error: "Price per share must be a positive number" });
    }

    const upperTicker = ticker.toUpperCase();
    const totalAmount = quantity * pricePerShare;

    try {
        const result = await db.$transaction(async (tx) => {
            let account = await tx.account.findUnique({
                where: { userId },
            });

            if (!account) {
                account = await tx.account.create({
                    data: {
                        userId,
                        cashBalance: new Prisma.Decimal(10000.0),
                        startingBalance: new Prisma.Decimal(10000.0),
                    },
                });
            }

            const currentCash = Number(account.cashBalance);

            const existingPosition = await tx.position.findUnique({
                where: {
                    userId_ticker: { userId, ticker: upperTicker },
                },
            });

            const currentQuantity = existingPosition ? Number(existingPosition.quantity) : 0;
            const currentAvgCost = existingPosition ? Number(existingPosition.averageCost) : 0;

            if (type === "BUY") {
                if (currentCash < totalAmount) {
                    throw new Error("INSUFFICIENT_FUNDS");
                }

                const newCash = currentCash - totalAmount;
                await tx.account.update({
                    where: { userId },
                    data: { cashBalance: new Prisma.Decimal(newCash) },
                });

                const newQuantity = currentQuantity + quantity;
                const newTotalCost = currentQuantity * currentAvgCost + totalAmount;
                const newAvgCost = newQuantity > 0 ? newTotalCost / newQuantity : 0;

                const updatedPosition = await tx.position.upsert({
                    where: {
                        userId_ticker: { userId, ticker: upperTicker },
                    },
                    create: {
                        userId,
                        ticker: upperTicker,
                        quantity: new Prisma.Decimal(newQuantity),
                        averageCost: new Prisma.Decimal(newAvgCost),
                    },
                    update: {
                        quantity: new Prisma.Decimal(newQuantity),
                        averageCost: new Prisma.Decimal(newAvgCost),
                    },
                });

                await tx.trade.create({
                    data: {
                        userId,
                        ticker: upperTicker,
                        type: "BUY",
                        quantity: new Prisma.Decimal(quantity),
                        pricePerShare: new Prisma.Decimal(pricePerShare),
                        totalAmount: new Prisma.Decimal(totalAmount),
                    },
                });

                return {
                    cashBalance: newCash,
                    ownedQuantity: Number(updatedPosition.quantity),
                };
            } else {
                if (currentQuantity < quantity) {
                    throw new Error("INSUFFICIENT_SHARES");
                }

                const newCash = currentCash + totalAmount;
                await tx.account.update({
                    where: { userId },
                    data: { cashBalance: new Prisma.Decimal(newCash) },
                });

                const newQuantity = currentQuantity - quantity;
                let updatedOwnedQuantity = 0;

                if (newQuantity <= 0) {
                    await tx.position.delete({
                        where: {
                            userId_ticker: { userId, ticker: upperTicker },
                        },
                    });
                } else {
                    const updatedPosition = await tx.position.update({
                        where: {
                            userId_ticker: { userId, ticker: upperTicker },
                        },
                        data: {
                            quantity: new Prisma.Decimal(newQuantity),
                        },
                    });
                    updatedOwnedQuantity = Number(updatedPosition.quantity);
                }

                await tx.trade.create({
                    data: {
                        userId,
                        ticker: upperTicker,
                        type: "SELL",
                        quantity: new Prisma.Decimal(quantity),
                        pricePerShare: new Prisma.Decimal(pricePerShare),
                        totalAmount: new Prisma.Decimal(totalAmount),
                    },
                });

                return {
                    cashBalance: newCash,
                    ownedQuantity: updatedOwnedQuantity,
                };
            }
        });

        return res.json({
            success: true,
            message: `Trade executed successfully (${type} ${quantity} ${upperTicker})`,
            cashBalance: result.cashBalance,
            ownedQuantity: result.ownedQuantity,
        });
    } catch (error: unknown) {
        if (error instanceof Error) {
            if (error.message === "INSUFFICIENT_FUNDS") {
                return res.status(400).json({ error: "Insufficient cash balance" });
            }
            if (error.message === "INSUFFICIENT_SHARES") {
                return res.status(400).json({ error: "Insufficient shares owned" });
            }
        }
        return res.status(500).json({ error: "Failed to execute trade" });
    }
});