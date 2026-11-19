import { createApp } from './app';
import { env } from './config/env';
import fs from 'fs';
import path from 'path';
import https from 'https';
import http from 'http';
import { db } from './prisma/db';
import { startLimitOrderWorker, stopLimitOrderWorker } from './market/limitWorker';

const app = createApp();

function getCertBuffer(filePath: string): Buffer {
  const resolved = path.resolve(filePath);
  // eslint-disable-next-line security/detect-non-literal-fs-filename
  return fs.readFileSync(resolved);
}

// Local Android emulator development can opt into loopback HTTP. Cloud stays HTTPS.
const localHttp = process.env.LOCAL_HTTP === 'true';
const server = localHttp
  ? http.createServer(app)
  : https.createServer({
      key: getCertBuffer(env.sslKeyPath),
      cert: getCertBuffer(env.sslCertPath),
    }, app);

server.listen(env.port, localHttp ? '127.0.0.1' : '0.0.0.0', () => {
    console.log("Server listening");
    startLimitOrderWorker();
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    stopLimitOrderWorker();
    server.close(() => {
      db.$disconnect().catch((error: unknown) => {
        console.error("Database shutdown failed", error);
        process.exitCode = 1;
      });
    });
  });
}
