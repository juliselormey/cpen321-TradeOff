import express, { type Express, Response, Request, NextFunction } from 'express';
import cors from 'cors';
import { usersRouter } from "./routes/users";
import { authRouter } from "./routes/auth";
import { friendsRouter } from "./routes/friends";
import { favoritesRouter } from "./routes/favorites";
import { createMarketRouter } from './market/routes';
import { tradesRouter } from "./routes/trades";
import { portfolioRouter } from "./routes/portfolio";
import { createE2ERouter } from './routes/e2e';

export function createApp(): Express {
  const app = express();
  app.set('trust proxy', 1);
  app.use(cors());
  app.use(express.json());

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use('/api/market', createMarketRouter());
  app.use("/api/auth", authRouter);
  app.use("/api/auth", usersRouter);
  app.use("/api/auth/me/favorites", favoritesRouter);
  app.use("/api/friends", friendsRouter);
  app.use("/api/trades", tradesRouter);
  app.use("/api/portfolio", portfolioRouter);
  app.use('/__e2e', createE2ERouter());

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });

  app.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
      if (res.headersSent) {
        next(err);
        return;
      }
      // Client errors raised by body-parser (malformed JSON, payload too large) are 4xx, not server faults.
      const status = typeof err === 'object' && err !== null && 'status' in err ? Number(err.status) : 0;
      if (status >= 400 && status < 500) {
        res.status(status).json({ error: status === 413 ? 'Request body too large' : 'Invalid request body' });
        return;
      }
      console.error(err);
      res.status(500).json({ error: "Server error" });
    });

  return app;
}

export default createApp;
