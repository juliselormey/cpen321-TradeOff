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

server.listen(env.port, () => {
    console.log(`Server listening on port ${env.port}`);
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    server.close(() => {
      process.exit(0);
    });
  });
}