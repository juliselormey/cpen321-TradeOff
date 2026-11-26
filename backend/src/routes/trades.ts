import { Router, Response } from "express";
import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { authenticatedHandler, AuthenticatedRequest } from "../middleware/auth";
import { LIVE_SYMBOLS } from "../market/catalog";
import { OrderError, availableShares, fillOrder, getServerPrice, runSerializable, Tx } from "../market/settlement";
import { STARTING_BALANCE } from "../market/valuation";
import { asyncHandler } from "../util/asyncHandler";
import { hasErrorCode } from "../util/dbErrors";

export const tradesRouter = Router();

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ZERO = new Prisma.Decimal(0);
const MAX_QUANTITY = 1_000_000;
const MAX_PRICE = 1_000_000;

/** GET /api/trades/position/:ticker: cash balance and position info for a ticker */
tradesRouter.get("/position/:ticker", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const ticker = String(req.params.ticker).toUpperCase();

    try {
        const [account, position, availableQuantity] = await Promise.all([
            db.account.findUnique({ where: { userId } }),
            db.position.findUnique({ where: { userId_ticker: { userId, ticker } } }),
            availableShares(db, userId, ticker),
        ]);

        res.json({
            cashBalance: account ? Number(account.cashBalance) : STARTING_BALANCE.toNumber(),
            ownedQuantity: position ? Number(position.quantity) : 0,
            averageCost: position ? Number(position.averageCost) : 0,
            availableQuantity: Math.max(0, Number(availableQuantity)),
        });
    } catch (error) {
        console.error(error);
        res.status(500).json({ error: "Failed to fetch position" });
    }
}));

/** GET /api/trades/orders/pending: active pending limit orders for the user */
tradesRouter.get("/orders/pending", asyncHandler(async (req, res) => {
    const pendingOrders = await db.order.findMany({
        where: { userId: req.userId!, status: "PENDING" },
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
            reservedCash: o.side === "BUY" && o.limitPrice ? Number(o.quantity.mul(o.limitPrice)) : 0,
            createdAt: o.createdAt.toISOString(),
        })),
    });
}));

/** POST /api/trades/orders/:id/cancel: cancels a pending limit order and releases reserved funds/shares */
tradesRouter.post("/orders/:id/cancel", asyncHandler(async (req, res) => {
    const userId = req.userId!;
    const orderId = String(req.params.id);
    if (!uuid.test(orderId)) {
        res.status(400).json({ error: "Invalid order id" });
        return;
    }

    try {
        const releasedCash = await db.$transaction(async (tx) => {
            // Conditional claim: only one of cancel / worker fill can win a PENDING order.
            const { count } = await tx.order.updateMany({
                where: { id: orderId, userId, status: "PENDING" },
                data: { status: "CANCELLED" },
            });
            if (count === 0) {
                const exists = await tx.order.findFirst({ where: { id: orderId, userId } });
                throw exists
                    ? new OrderError(409, "Only pending orders can be cancelled")
                    : new OrderError(404, "Order not found");
            }

            const order = await tx.order.findUniqueOrThrow({ where: { id: orderId } });
            if (order.side === "BUY" && order.limitPrice) {
                const release = order.quantity.mul(order.limitPrice);
                await tx.account.update({ where: { userId }, data: { cashBalance: { increment: release } } });
                return release.toNumber();
            }
            return 0;
        });
        res.json({ success: true, message: "Order cancelled successfully", orderId, status: "CANCELLED", releasedCash });
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

/** Positive, finite, within the cap, and representable by the Decimal(18,4) columns (fractional shares allowed). */
function validAmount(value: unknown, max: number): value is number {
    return typeof value === "number" && Number.isFinite(value) && value > 0 && value <= max
        && new Prisma.Decimal(value).decimalPlaces() <= 4;
}

function parseOrder(body: unknown, type: OrderType): OrderInput {
    const raw = (body ?? {}) as Record<string, unknown>;
    const { ticker, quantity, limitPrice } = raw;
    const sideRaw = (raw.side ?? raw.type)?.toString().toUpperCase();

    if (!ticker || typeof ticker !== "string") throw new OrderError(400, "Ticker symbol is required");
    const symbol = ticker.toUpperCase();
    if (!LIVE_SYMBOLS.includes(symbol)) throw new OrderError(400, "Unsupported ticker");
    if (sideRaw !== "BUY" && sideRaw !== "SELL") throw new OrderError(400, "Side must be BUY or SELL");
    if (!validAmount(quantity, MAX_QUANTITY)) {
        throw new OrderError(400, "Quantity must be a positive number with at most 4 decimal places");
    }

    if (type === "MARKET" && limitPrice != null) {
        throw new OrderError(400, "Limit price is forbidden for market orders");
    }
    if (type === "LIMIT" && !validAmount(limitPrice, MAX_PRICE)) {
        throw new OrderError(400, "Limit price is required for limit orders and must be positive with at most 4 decimal places");
    }

    // Market orders never reach here with a limit price, so this is null for them.
    return { ticker: symbol, side: sideRaw, type, quantity, limitPrice: typeof limitPrice === "number" ? limitPrice : null };
}

async function placeOrderTx(tx: Tx, userId: string, o: OrderInput, price: Prisma.Decimal) {
    const qty = new Prisma.Decimal(o.quantity);
    const limit = o.limitPrice != null ? new Prisma.Decimal(o.limitPrice) : null;
    // A limit order always has a limit price (validated in parseOrder).
    const fills = o.type === "MARKET" || (o.side === "BUY" ? price.lte(limit!) : price.gte(limit!));

    const account = await tx.account.upsert({
        where: { userId },
        create: { userId, cashBalance: STARTING_BALANCE, startingBalance: STARTING_BALANCE },
        update: {},
    });

    if (o.side === "BUY") {
        const hold = qty.mul(limit ?? price);
        if (account.cashBalance.lt(hold)) throw new OrderError(400, "Insufficient cash balance");
        await tx.account.update({ where: { userId }, data: { cashBalance: { decrement: hold } } });
    } else if ((await availableShares(tx, userId, o.ticker)).lt(qty)) {
        throw new OrderError(400, "Insufficient shares owned");
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

const placeOrder = (type: OrderType) => asyncHandler(async (req: Request, res: Response) => {
    const userId = req.userId!;
    try {
        const input = parseOrder(req.body, type);
        const price = await getServerPrice(input.ticker);
        const result = await runSerializable((tx) => placeOrderTx(tx, userId, input, price));
        res.json({ success: true, ...result });
    } catch (error) {
        if (error instanceof OrderError) {
            res.status(error.status).json({ error: error.message });
            return;
        }
        if (hasErrorCode(error, "P2034")) {
            res.status(503).json({ error: "The server is busy. Please try again." });
            return;
        }
        console.error(error);
        res.status(500).json({ error: `Failed to execute ${type.toLowerCase()} order` });
    }
});

tradesRouter.post("/execute/market", placeOrder("MARKET"));
tradesRouter.post("/execute/limit", placeOrder("LIMIT"));