import { Prisma } from "../generated/prisma/client";
import { marketService } from "./service";

type Decimal = Prisma.Decimal;
const ZERO = new Prisma.Decimal(0);
export const STARTING_BALANCE = new Prisma.Decimal(10000);

export class PriceUnavailableError extends Error {
    constructor(public readonly ticker: string) {
        super(`Price unavailable for ${ticker}`);
    }
}

export interface ValuationInput {
    account: { cashBalance: Decimal; startingBalance: Decimal } | null;
    positions: { ticker: string; quantity: Decimal; averageCost?: Decimal }[];
    /** Pending BUY limit orders. Their cash was deducted at placement and still belongs to the user. */
    pendingBuyOrders: { quantity: Decimal; limitPrice: Decimal | null }[];
}

export interface HoldingValue {
    ticker: string;
    quantity: Decimal;
    averageCost: Decimal | null;
    price: Decimal;
    value: Decimal;
    profitLoss: Decimal | null;
}

export interface Valuation {
    cash: Decimal;
    reservedCash: Decimal;
    holdingsValue: Decimal;
    /** cash + reserved cash + holdings at current prices */
    totalValue: Decimal;
    /** totalValue - startingBalance */
    profitLoss: Decimal;
    returnPercent: Decimal | null;
    holdings: HoldingValue[];
    allocation: { label: string; percent: number }[];
}

/** One request per distinct ticker, served from the shared market cache. Only fresh, valid prices are usable; stale/unavailable prices map to null. */
export async function loadPrices(tickers: Iterable<string>): Promise<Map<string, Decimal | null>> {
    const symbols = Array.from(new Set(tickers));
    return new Map(await Promise.all(symbols.map(async (ticker) => {
        try {
            const stock = await marketService.getStock(ticker);
            const price = stock.status !== "ok" || stock.currentPrice == null ? null : new Prisma.Decimal(stock.currentPrice);
            return [ticker, price?.isFinite() && price.greaterThan(0) ? price : null] as const;
        } catch {
            return [ticker, null] as const;
        }
    })));
}

/** Throws PriceUnavailableError when a held ticker has no price. */
export function valuePortfolio(input: ValuationInput, prices: Map<string, Decimal | null>): Valuation {
    const cash = input.account?.cashBalance ?? STARTING_BALANCE;
    const starting = input.account?.startingBalance ?? STARTING_BALANCE;

    let reservedCash = ZERO;
    for (const order of input.pendingBuyOrders) {
        if (order.limitPrice) reservedCash = reservedCash.plus(order.quantity.times(order.limitPrice));
    }

    let holdingsValue = ZERO;
    const holdings: HoldingValue[] = [];
    for (const position of input.positions) {
        if (!position.quantity.greaterThan(0)) continue;
        const price = prices.get(position.ticker);
        if (!price) throw new PriceUnavailableError(position.ticker);
        const value = position.quantity.times(price);
        holdingsValue = holdingsValue.plus(value);
        holdings.push({
            ticker: position.ticker, quantity: position.quantity, price, value,
            averageCost: position.averageCost ?? null,
            profitLoss: position.averageCost ? price.minus(position.averageCost).times(position.quantity) : null,
        });
    }

    const totalValue = cash.plus(reservedCash).plus(holdingsValue);
    const profitLoss = totalValue.minus(starting);
    const share = (amount: Decimal) => (totalValue.greaterThan(0) ? amount.dividedBy(totalValue).times(100).toNumber() : 0);
    return {
        cash, reservedCash, holdingsValue, totalValue, profitLoss, holdings,
        returnPercent: starting.greaterThan(0) ? profitLoss.dividedBy(starting).times(100) : null,
        allocation: [{ label: "Cash", percent: share(cash.plus(reservedCash)) },
            ...holdings.map(h => ({ label: h.ticker, percent: share(h.value) }))],
    };
}
