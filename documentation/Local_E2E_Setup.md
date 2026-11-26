# Local test setup (no Docker)

This is the setup for the five real-backend acceptance tests: Browse Stocks
(`BrowseStocksE2ETest`), Place Market Buy
(`TradingE2ETest`), View Portfolio (`PortfolioE2ETest`), View Leaderboard
(`LeaderboardE2ETest`) and Send Friend Request (`SendFriendRequestE2ETest`). They use the normal
`npm run dev` backend, real Google login, real MySQL/MariaDB and real Alpaca data.
After one-time setup, each session requires starting the database, `npm run dev`,
and a test runner. Nothing here requires the old special E2E servers.

## 1. Install prerequisites once

- Node.js 22.x and npm 10.x (the backend's supported versions).
- MySQL Server 8.4, or an existing compatible MariaDB server, **running** locally.
  Install the server, not only MySQL Workbench. Record the administrator password
  and TCP port. Use port 3306 below unless your installation uses another port.
- Android Studio, Android SDK/platform-tools and a Pixel 9 emulator with API 36
  and Google Play. Let Gradle use Android Studio's bundled JDK.
- Git and the cloned repository. Run all root-relative commands from that clone.

The database is a separate running program. Downloading the Git repository does
not install/start MySQL and does not copy another developer's local database.
On Windows, start your installed MySQL/MariaDB service in `services.msc`.
If using a manually launched MariaDB, start it with its existing data-directory
configuration; keep it running during testing. Do not initialize over existing data.

## 2. Create an isolated database once

Open the MySQL command-line client or a MySQL Workbench SQL connection as an
administrator. For a CLI installed on PATH:

```powershell
mysql --host=127.0.0.1 --port=3306 --user=root --password
```

Use `mariadb` instead of `mysql` if that is your installed client. If neither is
on PATH, use the full path to the client in the installation's `bin` directory.

Run the following SQL **inside that client**, replacing `CHOOSE_LOCAL_PASSWORD`
with a new local password (not the administrator password). Use a password with
letters/digits for the simplest URL setup. Never commit the real password.

```sql
CREATE DATABASE tradeoff_e2e_local CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'tradeoff_e2e'@'127.0.0.1' IDENTIFIED BY 'CHOOSE_LOCAL_PASSWORD';
CREATE USER 'tradeoff_e2e'@'localhost' IDENTIFIED BY 'CHOOSE_LOCAL_PASSWORD';
GRANT ALL PRIVILEGES ON tradeoff_e2e_local.* TO 'tradeoff_e2e'@'127.0.0.1';
GRANT ALL PRIVILEGES ON tradeoff_e2e_local.* TO 'tradeoff_e2e'@'localhost';
```

If the database/user already exists, reuse the matching credentials rather than
dropping it. An existing disposable database is also supported if its name matches
`tradeoff_e2e_<name>`. On Altai's existing machine the prepared database was
`tradeoff_e2e_market_buy` on port `33418`; other testers must use **their own**
database address/password. Do not copy another person's credentials.

## 3. Obtain the real external-service credentials once

- Alpaca: create a Paper Trading account, generate an API key/secret pair and set
  `ALPACA_API_KEY_ID` and `ALPACA_API_SECRET_KEY`. Both must belong to the same pair.
  Alpaca is an online API; no local Alpaca installation is needed.
- Google: obtain a Web OAuth client ID (`GOOGLE_CLIENT_ID`). Register an Android
  OAuth client for the app's actual package and signing SHA-1 in the same Google
  project. If using the team's project, ask its owner to register your signing
  certificate and grant test-user access. Follow the root README's Google setup.
  If `frontend/app/debug.keystore` is present, the build uses that local shared
  debug key; otherwise it uses Android's standard debug key. Register the SHA-1
  reported on your machine. Keystore files are not included in Git.
  Get the SHA-1 with `frontend/gradlew.bat signingReport` from an appropriate shell,
  or run `.\gradlew.bat signingReport` while inside `frontend`.
- Backend and Android must use the same Web OAuth client ID. No replacement token
  or fake login is supplied. The tester signs in manually before feature tests.

## 4. Configure the normal backend once

From the repository root:

```powershell
cd backend
npm.cmd ci
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Edit `backend/.env` (merge with an existing file; do not discard other settings):

```dotenv
PORT=3004
NODE_ENV=development
LOCAL_HTTP=true
E2E_ENABLED=true
DATABASE_URL="mysql://tradeoff_e2e:CHOOSE_LOCAL_PASSWORD@127.0.0.1:3306/tradeoff_e2e_local"
JWT_SECRET=PASTE_A_GENERATED_RANDOM_SECRET
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
ALPACA_API_KEY_ID=YOUR_ALPACA_KEY
ALPACA_API_SECRET_KEY=YOUR_MATCHING_ALPACA_SECRET
```

Replace all placeholders; update the database port/name for your installation.
URL-encode special characters in database usernames/passwords. Generate a JWT
secret with this command and paste the result in `.env`:

```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

For a fresh terminal, `.env` is loaded automatically. If you previously set
`DOTENV_CONFIG_PATH`, `DATABASE_URL` or other configuration variables in this
terminal, open a fresh terminal: existing environment variables override `.env`.
Do not use `.env.buy-e2e` or `.env.e2e` for this workflow.

While inside `backend`, generate the client and create tables from migrations:

```powershell
npx.cmd prisma generate
npx.cmd prisma migrate deploy
```

These commands require the database server to be running. They do not install
MySQL or create the database itself. Do not force/reset an existing non-test database.
For an existing database created with `prisma db push`, migration history may need
baselining; use a new empty disposable database rather than applying destructive fixes.

`LOCAL_HTTP=true` avoids local certificates and binds only to `127.0.0.1`.
Without that flag the normal backend uses HTTPS and needs TLS certificate files.
Cloud deployments should use trusted HTTPS, not this local HTTP option.

## 5. Configure/install Android once (and rebuild after URL changes)

In ignored `frontend/local.properties`, preserve `sdk.dir` and signing settings,
and set:

```properties
API_BASE_URL=http://10.0.2.2:3004/
MARKET_API_BASE_URL=http://10.0.2.2:3004/
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
DEBUG_APPLICATION_ID_SUFFIX=
```

`10.0.2.2` means the host computer from the Android emulator; it is not the
database address. Both APIs must use the **same full backend**, not a Market-only
server. Build/install the debug app through Android Studio. For a physical USB
device use `adb reverse tcp:3004 tcp:3004` and `http://127.0.0.1:3004/` for both
URLs, then rebuild. Use the signing configuration/certificate registered with Google.

## 6. Every test session: normal server, then a test script

Ensure the database is running. Terminal 1, from the repository root:

```powershell
cd backend
npm run dev
```

Leave it open. Expect `Server listening`. In a browser or another terminal,
`http://127.0.0.1:3004/health` should return `{"status":"ok"}`. This verifies
server liveness, not Google/Alpaca connectivity; check Market quotes in the app too.
Open the installed app, sign in through Google and return to Market.

The normal server also starts shared background quote/history refresh loops
(4 seconds / 60 seconds, waiting after each completed request and respecting
provider cooldown). Market receives full cache snapshots at `/api/market/events`
through WebSocket; Android does not poll the stock list periodically. Restart
the backend and rebuild/install Android after updating this feature. Cloud/reverse
proxy setups must forward WebSocket upgrades for this path as well as the existing
`/api/friends/events`. Local HTTP uses `ws://`; HTTPS uses `wss://` through the
same base URL and certificate configuration. No additional server is required.

Terminal 2, from the repository root, choose one command:

| Tests | Command |
| --- | --- |
| All five real-backend feature tests | `.\scripts\run-frontend-e2e-tests.ps1` |
| Browse Stocks only | `.\scripts\run-browse-stocks-e2e-tests.ps1` |
| Place Market Buy only | `.\scripts\run-market-buy-e2e-tests.ps1` |
| View Portfolio only | `.\scripts\run-view-portfolio-e2e-tests.ps1` |
| View Leaderboard only | `.\scripts\run-view-leaderboard-e2e-tests.ps1` |
| Send Friend Request only | `.\scripts\run-send-friend-request-e2e-tests.ps1` |

These use normal Gradle output. Results are in
`frontend/app/build/reports/androidTests/connected/debug/index.html`.
The combined runner explicitly runs one class per Gradle invocation, in this order:
Browse Stocks, Place Market Buy, View Portfolio, View Leaderboard, Send Friend Request.
It waits for each to finish before starting the next and continues after a test
failure so every suite is attempted. Any failed suite makes the overall command fail.
It does not start the backend, install MySQL or sign in for you.

Because each invocation replaces Gradle's default report, the combined runner
copies each fresh HTML report to
`frontend/app/build/reports/e2e-all/<run-id>/<test-class>/index.html`.
The default report path contains only the most recent invocation. Preserve the
run-specific folder when sharing results. If setup/build failed before generating
a new report, inspect the terminal output; an old report is not copied as a new result.

Before a combined run, sign in again if necessary so the session remains valid
through the full run (Browse Stocks alone takes about two minutes). If a suite
fails with HTTP 401, sign in again and rerun that suite or the combined command.
On macOS/Linux use the corresponding `.sh` runner with `bash`, and `npm`/`npx`.
Use one emulator/device and do not run tests against the same account concurrently.
Stop the backend with Ctrl+C. Restart the database after reboot if it is not a service.

## What the tests erase

Before and after each mutating/prepared feature test (Buy, Portfolio, Leaderboard
and Send Friend Request), Android calls `POST /__e2e/account/reset` using the
current Google session's backend JWT. The normal backend removes **that user's**
orders, trades and positions, and sets cash/starting balance to $10,000 in one
transaction. It preserves login, profile and Favorites. It does **not** restore
previous trading data. Cleanup is attempted on assertion failure; a killed process
or unavailable server can prevent it, so the next run prepares the account again.

The endpoint is absent unless `E2E_ENABLED=true`. Enabling it against a database
not named `tradeoff_e2e_<name>` prevents startup. Never enable this against real
user data. The PDF permits automated data preparation but does not explicitly
approve a reset endpoint; confirm this fixture mechanism with the TA before submission.

The Leaderboard and Send Friend Request tests also remove the current test account's existing friendships
and prepares six accepted participants, one pending participant and one unrelated
participant in the real database. Those temporary users and their data are deleted
afterward. Original friendships are not restored. Profiles/login/Favorites of the
current user remain intact. No second Google login is needed.
Browse Stocks performs no reset or cleanup: it only reads data and checks that
the account and Favorites have not changed.

## Firebase/NFR integration

The merged normal server attaches authenticated friend WebSockets to both local
HTTP and deployed HTTPS, and retains Firebase push-notification routes. A Firebase
service-account file is required to verify actual push delivery; configure
`GOOGLE_APPLICATION_CREDENTIALS` for that separately. Never commit that file.
The included Android Firebase project configuration must match the team's setup.
The five acceptance tests do not claim to verify push delivery or replace Firebase.

Friend requests now use unique usernames, not UUIDs. Fixture participants receive
unique generated usernames. If the current test user has no username, fixture setup
temporarily assigns one and cleanup removes that exact generated username; an
existing chosen username is preserved. Request database records still reference user IDs.

Migration reconciliation keeps the existing
`20261118082656_stock_expansion_favorites` as the canonical trading-table migration,
and adds `20261118082734_add_notification_devices`. The duplicate incoming
`20261118000000_add_trading_tables` is excluded. Before migrating another existing
deployment, inspect its `_prisma_migrations`: if it already applied that duplicate
or has trading tables without the canonical migration recorded, stop and reconcile
the baseline with its operator. Do not drop tables, reset data or blindly mark
migrations applied. Local validation was against the existing case-insensitive
MariaDB database; other deployments must be checked separately.

Run NFR tests separately from the Android acceptance tests:

```powershell
cd backend
npm.cmd run test:nfr
```

These use controlled providers and no real database, as documented in
`backend/tests/nfr/README.md`; this does not introduce mocks into Android E2E.

## Other tests and old fixtures

The common E2E runner selects `BrowseStocksE2ETest`, `TradingE2ETest`, `PortfolioE2ETest`, `LeaderboardE2ETest` and `SendFriendRequestE2ETest`.
It does not run every class in `androidTest/e2e`. The old `MarketFlowTest` was
removed; `run-market-e2e-tests.ps1` now runs Browse Stocks against the normal
backend. The remaining old `PortfolioFlowTest` depends on a special fixture
host and is not part of this no-mock acceptance workflow. Do not use
`backend/scripts/e2e-server.ts` or `market-e2e-server.ts` for the five feature tests.

Backend API/Jest tests are separate: see `Testing.md` for mock and no-mock suites;
their database/service requirements differ. Do not assume this runner covers them.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `DATABASE_URL is not set` | `backend/.env` has a real URL; run from `backend`; remove stale environment overrides. |
| Connection refused / access denied by MySQL | Database process/service, TCP port, database existence and app-user credentials. |
| E2E startup guard error | Use `tradeoff_e2e_<name>`, not the main database. |
| Reset returns 404 | Updated normal backend and `E2E_ENABLED=true`; restart after `.env` edits. |
| Reset returns 401 | Sign in again; the existing session expired or JWT secret changed. |
| App cannot reach server | `/health`, both URLs, port, HTTP versus HTTPS, rebuild after config changes. |
| Google login fails | Web client IDs match, Android package/SHA-1 registered, test-user access granted. |
| Alpaca quotes unavailable | Matching real key pair, service/network availability; `/health` alone does not prove this. |
| PowerShell blocks `npm.ps1` | Use `npm.cmd`/`npx.cmd`. |
| Gradle cannot find Java/SDK | Android Studio bundled JDK; `sdk.dir` or `ANDROID_HOME`. |

Never commit `.env`, `local.properties`, passwords, API keys, database files or dumps.
