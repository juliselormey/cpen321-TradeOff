import { Prisma } from "../generated/prisma/client";
import { marketService } from "./service";

export type Tx = Prisma.TransactionClient;
type Order = Prisma.OrderGetPayload<{}>;
const ZERO = new Prisma.Decimal(0);

export class OrderError extends Error {
    constructor(public status: number, message: string) {
        super(message);
    }
}

/** Server-side price from Alpaca. Call this BEFORE opening a transaction. */
export async function getServerPrice(ticker: string): Promise<Prisma.Decimal> {
    try {
        const stock = await marketService.getStock(ticker);
        if (!stock.currentPrice || stock.status === "unavailable") throw new Error("unavailable");
        return new Prisma.Decimal(stock.currentPrice);
    } catch {
        throw new OrderError(503, `Price unavailable for ${ticker}`);
    }
}

export async function availableShares(tx: Tx, userId: string, ticker: string) {
    const [position, pending] = await Promise.all([
        tx.position.findUnique({ where: { userId_ticker: { userId, ticker } } }),
        tx.order.aggregate({
            _sum: { quantity: true },
            where: { userId, ticker, side: "SELL", status: "PENDING" },
        }),
    ]);
    return (position?.quantity ?? ZERO).minus(pending._sum.quantity ?? ZERO);
}

/**
 * Settles a PENDING order at `price`. Returns false if the order was no longer
 * PENDING (e.g. cancelled while the worker was running). Throws on bad state,
 * which rolls the whole transaction back, including the status claim.
 */
export async function fillOrder(tx: Tx, order: Order, price: Prisma.Decimal): Promise<boolean> {
    // Atomic claim: only one caller can move PENDING -> FILLED
    const claimed = await tx.order.updateMany({
        where: { id: order.id, status: "PENDING" },
        data: { status: "FILLED", executedPrice: price },
    });
    if (claimed.count === 0) return false;

    const { userId, ticker, side, quantity: qty } = order;
    const posKey = { userId_ticker: { userId, ticker } };
    const cost = qty.mul(price);
    const position = await tx.position.findUnique({ where: posKey });

    if (side === "BUY") {
        // Cash was held at placement: limit price for limit orders, price for market
        const hold = qty.mul(order.type === "LIMIT" ? order.limitPrice! : price);
        const refund = hold.minus(cost);
        if (!refund.isZero()) {
            await tx.account.update({ where: { userId }, data: { cashBalance: { increment: refund } } });
        }

        const owned = position?.quantity ?? ZERO;
        const avg = position?.averageCost ?? ZERO;
        const newQty = owned.plus(qty);
        const newAvg = owned.mul(avg).plus(cost).div(newQty);
        await tx.position.upsert({
            where: posKey,
            create: { userId, ticker, quantity: newQty, averageCost: newAvg },
            update: { quantity: newQty, averageCost: newAvg },
        });
    } else {
        if (!position || position.quantity.lt(qty)) throw new OrderError(409, "Position missing for fill");

        const remaining = position.quantity.minus(qty);
        if (remaining.isZero()) await tx.position.delete({ where: posKey });
        else await tx.position.update({ where: posKey, data: { quantity: remaining } });

        await tx.account.update({ where: { userId }, data: { cashBalance: { increment: cost } } });
    }

    await tx.trade.create({
        data: { userId, ticker, type: side, quantity: qty, pricePerShare: price, totalAmount: cost },
    });
    return true;
}