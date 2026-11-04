/**
 * Deterministic HTTP fixture for the Android Market E2E suite.
 * Uses the production market router and cache, with fixed provider data.
 * It does not import the app, Prisma, credentials, or the real Alpaca client.
 */
import express from 'express';
import { createMarketRouter } from '../src/market/routes';
import { MarketService } from '../src/market/service';
import { LIVE_SYMBOLS } from '../src/market/catalog';
import { PricePoint, ProviderQuote } from '../src/market/types';

const companies: Record<string, { price: number; change: number }> = {
  AAPL: { price: 200, change: 2.5 },
  MSFT: { price: 410, change: 1.2 },
  TSLA: { price: 250, change: -3.1 },
  NVDA: { price: 140, change: 4.2 },
  GOOGL: { price: 175, change: 0.8 },
  AMZN: { price: 190, change: 1.7 },
};

const history: PricePoint[] = [
  { timestamp: '2026-10-20T14:30:00.000Z', price: 98 },
  { timestamp: '2026-10-21T14:30:00.000Z', price: 99 },
  { timestamp: '2026-10-22T14:30:00.000Z', price: 100 },
];

const provider = {
  async fetchQuote(symbol: string): Promise<ProviderQuote> {
    const stock = companies[symbol];
    if (!stock) throw new Error(`Unknown fixture symbol: ${symbol}`);
    return {
      price: stock.price,
      change: stock.change,
      percentageChange: stock.change / (stock.price - stock.change) * 100,
      quoteAsOf: new Date().toISOString(),
    };
  },
  async fetchHistory(_symbol: string): Promise<PricePoint[]> {
    return history;
  },
  async fetchQuotes(symbols: readonly string[]): Promise<Record<string, ProviderQuote>> {
    return Object.fromEntries(await Promise.all(symbols.map(async symbol => [symbol, await this.fetchQuote(symbol)])));
  },
  async fetchHistories(symbols: readonly string[]): Promise<Record<string, PricePoint[]>> {
    return Object.fromEntries(symbols.map(symbol => [symbol, history]));
  },
};

const app = express();
app.get('/__e2e/health', (_req, res) => res.json({ ready: true, suite: 'market' }));
app.use('/api/market', createMarketRouter(new MarketService(provider)));

const port = Number(process.env.MARKET_E2E_PORT ?? 3003);
const server = app.listen(port, '0.0.0.0', () => {
  console.log(`Deterministic Market E2E fixture listening on ${port} (${LIVE_SYMBOLS.length} symbols).`);
});
process.on('SIGINT', () => server.close(() => process.exit(0)));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
