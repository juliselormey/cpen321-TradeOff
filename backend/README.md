# TradeOff backend

The backend is an Express/TypeScript API using Prisma with MySQL/MariaDB,
Google authentication and Alpaca market data. The ordinary entry point is
`src/index.ts`; it also starts the limit-order worker, background market refresh
and market/friend WebSockets.

For complete local acceptance-test preparation, follow
[Local E2E Setup](../documentation/Local_E2E_Setup.md).
For regular application setup, follow the [root README](../README.md).

After configuring `backend/.env` and preparing the database:

```powershell
npm.cmd ci
npx.cmd prisma generate
npx.cmd prisma migrate deploy
npm run dev
```

Run these from `backend`. MySQL/MariaDB must already be running. Migrations create
tables in an existing database; they do not install/start the database server.
The server defaults to HTTPS with configured certificate files. For local emulator
testing only, `LOCAL_HTTP=true` enables loopback HTTP without certificates.

E2E setup is opt-in: `E2E_ENABLED=true` requires a disposable database named
`tradeoff_e2e_<name>`. The authenticated reset endpoint deletes only the caller's
trading data and sets virtual cash to $10,000. Never enable it on real user data.
No Docker, special fixture host or fixed testing Google account is required.

From the repository root, `.\scripts\run-market-e2e-tests.ps1` runs the four
Market use cases individually, with Browse Stocks last.
`.\scripts\run-trading-e2e-tests.ps1` runs only the four Trading use cases.
`.\scripts\run-portfolio-e2e-tests.ps1` runs only the two Portfolio use cases.
`.\scripts\run-frontend-e2e-tests.ps1` runs all twelve implemented use cases.
These use the existing server and do not start another one.

Common commands: `npm run typecheck`, `npm run build`, `npm run lint`,
and `npm test`. See [Testing](../documentation/Testing.md) for each suite's setup.
Use Node.js 22.x and npm 10.x as specified in package.json.
