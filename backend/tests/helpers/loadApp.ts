import { createApp } from '../../src/app';
import { db } from '../../src/prisma/db';
import * as authService from '../../src/auth/service';
import * as limitWorker from '../../src/market/limitWorker';
import { marketService } from '../../src/market/service';

export function loadApp(...services: string[]) {
  const app = createApp();
  const mods = services.map(s => {
    if (s === 'db') return { default: db, db };
    if (s === 'services/googleVerifier' || s === 'auth/service') return authService;
    if (s === 'services/tradingService' || s === 'market/service') return marketService;
    if (s === 'services/marketService' || s === 'services/alpacaProvider' || s === 'market/alpaca') return marketService;
    if (s === 'services/limitOrderWorker') return limitWorker;
    return {};
  });
  return { app, mods };
}