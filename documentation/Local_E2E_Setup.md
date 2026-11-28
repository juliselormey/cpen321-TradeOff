# Local test setup (no Docker)

This guide prepares the **three selected main features** for automated E2E:
**Market (4 use cases), Trading (4), Portfolio (2)**. The two existing Social formal
cases remain as **extra tests**. The all-tests command runs all **12**; each
feature also has its own runner. Tables in [section 6](#6-every-test-session-normal-server-then-a-test-script)
explain every test and its individual/feature command.

Tests use the normal `npm run dev` backend, real Google login, real MySQL/MariaDB
and real Alpaca data. After one-time setup, each session requires the database,
backend and a test runner. No Docker or special E2E server is required.

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

`LOCAL_HTTP=true` avoids local certificates. The server binds to `0.0.0.0`
so an emulator or LAN device can connect; use it only on your trusted development network.
Without that flag the normal backend uses HTTPS and needs TLS certificate files.
Cloud deployments should use trusted HTTPS, not this local HTTP option. Android
uses its standard certificate trust store; no bundled custom certificate is required.

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

`GOOGLE_CLIENT_ID` must be the Web OAuth client ID used by the backend. The
refactored app no longer supplies a hard-coded fallback: if sign-in says to set
this value, add it to `frontend/local.properties`, then rebuild **and reinstall**.
Rebuilding alone cannot fill in a missing property.

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

The **three selected main features** are Market, Trading and Portfolio: **10
use-case tests** in total. Three of them also cover formal cases from section
3.5: Browse Stocks, Place Market Buy and View Portfolio. Two further formal
Social cases are retained below as **extra tests**, making **12 tests overall**.

After the one-time setup, keep MySQL/MariaDB and the normal backend running,
use Pixel 9 API 36, sign in through Google and return to Market. Run all commands
below from the **repository root**. On macOS/Linux, use the matching `.sh` file
with `bash`.

**Run everything, including the extra formal tests:**

```powershell
.\scripts\run-frontend-e2e-tests.ps1
```

### Market — four use cases

| Run / use case | What it checks | PowerShell command |
| --- | --- | --- |
| **Entire Market feature** | Runs Search → Details → Favorites → **Browse last**. No Trading, Portfolio or Social test class is selected. | `.\scripts\run-market-e2e-tests.ps1` |
| Search Stocks | Full/partial ticker and company searches, case-insensitivity, no-match message, correction and clearing the search. | `.\scripts\run-search-stocks-e2e-tests.ps1` |
| View Stock Details | Identity/logo/quote, chart periods, point inspection, live snapshot receipt and switching to another stock without trading. | `.\scripts\run-view-stock-details-e2e-tests.ps1` |
| Manage Favorite Stocks | Add/remove through stars, view Favorites, persistence and empty state; preserve selections during live updates and restore the original saved set. | `.\scripts\run-manage-favorites-e2e-tests.ps1` |
| Browse Stocks — **formal 3.5.1** | Scroll all 100 cards; check names, tickers, logo elements, displayed price/change and unchanged account data. This is the slowest Market case. | `.\scripts\run-browse-stocks-e2e-tests.ps1` |

### Trading — four use cases

| Run / use case | What it checks | PowerShell command |
| --- | --- | --- |
| **Entire Trading feature** | Runs Market Buy → Limit Order → Cancel Pending Order → Sell Shares. | `.\scripts\run-trading-e2e-tests.ps1` |
| Place Market Buy — **formal 3.5.2** | Invalid quantity/insufficient funds, actual purchase, exact cash/share changes, recorded BUY and persistence. | `.\scripts\run-market-buy-e2e-tests.ps1` |
| Place Limit Order | Invalid inputs/funds, pending BUY with cash reserve, persistence and immediately executable limit BUY using a real quote. | `.\scripts\run-limit-order-e2e-tests.ps1` |
| Cancel Pending Order | Cancel BUY from Trade and SELL from Portfolio; release the exact cash/share reserve, verify CANCELLED and persistence without duplicate refunds. Persisted-state checks do not need price valuation. | `.\scripts\run-cancel-pending-order-e2e-tests.ps1` |
| Sell Shares | Invalid/excess/reserved quantities, partial/full market sales, pending/immediate limit sales, real proceeds and SELL history. | `.\scripts\run-sell-shares-e2e-tests.ps1` |

### Portfolio — two use cases

| Run / use case | What it checks | PowerShell command |
| --- | --- | --- |
| **Entire Portfolio feature** | Runs View Portfolio → View Transaction History. | `.\scripts\run-portfolio-e2e-tests.ps1` |
| View Portfolio — **formal 3.5.3** | Empty/funded portfolios, holdings, cash including reserves, total value, allocation, profit/loss, pending orders and read-only viewing. | `.\scripts\run-view-portfolio-e2e-tests.ps1` |
| View Transaction History | Empty history, actual BUY/SELL fields and newest-first order, no duplicate/pending/cancelled history entries, persistence and unchanged data during viewing. It prepares its own real trades. | `.\scripts\run-view-transaction-history-e2e-tests.ps1` |

### Extra formal tests — Social

These remain implemented, independently runnable, and included in the all-tests
command. They are **additional formal-case coverage**, not a fourth selected
main feature and not part of any Market/Trading/Portfolio-only runner.

| Extra formal use case | What it checks | PowerShell command |
| --- | --- | --- |
| Send Friend Request — **formal 3.5.4** | Username-based pending request, outgoing-list persistence, and unknown/self/existing-relationship validation. | `.\scripts\run-send-friend-request-e2e-tests.ps1` |
| View Leaderboard — **formal 3.5.5** | Self-only and accepted-friend rankings, value/return sorting and unchanged account data. | `.\scripts\run-view-leaderboard-e2e-tests.ps1` |

**Execution and reports:** an individual command selects one test class. That
class can have preparation, several UI stages and cleanup; these are not extra
test classes. Feature/all runners explicitly execute their listed classes one
at a time, continue after a failed class, and return failure if any class failed.
No runner starts the backend/database or performs Google sign-in.

The all-tests order is Search → Details → Favorites → Market Buy → Limit Order →
Cancel Pending → Sell Shares → View Portfolio → Transaction History → Leaderboard →
Send Friend Request → **Browse Stocks last**. Ordering does not supply data to
later tests; each test prepares its own required state.

Individual Gradle reports are overwritten by the next invocation:
`frontend/app/build/reports/androidTests/connected/debug/index.html`.
Aggregate runners preserve fresh per-class reports under:

| Runner | Saved reports |
| --- | --- |
| Market | `frontend/app/build/reports/e2e-market/<run-id>/<class>/index.html` |
| Trading | `frontend/app/build/reports/e2e-trading/<run-id>/<class>/index.html` |
| Portfolio | `frontend/app/build/reports/e2e-portfolio/<run-id>/<class>/index.html` |
| Everything, including extra formal cases | `frontend/app/build/reports/e2e-all/<run-id>/<class>/index.html` |

Use the disposable test database/account described in the setup. Trading,
Portfolio and extra Social tests reset the caller's trading data; Favorites
preparation temporarily changes and then restores its saved set. See the cleanup
and coverage limitations below. Renew Google login before a long run: the backend
JWT lasts 15 minutes. HTTP 401 requires a fresh login, not a database restart.
With the independent Market Buy and Portfolio methods, the all-tests run can
outlast that session. Sign in immediately before starting; if it expires, sign
in again and rerun the affected individual commands. Runners do not generate
or silently refresh Google credentials. An expiry during Browse can interrupt
its final read-only evidence check even after all cards were visited.
A persistent real provider failure is not hidden; unsupported forced-failure
branches remain documented rather than being reported as covered.

## What the tests erase

Before and after each mutating/prepared feature test (all four Trading cases, both Portfolio cases, Leaderboard
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
Search Stocks and View Stock Details likewise perform no trading reset and compare
account/Favorites evidence before and after. Manage Favorites temporarily clears
only the signed-in user's favorites using real authenticated API calls, exercises
add/remove through the UI, and restores the original set in teardown, including
after assertion failures. A killed process or unavailable backend can prevent
restoration; use a disposable test account. No other user is modified.
All Market suites start on Market with an existing real Google login. They do
not mock the backend, inject provider failures, or require a second Google account.
The common setup/evidence helper is `MarketE2ESupport.kt`; it does not use the
legacy `RealBackendTest` isolated/fault server. Browse retains its own read-only setup.

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

The common E2E runner selects `SearchStocksE2ETest`, `ViewStockDetailsE2ETest`,
`ManageFavoritesE2ETest`, `TradingE2ETest`, `PlaceLimitOrderE2ETest`,
`CancelPendingOrderE2ETest`, `SellSharesE2ETest`, `PortfolioE2ETest`,
`ViewTransactionHistoryE2ETest`, `LeaderboardE2ETest`,
`SendFriendRequestE2ETest`, and finally `BrowseStocksE2ETest`.
It does not run every class in `androidTest/e2e`. The old `MarketFlowTest` was
removed; `run-market-e2e-tests.ps1` runs Search, Details, Favorites and Browse
against the normal backend. The remaining old `PortfolioFlowTest` depends on a special fixture
host and is not part of this no-mock acceptance workflow. Do not use
`backend/scripts/e2e-server.ts` or `market-e2e-server.ts` for these acceptance tests.

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
