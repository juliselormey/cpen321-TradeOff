import { Prisma } from "../../src/generated/prisma/client";
import { db } from "../../src/prisma/db";
import { getServerPrice } from "../../src/market/settlement";
import { processPendingLimitOrders } from "../../src/market/limitWorker";
import { notifyUser } from "../../src/firebase/notifier";

jest.mock("../../src/prisma/db", () => ({
    db: {
        order: { findMany: jest.fn() },
        $transaction: jest.fn(),
    },
}));

jest.mock("../../src/market/settlement", () => ({
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