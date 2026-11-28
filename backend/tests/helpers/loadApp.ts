import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import * as authService from '../../src/auth/service';
import * as limitWorker from '../../src/trading/limitWorker';
import { marketService } from '../../src/market/service';

export function loadApp(...services: string[]) {
  const app = createApp();
  const mods = services.map(s => {
    if (s === 'db') return { default: db, db };
    if (s === 'services/googleVerifier' || s === 'auth/service') return authService;
    if (s === 'services/tradingService' || s === 'market/service') return marketService;
    if (s === 'services/marketService' || s === 'services/alpacaProvider' || s === 'market/alpaca') return marketService;
    if (s === 'services/limitOrderWorker' || s === 'market/limitWorker') return limitWorker;
    if (s === 'services/limitOrderWorker' || s === 'trading/limitWorker') return limitWorker;
    return {};
  });
  return { app, mods };
}
