import { Router } from "express";
import { authenticatedHandler } from "../middleware/auth";
import { OrderError } from "../trading/settlement";
import { OrderType } from "../trading/orderInput";
import * as trading from "../trading/service";
import { hasErrorCode } from "../util/dbErrors";

export const tradesRouter = Router();
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function tradingAction(action: (userId: string, req: import('express').Request) => Promise<unknown>, fallback: string) {
    return authenticatedHandler(async (req, res) => {
        try {
            res.json(await action(req.userId, req));
        } catch (error) {
            if (error instanceof OrderError) { res.status(error.status).json({ error: error.message }); return; }
            if (hasErrorCode(error, "P2034")) {
                res.status(503).json({ error: "The server is busy. Please try again." }); return;
            }
            console.error(error);
            res.status(500).json({ error: fallback });
        }
    });
}

tradesRouter.get("/position/:ticker", tradingAction((userId, req) =>
    trading.getPosition(userId, String(req.params.ticker).toUpperCase()), "Failed to fetch position"));
tradesRouter.get("/orders/pending", tradingAction(userId => trading.getPendingOrders(userId), "Failed to fetch pending orders"));
tradesRouter.post("/orders/:id/cancel", tradingAction(async (userId, req) => {
    const orderId = String(req.params.id);
    if (!uuid.test(orderId)) throw new OrderError(400, "Invalid order id");
    const releasedCash = await trading.cancelOrder(userId, orderId);
    return { success: true, message: "Order cancelled successfully", orderId, status: "CANCELLED", releasedCash };
}, "Failed to cancel order"));

const execute = (type: OrderType) => tradingAction(async (userId, req) =>
    ({ success: true, ...await trading.placeOrder(userId, type, req.body) }), `Failed to execute ${type.toLowerCase()} order`);
tradesRouter.post("/execute/market", execute("MARKET"));
tradesRouter.post("/execute/limit", execute("LIMIT"));
