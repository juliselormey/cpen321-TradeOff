import { Router } from 'express';
import { asyncHandler } from '../util/asyncHandler';
import { LIVE_SYMBOLS, REFRESH_INTERVAL_MS, STOCK_CATALOG } from './catalog';
import { MarketService, marketService } from './service';
import { MarketProviderError } from './types';

const UNKNOWN_SYMBOL = { error: 'Unsupported market symbol.', code: 'UNKNOWN_SYMBOL' };

export function createMarketRouter(service: MarketService = marketService): Router {
  const router = Router();
  router.use((_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });

  router.get('/stocks', asyncHandler(async (_req, res) => {
    try {
      const stocks = await service.getStocks();
      if (stocks.some(stock => stock.status === 'unavailable' || stock.error)) {
        res.status(503).json({
          error: 'Market data is unavailable.',
          code: stocks.find(s => s.error)?.error?.code ?? 'PROVIDER_UNAVAILABLE',
          stocks,
        });
        return;
      }
      res.json({ liveSymbols: LIVE_SYMBOLS, refreshIntervalMs: REFRESH_INTERVAL_MS, stocks });
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : 'Market data is unavailable.';
      const code = error instanceof MarketProviderError ? error.code : 'PROVIDER_UNAVAILABLE';
      res.status(503).json({ error: message, code });
    }
  }));

  router.get('/stocks/:symbol', asyncHandler(async (req, res) => {
    const symbol = String(req.params.symbol).toUpperCase();
    if (!STOCK_CATALOG.some(stock => stock.ticker === symbol)) {
      res.status(404).json(UNKNOWN_SYMBOL);
      return;
    }
    try {
      const stock = await service.getStock(symbol);
      if (stock.status === 'unavailable' || stock.error) {
        res.status(503).json({
          error: stock.error?.message ?? `Price unavailable for ${symbol}.`,
          code: stock.error?.code ?? 'PROVIDER_UNAVAILABLE',
        });
        return;
      }
      res.json(stock);
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : `Price unavailable for ${symbol}.`;
      const code = error instanceof MarketProviderError ? error.code : 'PROVIDER_UNAVAILABLE';
      res.status(503).json({ error: message, code });
    }
  }));

  router.get('/stocks/:symbol/history', asyncHandler(async (req, res) => {
    const symbol = String(req.params.symbol).toUpperCase();
    if (!LIVE_SYMBOLS.includes(symbol)) {
      res.status(404).json(UNKNOWN_SYMBOL);
      return;
    }
    try {
      const history = await service.getHistory(symbol);
      if (history.status === 'unavailable' || history.error) {
        res.status(503).json({
          error: history.error?.message ?? `History unavailable for ${symbol}.`,
          code: history.error?.code ?? 'PROVIDER_UNAVAILABLE',
        });
        return;
      }
      res.json(history);
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : `History unavailable for ${symbol}.`;
      const code = error instanceof MarketProviderError ? error.code : 'PROVIDER_UNAVAILABLE';
      res.status(503).json({ error: message, code });
    }
  }));
  return router;
}
