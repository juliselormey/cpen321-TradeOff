import { Prisma } from "../../src/generated/prisma/client";
import { db } from "../../src/prisma/db";
import { getServerPrice } from "../../src/trading/settlement";
import { processPendingLimitOrders, startLimitOrderWorker, stopLimitOrderWorker } from "../../src/trading/limitWorker";
import { notifyUser } from "../../src/firebase/notifier";

jest.mock("../../src/prisma/db", () => ({
    db: {
        order: { findMany: jest.fn() },
        $transaction: jest.fn(),
    },
}));

jest.mock("../../src/trading/settlement", () => ({
    getServerPrice: jest.fn(),
    fillOrder: jest.fn(),
}));

jest.mock("../../src/firebase/notifier", () => ({
    notifyUser: jest.fn(),
}));

const orders = jest.mocked(db.order.findMany);
const transaction = jest.mocked(db.$transaction);
const price = jest.mocked(getServerPrice);
const notifier = jest.mocked(notifyUser);

const timestamp = new Date("2026-01-01T00:00:00Z");

const order = {
    id: "notification-test-order",
    userId: "notification-test-user",
    ticker: "AAPL",
    type: "LIMIT" as const,
    side: "BUY" as const,
    status: "PENDING" as const,
    limitPrice: new Prisma.Decimal(100),
    quantity: new Prisma.Decimal(1),
    executedPrice: null,
    createdAt: timestamp,
    updatedAt: timestamp,
};

beforeEach(() => {
    jest.resetAllMocks();
    orders.mockResolvedValue([order]);
    price.mockResolvedValue(new Prisma.Decimal(90));
    notifier.mockResolvedValue(undefined);
});

// Internal interface: processPendingLimitOrders
// Expected HTTP status: Not applicable
describe("Mocked: limit order notification trigger", () => {
    // Input: Pending buy order whose price crosses its limit
    // Mocked behavior: Settlement transaction returns true
    // Expected behavior: Order owner is notified once
    // Expected output: One processed order, one filled order
    test("notifies the owner after a successful fill", async () => {
        transaction.mockResolvedValue(true);

        await expect(processPendingLimitOrders()).resolves.toEqual({
            processedCount: 1,
            filledCount: 1,
        });

        expect(notifier).toHaveBeenCalledTimes(1);

        expect(notifier).toHaveBeenCalledWith(
            order.userId,
            "Limit order filled",
            "Your buy order for AAPL has been filled.",
        );
    });

    // Input: Pending buy order that does not cross its limit
    // Mocked behavior: Market price exceeds limit price
    // Expected behavior: No settlement or notification
    // Expected output: One processed order, zero filled orders
    test("does not notify for a non-crossing order", async () => {
        price.mockResolvedValue(new Prisma.Decimal(110));

        await expect(processPendingLimitOrders()).resolves.toEqual({
            processedCount: 1,
            filledCount: 0,
        });

        expect(transaction).not.toHaveBeenCalled();
        expect(notifier).not.toHaveBeenCalled();
    });

    // Input: Pending order whose price crosses its limit
    // Mocked behavior: Settlement returns false
    // Expected behavior: No notification
    // Expected output: One processed order, zero filled orders
    test("does not notify when settlement returns false", async () => {
        transaction.mockResolvedValue(false);

        await expect(processPendingLimitOrders()).resolves.toEqual({
            processedCount: 1,
            filledCount: 0,
        });

        expect(notifier).not.toHaveBeenCalled();
    });

    // Input: Pending order whose price crosses its limit
    // Mocked behavior: Settlement transaction rejects
    // Expected behavior: No notification on failed settlement
    // Expected output: One processed order, zero filled orders
    test("does not notify when settlement fails", async () => {
        transaction.mockRejectedValue(
            new Error("Settlement failed"),
        );

        await expect(processPendingLimitOrders()).resolves.toEqual({
            processedCount: 1,
            filledCount: 0,
        });

        expect(notifier).not.toHaveBeenCalled();
    });
});

describe("Mocked: limit order worker edge cases", () => {
    const IDLE = { processedCount: 0, filledCount: 0 };
    const make = (o: object = {}) => ({ ...order, ...o }) as never;
    let errors: jest.SpyInstance;
    beforeEach(() => { errors = jest.spyOn(console, "error").mockImplementation(() => undefined); });
    afterEach(() => { errors.mockRestore(); });

    test("does not start a second run while one is in flight", async () => {
        let release: (value: unknown) => void = () => undefined;
        orders.mockReturnValueOnce(new Promise(resolve => { release = resolve; }) as never);
        const first = processPendingLimitOrders();
        await expect(processPendingLimitOrders()).resolves.toEqual(IDLE);
        release([]);
        await expect(first).resolves.toEqual(IDLE);
        expect(orders).toHaveBeenCalledTimes(1);
        orders.mockResolvedValue([]);
        await expect(processPendingLimitOrders()).resolves.toEqual(IDLE); // lock was released
        expect(orders).toHaveBeenCalledTimes(2);
    });

    test("a sell limit fills when the price is at or above the limit", async () => {
        orders.mockResolvedValue([make({ side: "SELL" })]);
        price.mockResolvedValue(new Prisma.Decimal(110));
        transaction.mockResolvedValue(true);
        await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 1, filledCount: 1 });
        expect(notifier).toHaveBeenCalledWith(order.userId, "Limit order filled", "Your sell order for AAPL has been filled.");
    });

    test("a sell limit above the market is left alone", async () => {
        orders.mockResolvedValue([make({ side: "SELL" })]);
        price.mockResolvedValue(new Prisma.Decimal(90));
        await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 1, filledCount: 0 });
        expect(transaction).not.toHaveBeenCalled();
    });

    test("an order without a limit price is skipped", async () => {
        orders.mockResolvedValue([make({ limitPrice: null })]);
        await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 1, filledCount: 0 });
        expect(transaction).not.toHaveBeenCalled();
    });

    test("prices each ticker once and keeps going when one ticker has no price", async () => {
        orders.mockResolvedValue([
            make({ id: "a1" }), make({ id: "a2" }), make({ id: "m1", ticker: "MSFT" }),
        ]);
        price.mockImplementation(async ticker => {
            if (ticker === "AAPL") throw new Error("Price unavailable");
            return new Prisma.Decimal(90);
        });
        transaction.mockResolvedValue(true);
        await expect(processPendingLimitOrders()).resolves.toEqual({ processedCount: 3, filledCount: 1 });
        expect(price).toHaveBeenCalledTimes(2);
        expect(transaction).toHaveBeenCalledTimes(1);
    });

    test("a failure fetching orders returns an empty result and releases the lock", async () => {
        orders.mockRejectedValueOnce(new Error("db down"));
        await expect(processPendingLimitOrders()).resolves.toEqual(IDLE);
        expect(errors).toHaveBeenCalled();
        orders.mockResolvedValue([]);
        await processPendingLimitOrders();
        expect(orders).toHaveBeenCalledTimes(2);
    });
});

describe("Mocked: limit order worker lifecycle", () => {
    afterEach(() => {
        stopLimitOrderWorker();
        jest.useRealTimers();
    });

    test("polls on the interval, ignores a second start, and stops cleanly", async () => {
        jest.useFakeTimers();
        orders.mockResolvedValue([]);
        startLimitOrderWorker(1000);
        startLimitOrderWorker(1000);
        expect(orders).not.toHaveBeenCalled();
        await jest.advanceTimersByTimeAsync(1000);
        expect(orders).toHaveBeenCalledTimes(1);
        await jest.advanceTimersByTimeAsync(1000);
        expect(orders).toHaveBeenCalledTimes(2);
        stopLimitOrderWorker();
        stopLimitOrderWorker();
        jest.advanceTimersByTime(5000);
        expect(orders).toHaveBeenCalledTimes(2);
    });

    test("can be restarted after stopping, using the default interval", () => {
        jest.useFakeTimers();
        orders.mockResolvedValue([]);
        startLimitOrderWorker();
        jest.advanceTimersByTime(9999);
        expect(orders).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(orders).toHaveBeenCalledTimes(1);
    });
});