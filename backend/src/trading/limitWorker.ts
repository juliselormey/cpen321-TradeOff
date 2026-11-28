import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { fillOrder, getServerPrice } from "./settlement";
import { notifyUser } from "../firebase/notifier";

let running = false;

type PendingOrder = Awaited<ReturnType<typeof db.order.findMany>>[number];

const crosses = (order: PendingOrder, price: Prisma.Decimal) =>
    order.limitPrice != null && (order.side === "BUY" ? price.lte(order.limitPrice) : price.gte(order.limitPrice));

export async function processPendingLimitOrders() {
    if (running) return { processedCount: 0, filledCount: 0 }; // don't overlap runs
    running = true;
    try {
        const pending = await db.order.findMany({ where: { status: "PENDING", type: "LIMIT" } });

        const byTicker = new Map<string, PendingOrder[]>();
        for (const order of pending) {
            const group = byTicker.get(order.ticker) ?? [];
            group.push(order);
            byTicker.set(order.ticker, group);
        }

        let filledCount = 0;
        for (const [ticker, orders] of byTicker) {
            let price: Prisma.Decimal;
            try {
                price = await getServerPrice(ticker);
            } catch {
                continue;
            }

            for (const order of orders) {
                if (!crosses(order, price)) continue;
                try {
                    const filled = await db.$transaction((tx) => fillOrder(tx, order, price), {
                        isolationLevel: Prisma.TransactionIsolationLevel.Serializable,
                    });
                    if (filled) {
                        filledCount++;
                        void notifyUser(
                            order.userId,
                            "Limit order filled",
                            `Your ${order.side.toLowerCase()} order for ${order.ticker} has been filled.`,
                        );
                    }
                } catch (err) {
                    console.error(`Fill failed for order ${order.id}`, err); // next tick retries
                }
            }
        }
        return { processedCount: pending.length, filledCount };
    } catch (err) {
        console.error("Error processing pending limit orders:", err);
        return { processedCount: 0, filledCount: 0 };
    } finally {
        running = false;
    }
}

let workerInterval: NodeJS.Timeout | null = null;

export function startLimitOrderWorker(intervalMs = 10000): void {
    if (workerInterval) return;
    // processPendingLimitOrders handles its own errors and never rejects.
    workerInterval = setInterval(() => void processPendingLimitOrders(), intervalMs);
}

export function stopLimitOrderWorker(): void {
    if (workerInterval) {
        clearInterval(workerInterval);
        workerInterval = null;
    }
}