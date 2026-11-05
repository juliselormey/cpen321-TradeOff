/** Portfolio E2E fixture host. It is never imported by the production entry point. */
import 'dotenv/config';
import express from 'express';
import { createApp } from '../src/app';
import { requireAuth } from '../src/middleware/auth';
import { db } from '../src/prisma/db';
import { marketService } from '../src/market/service';
import { asyncHandler } from '../src/util/asyncHandler';

const database = new URL(process.env.DATABASE_URL ?? '').pathname.slice(1);
if (!/^tradeoff_e2e_[a-z0-9_]+$/.test(database) || !process.env.E2E_USER_EMAIL) {
  throw new Error('Use a disposable tradeoff_e2e_<name> database and set E2E_USER_EMAIL.');
}
const app = express();
app.use(express.json());
let fault = '';
let faultUntil = 0;
let fixedPrice = 0;
const activeFault = () => (Date.now() < faultUntil ? fault : '');
const originalStock = marketService.getStock.bind(marketService);
marketService.getStock = async ticker => {
  const stock = await originalStock(ticker);
  if (activeFault() === 'price-rise' && ticker === 'MSFT' && stock.currentPrice) {
    return { ...stock, currentPrice: String(fixedPrice) };
  }
  return stock;
};

app.use('/__e2e', requireAuth, asyncHandler(async (req, res, next) => {
  const userId = req.userId;
  if (!userId) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }
  const user = await db.user.findUnique({ where: { id: userId } });
  if (user?.email !== process.env.E2E_USER_EMAIL) {
    res.status(403).json({ error: 'Only the selected E2E account can control fixtures.' });
    return;
  }
  next();
}));

app.post('/__e2e/fault', asyncHandler(async (req, res) => {
  const raw = typeof req.body === 'object' && req.body !== null ? (req.body as Record<string, unknown>) : {};
  const mode = raw.mode;
  if (typeof mode !== 'string' || !['', 'portfolio', 'market', 'price-rise'].includes(mode)) {
    res.status(400).json({ error: 'Unknown Portfolio E2E fault.' });
    return;
  }
  if (mode === 'price-rise') fixedPrice = Number((await originalStock('MSFT')).currentPrice) + 25;
  fault = mode;
  faultUntil = Date.now() + 90_000;
  res.json({ mode });
}));

app.post('/__e2e/reset', asyncHandler(async (req, res) => {
  fault = '';
  const userId = req.userId;
  if (!userId) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }
  await db.$transaction(async tx => {
    await tx.order.deleteMany({ where: { userId } });
    await tx.trade.deleteMany({ where: { userId } });
    await tx.position.deleteMany({ where: { userId } });
    await tx.friendship.deleteMany({ where: { OR: [{ senderId: userId }, { recipientId: userId }] } });
    await tx.account.upsert({ where: { userId }, create: { userId, cashBalance: 10000, startingBalance: 10000 },
      update: { cashBalance: 10000, startingBalance: 10000 } });
  });
  res.json({ ready: true });
}));

app.use((req, res, next) => {
  const mode = activeFault();
  const prefixes = new Map<string, string>([
    ['portfolio', '/api/portfolio'],
    ['market', '/api/market'],
  ]);
  const prefix = prefixes.get(mode);
  if (prefix && req.path.startsWith(prefix)) {
    res.status(503).json({ error: 'E2E injected service outage' });
    return;
  }
  next();
});

app.use(createApp());

const server = app.listen(3002, '127.0.0.1', () => {
  console.log('Portfolio E2E backend listening on port 3002.');
});

process.on('SIGINT', () => {
  server.close(() => {
    db.$disconnect().then(() => {
      process.exitCode = 0;
    }).catch(() => {
      process.exitCode = 1;
    });
  });
});
