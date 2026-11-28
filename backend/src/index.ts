import { attachMarketEvents } from './realtime/marketEvents';
import { createApp } from './app';
import { attachFriendEvents } from './realtime/friendEvents';
import { env } from './config/env';
import fs from 'fs';
import path from 'path';
import https from 'https';
import http from 'http';
import { db } from './prisma/db';
import { startLimitOrderWorker, stopLimitOrderWorker } from './trading/limitWorker';

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

const closeFriendEvents = attachFriendEvents(server);
const closeMarketEvents = attachMarketEvents(server);

server.listen(env.port, '0.0.0.0', () => {
    console.log(`Server listening on 0.0.0.0:${env.port}`);
    startLimitOrderWorker();
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    stopLimitOrderWorker();
    closeFriendEvents();
    closeMarketEvents();
    server.close(() => {
      db.$disconnect().catch((error: unknown) => {
        console.error("Database shutdown failed", error);
        process.exitCode = 1;
      });
    });
  });
}
