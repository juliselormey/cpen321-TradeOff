import { Prisma } from "../generated/prisma/client";
import { db } from "../prisma/db";
import { fillOrder, getServerPrice } from "./settlement";

let running = false;

export async function processPendingLimitOrders() {
    if (running) return { processedCount: 0, filledCount: 0 }; // don't overlap runs
    running = true;
    try {
        const pending = await db.order.findMany({ where: { status: "PENDING", type: "LIMIT" } });

        const byTicker = new Map<string, typeof pending>();
        for (const o of pending) byTicker.set(o.ticker, [...(byTicker.get(o.ticker) ?? []), o]);

        let filledCount = 0;
        for (const [ticker, orders] of byTicker) {
            let price: Prisma.Decimal;
            try {
                price = await getServerPrice(ticker);
            } catch {
                continue;
            }

            for (const order of orders) {
                if (!order.limitPrice) continue;
                const crosses = order.side === "BUY" ? price.lte(order.limitPrice) : price.gte(order.limitPrice);
                if (!crosses) continue;

                try {
                    const filled = await db.$transaction((tx) => fillOrder(tx, order, price), {
                        isolationLevel: Prisma.TransactionIsolationLevel.Serializable,
                    });
                    if (filled) filledCount++;
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
    workerInterval = setInterval(() => {
        processPendingLimitOrders().catch(() => {});
    }, intervalMs);
}

export function stopLimitOrderWorker(): void {
    if (workerInterval) {
        clearInterval(workerInterval);
        workerInterval = null;
    }
}
