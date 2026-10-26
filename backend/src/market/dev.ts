import 'dotenv/config';
import express from 'express';
import { createMarketRouter } from './routes';

// Local emulator development only; deployed code uses app.ts and HTTPS.
const app = express();
app.get('/health', (_req, res) => {
  res.json({ status: 'ok', mode: 'market-development', provider: 'alpaca', feed: 'iex' });
});
app.use('/api/market', createMarketRouter());
const server = app.listen(3001, '127.0.0.1', () => {
  console.log('Market development API: http://127.0.0.1:3001/api/market/stocks');
});
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => { server.close(); });
}
