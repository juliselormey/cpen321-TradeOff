import express, { type Express } from 'express';
import info from './routes/info'
import cors from 'cors';
import {usersRouter} from "./routes/users";

export function createApp(): Express {
  const app = express();
  app.set('trust proxy', 1);
  app.use(cors())
  app.use(express.json())

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use("/api", info)
  app.use(usersRouter);


  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });

  return app;
}
