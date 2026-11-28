import { Prisma } from "../generated/prisma/client";
import { LIVE_SYMBOLS } from "../market/catalog";
import { OrderError } from "./settlement";

const MAX_QUANTITY = 1_000_000;
const MAX_PRICE = 1_000_000;

type Side = "BUY" | "SELL";
export type OrderType = "MARKET" | "LIMIT";
export interface OrderInput {
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

export function parseOrder(body: unknown, type: OrderType): OrderInput {
    const raw = typeof body === "object" && body !== null && !Array.isArray(body) ? body as Record<string, unknown> : {};
    const { ticker, quantity, limitPrice } = raw;
    const rawSide = raw.side ?? raw.type;
    const sideRaw = typeof rawSide === "string" ? rawSide.toUpperCase() : undefined;

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
