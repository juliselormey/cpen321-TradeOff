import { createApp } from './app';
import { env } from './config/env';
import fs from 'fs';
import path from 'path';
import https from 'https';
import { db } from './prisma/db';

const app = createApp();

const keyPath = path.resolve(env.sslKeyPath);
const certPath = path.resolve(env.sslCertPath);

/* eslint-disable security/detect-non-literal-fs-filename */
const sslOptions = {
    key: fs.readFileSync(keyPath),
    cert: fs.readFileSync(certPath),
};
/* eslint-enable security/detect-non-literal-fs-filename */

const server = https.createServer(sslOptions, app);

server.listen(env.port, () => {
    console.log("Server listening");
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    server.close(() => {
      db.$disconnect().catch((error: unknown) => {
        console.error("Database shutdown failed", error);
        process.exitCode = 1;
      });
    });
  });
}
