import { createApp } from './app';
import { env } from './config/env';
import fs from 'fs';
import path from 'path';
import https from 'https';
import { db } from './prisma/db';

const app = createApp();

function getCertBuffer(filePath: string): Buffer {
  const resolved = path.resolve(filePath);
  // eslint-disable-next-line security/detect-non-literal-fs-filename
  return fs.readFileSync(resolved);
}

const sslOptions = {
  key: getCertBuffer(env.sslKeyPath),
  cert: getCertBuffer(env.sslCertPath),
};

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
