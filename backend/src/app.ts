import express, { type Express, Response, Request, NextFunction } from 'express';
import info from './routes/info'
import cors from 'cors';
import {usersRouter} from "./routes/users";
import { authRouter } from "./routes/auth";
import { friendsRouter } from "./routes/friends";
import { createMarketRouter } from './market/routes';


export function createApp(): Express {
  const app = express();
  app.set('trust proxy', 1);
  app.use(cors())
  app.use(express.json())

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use("/api", info)
  app.use('/api/market', createMarketRouter());
  app.use("/api/auth",authRouter);
  app.use("/api/auth", usersRouter);
  app.use("/api/users", usersRouter);
  app.use("/api/friends", friendsRouter);


  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });

  app.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
      console.error(err);
      if (res.headersSent) {
        next(err);
        return;
      }
      res.status(500).json({ error: "Server error" });
    });

  return app;
}


