import { Router } from 'express';
import { asyncHandler } from '../util/asyncHandler';
import { LIVE_SYMBOLS, REFRESH_INTERVAL_MS, STOCK_CATALOG } from './catalog';
import { MarketService, marketService } from './service';

export function createMarketRouter(service: MarketService = marketService): Router {
  const router = Router();
  router.use((_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });

  router.get('/stocks', asyncHandler(async (_req, res) => {
    res.json({
      liveSymbols: LIVE_SYMBOLS,
      refreshIntervalMs: REFRESH_INTERVAL_MS,
      stocks: await service.getStocks(),
    });
  }));

  router.get('/stocks/:symbol', asyncHandler(async (req, res) => {
    const symbol = String(req.params.symbol).toUpperCase();
    if (!STOCK_CATALOG.some(stock => stock.ticker === symbol)) {
      res.status(404).json({ error: { code: 'UNKNOWN_SYMBOL', message: 'Unsupported market symbol.' } });
      return;
    }
    const stock = await service.getStock(symbol);
    res.status(stock.status === 'unavailable' ? 503 : 200).json(stock);
  }));

  router.get('/stocks/:symbol/history', asyncHandler(async (req, res) => {
    const symbol = String(req.params.symbol).toUpperCase();
    if (!LIVE_SYMBOLS.includes(symbol)) {
      res.status(404).json({ error: { code: 'UNKNOWN_SYMBOL', message: 'Unsupported market symbol.' } });
      return;
    }
    const history = await service.getHistory(symbol);
    res.status(history.status === 'unavailable' && history.error ? 503 : 200).json(history);
  }));
  return router;
}
