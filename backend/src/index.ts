import { createApp } from './app';
import { env } from './config/env';
import fs from 'fs';
import https from 'https';

const app = createApp();

const sslOptions = {
    key: fs.readFileSync(env.sslKeyPath),
    cert: fs.readFileSync(env.sslCertPath),
};

const server = https.createServer(sslOptions, app);

const safePort = String(env.port).replace(/[\r\n]/g, "");
server.listen(env.port, () => {
    console.log(`Server listening on port ${safePort}`);
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    server.close(() => {
      process.exit(0);
    });
  });
}