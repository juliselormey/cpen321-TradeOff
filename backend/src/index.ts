import { createApp } from './app';
import { env } from './config/env';
import fs from 'fs';
import https from 'https';
import { db } from './prisma/db';

const app = createApp();

const sslOptions = {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- Trusted startup TLS configuration, not request input.
    key: fs.readFileSync(env.sslKeyPath),
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- Trusted startup TLS configuration, not request input.
    cert: fs.readFileSync(env.sslCertPath),
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