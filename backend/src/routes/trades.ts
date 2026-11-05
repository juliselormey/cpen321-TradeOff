import { Router, Request, Response } from "express";
import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { requireAuth } from "../middleware/auth";
import { OrderError, availableShares, fillOrder, getServerPrice, Tx } from "../market/settlement";
import { asyncHandler } from "../util/asyncHandler";

export const tradesRouter = Router();
tradesRouter.use(requireAuth);

/**
 * GET /api/trades/position/:ticker
 * Returns cash balance and position info for a ticker
 */
tradesRouter.get("/position/:ticker", asyncHandler(async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    const tickerParam = req.params.ticker;
    if (typeof tickerParam !== "string") {
        res.status(400).json({ error: "Invalid ticker" });
        return;
    }

    const ticker = tickerParam.toUpperCase();

    try {
        const [account, position, availableQuantity] = await Promise.all([
            db.account.findUnique({ where: { userId } }),
            db.position.findUnique({ where: { userId_ticker: { userId, ticker } } }),
            availableShares(db, userId, ticker),
        ]);

        res.json({
            cashBalance: account ? Number(account.cashBalance) : 10000.0,
            ownedQuantity: position ? Number(position.quantity) : 0,
            averageCost: position ? Number(position.averageCost) : 0,
            availableQuantity: Math.max(0, Number(availableQuantity)),
        });
    } catch (error) {
        console.error(error);
        res.status(500).json({ error: "Failed to fetch position" });
    }
}));

/**
 * GET /api/trades/orders/pending
 * Returns active pending limit orders for the user
 */
tradesRouter.get("/orders/pending", asyncHandler(async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    const pendingOrders = await db.order.findMany({
        where: {
            userId,
            status: "PENDING",
        },
        orderBy: { createdAt: "desc" },
    });

    res.json({
        orders: pendingOrders.map((o) => ({
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
}));

/**
 * POST /api/trades/orders/:id/cancel
 * Cancels a pending limit order and releases reserved funds/shares
 */
tradesRouter.post("/orders/:id/cancel", asyncHandler(async (req: Request, res: Response) => {
    const userId = req.userId;
    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }

    const orderId = req.params.id;
    if (typeof orderId !== "string") {
        res.status(400).json({ error: "Invalid order id" });
        return;
    }

    try {
        await db.$transaction(async (tx) => {
            const { count } = await tx.order.updateMany({
                where: { id: orderId, userId, status: "PENDING" },
                data: { status: "CANCELLED" },
            });
            if (count === 0) {
                const exists = await tx.order.findFirst({ where: { id: orderId, userId } });
                throw exists
                    ? new OrderError(400, "Only pending orders can be cancelled")
                    : new OrderError(404, "Order not found");
            }

            const order = await tx.order.findUniqueOrThrow({ where: { id: orderId } });
            if (order.side === "BUY" && order.limitPrice) {
                await tx.account.update({
                    where: { userId },
                    data: { cashBalance: { increment: order.quantity.mul(order.limitPrice) } },
                });
            }
        });
        res.json({ success: true, message: "Order cancelled successfully", orderId });
    } catch (error) {
        if (error instanceof OrderError) {
            res.status(error.status).json({ error: error.message });
            return;
        }
        console.error(error);
        res.status(500).json({ error: "Failed to cancel order" });
    }
}));

type Side = "BUY" | "SELL";
type OrderType = "MARKET" | "LIMIT";
interface OrderInput {
    ticker: string;
    side: Side;
    type: OrderType;
    quantity: number;
    limitPrice: number | null;
}
const STARTING_BALANCE = new Prisma.Decimal(10000);
const ZERO = new Prisma.Decimal(0);

function parseOrder(body: unknown, type: OrderType): OrderInput {
    const raw = typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
    const { ticker, quantity, limitPrice } = raw;
    const sideRaw = (raw.side ?? raw.type)?.toString().toUpperCase();

    if (!ticker || typeof ticker !== "string") throw new OrderError(400, "Ticker symbol is required");
    if (sideRaw !== "BUY" && sideRaw !== "SELL") throw new OrderError(400, "Side must be BUY or SELL");
    if (typeof quantity !== "number" || quantity <= 0) throw new OrderError(400, "Quantity must be a positive number");

    if (type === "MARKET" && limitPrice != null) {
        throw new OrderError(400, "Limit price is forbidden for market orders");
    }
    if (type === "LIMIT" && (typeof limitPrice !== "number" || limitPrice <= 0)) {
        throw new OrderError(400, "Limit price is required for limit orders and must be positive");
    }

    return {
        ticker: ticker.toUpperCase(),
        side: sideRaw,
        type,
        quantity,
        limitPrice: type === "LIMIT" ? limitPrice : null,
    };
}

async function placeOrderTx(tx: Tx, userId: string, o: OrderInput, price: Prisma.Decimal) {
    const qty = new Prisma.Decimal(o.quantity);
    const limit = o.limitPrice != null ? new Prisma.Decimal(o.limitPrice) : null;
    const fills = o.type === "MARKET" || (limit != null && (o.side === "BUY" ? price.lte(limit) : price.gte(limit)));

    const account = await tx.account.upsert({
        where: { userId },
        create: { userId, cashBalance: STARTING_BALANCE, startingBalance: STARTING_BALANCE },
        update: {},
    });

    if (o.side === "BUY") {
        const hold = qty.mul(limit ?? price);
        if (account.cashBalance.lt(hold)) throw new OrderError(400, "Insufficient cash balance");
        await tx.account.update({ where: { userId }, data: { cashBalance: { decrement: hold } } });
    } else {
        if ((await availableShares(tx, userId, o.ticker)).lt(qty)) {
            throw new OrderError(400, "Insufficient shares owned");
        }
    }

    const order = await tx.order.create({
        data: {
            userId, ticker: o.ticker, side: o.side, type: o.type,
            status: "PENDING", quantity: qty, limitPrice: limit,
        },
    });
    if (fills) await fillOrder(tx, order, price);

    const [acct, pos, available] = await Promise.all([
        tx.account.findUniqueOrThrow({ where: { userId } }),
        tx.position.findUnique({ where: { userId_ticker: { userId, ticker: o.ticker } } }),
        availableShares(tx, userId, o.ticker),
    ]);

    return {
        status: fills ? "FILLED" : "PENDING",
        orderId: order.id,
        executedPrice: fills ? price.toNumber() : null,
        cashBalance: acct.cashBalance.toNumber(),
        ownedQuantity: (pos?.quantity ?? ZERO).toNumber(),
        availableQuantity: available.toNumber(),
        message: fills
            ? `${o.type} ${o.side} filled @ $${price}`
            : `${o.type} ${o.side} placed @ $${limit} (${o.side === "BUY" ? "funds" : "shares"} reserved)`,
    };
}

async function handlePlaceOrder(type: OrderType, req: Request, res: Response): Promise<void> {
    const userId = req.userId;
    if (!userId) {
        res.status(401).json({ error: "Unauthorized" });
        return;
    }
    try {
        const input = parseOrder(req.body, type);
        const price = await getServerPrice(input.ticker);
        const result = await db.$transaction((tx) => placeOrderTx(tx, userId, input, price), {
            isolationLevel: Prisma.TransactionIsolationLevel.Serializable,
        });
        res.json({ success: true, ...result });
    } catch (error) {
        if (error instanceof OrderError) {
            res.status(error.status).json({ error: error.message });
            return;
        }
        console.error(error);
        res.status(500).json({ error: `Failed to execute ${type.toLowerCase()} order` });
    }
}

const placeOrder = (type: OrderType) =>
    asyncHandler((req: Request, res: Response) => handlePlaceOrder(type, req, res));

tradesRouter.post("/execute/market", placeOrder("MARKET"));
tradesRouter.post("/execute/limit", placeOrder("LIMIT"));
tradesRouter.post("/execute", asyncHandler(async (req: Request, res: Response) => {
    const raw = typeof req.body === "object" && req.body !== null ? (req.body as Record<string, unknown>) : {};
    const type: OrderType = raw.type?.toString().toUpperCase() === "LIMIT" ? "LIMIT" : "MARKET";
    await handlePlaceOrder(type, req, res);
}));