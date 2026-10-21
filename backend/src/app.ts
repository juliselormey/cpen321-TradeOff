import express, { type Express, NextFunction, Request, Response } from 'express';
import info from './routes/info'
import cors from 'cors';
import {usersRouter} from "./routes/users";
import { authRouter } from "./routes/auth";

export function createApp(): Express {
  const app = express();
  app.set('trust proxy', 1);
  app.use(cors())
  app.use(express.json())

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use("/api", info)
  app.use("/api/auth",authRouter);
  app.use("/api/auth", usersRouter);


  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });

  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
      console.error(err);
      res.status(500).json({ error: "Server error" });
});

  return app;
}


