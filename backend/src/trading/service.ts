import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { STARTING_BALANCE } from "../config/trading";
import { availableShares, fillOrder, getServerPrice, OrderError, runSerializable, Tx } from "./settlement";
import { OrderInput, OrderType, parseOrder } from "./orderInput";

const ZERO = new Prisma.Decimal(0);

export async function getPosition(userId: string, ticker: string) {
    return db.$transaction(async tx => {
        const [account, position, availableQuantity] = await Promise.all([
            tx.account.findUnique({ where: { userId } }),
            tx.position.findUnique({ where: { userId_ticker: { userId, ticker } } }),
            availableShares(tx, userId, ticker),
        ]);
        return {
            cashBalance: account ? Number(account.cashBalance) : STARTING_BALANCE.toNumber(),
            ownedQuantity: position ? Number(position.quantity) : 0,
            averageCost: position ? Number(position.averageCost) : 0,
            availableQuantity: Math.max(0, Number(availableQuantity)),
        };
    }, { isolationLevel: Prisma.TransactionIsolationLevel.RepeatableRead });
}

export async function getPendingOrders(userId: string) {
    const orders = await db.order.findMany({ where: { userId, status: "PENDING" }, orderBy: { createdAt: "desc" } });
    return { orders: orders.map(order => ({
        id: order.id, ticker: order.ticker, side: order.side, type: order.type, status: order.status,
        quantity: Number(order.quantity), limitPrice: order.limitPrice ? Number(order.limitPrice) : null,
        reservedCash: order.side === "BUY" && order.limitPrice ? Number(order.quantity.mul(order.limitPrice)) : 0,
        createdAt: order.createdAt.toISOString(),
    })) };
}

export async function cancelOrder(userId: string, orderId: string) {
    return db.$transaction(async (tx) => {
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
}

export async function placeOrder(userId: string, type: OrderType, body: unknown) {
    const input = parseOrder(body, type);
    const price = await getServerPrice(input.ticker);
    return runSerializable(tx => placeOrderTx(tx, userId, input, price));
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
