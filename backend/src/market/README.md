# Market API: 100 Alpaca IEX stocks

The 100 U.S.-listed stocks in `catalog.ts` use Alpaca IEX quotes and history.
There are no mock prices/charts or fabricated offline prices. Company names
and default favorites are catalog metadata. The authenticated favorites API
stores each user's selections in MySQL. Signed-out users can browse the catalog
and are prompted to sign in when they tap a star or open Favorites.

The full backend serves `GET /api/users/me/favorites` and
`PUT`/`DELETE /api/users/me/favorites/:ticker`. Apply the Prisma
`20261117000000_stock_favorites` migration and regenerate the Prisma client
before running the updated backend. The Market-only development server
serves public quotes and history.
Android calls our backend; Alpaca credentials never leave the backend.
Trading/order execution and Portfolio integration remain separate features.

## Local setup

Copy backend/.env.example to backend/.env on a new machine and set:

```dotenv
ALPACA_API_KEY_ID=your_key_id
ALPACA_API_SECRET_KEY=your_secret
```

Never commit real credentials. The .env file is Git-ignored. Restart the
backend after changing keys. From backend/:

```powershell
npm.cmd ci
npm.cmd run dev:market
```

This starts a Market-only development server at http://127.0.0.1:3001,
without database, authentication or TLS initialization. Keep it running and
the computer awake. It is not a deployed service or a production entrypoint.
The regular app.ts server already mounts this router under /api/market.

In frontend/local.properties, preserve existing settings and add:

```properties
MARKET_API_BASE_URL=http://10.0.2.2:3001/
DEBUG_APPLICATION_ID_SUFFIX=.marketdemo
```

Sync Gradle and rebuild/install app in Android Studio. Open TradeOff Market
Dev. Android Emulator's 10.0.2.2 reaches the host loopback directly, so no
adb reverse rule is needed. Physical USB devices can instead use
http://127.0.0.1:3001/ plus adb reverse tcp:3001 tcp:3001; that rule must be
restored after ADB/device reconnection. URL changes require an app rebuild.
The override affects Market only, not authentication.

## Endpoints and shared usage

| Request | Response |
| --- | --- |
| GET /health | Development server liveness, not Alpaca connectivity |
| GET /api/market/stocks | {liveSymbols, refreshIntervalMs:4000, stocks:[...]} |
| GET /api/market/stocks/:symbol | One supported stock, case-insensitive symbol |
| GET /api/market/stocks/:symbol/history | That stock's real chart history |

Only catalog symbols are allowed. Unknown symbols return 404. All
responses have Cache-Control: no-store, so HTTP intermediaries do not hide
our current health status. Backend caches still apply.

The list returns HTTP 200 even during an outage; inspect each stock's status.
A single-stock response returns 503 if it has no quote, or 200 with retained
stale data after refresh failure. History returns 503 on an outage with no
points; an empty successful history returns 200 without invented points.

Each stock includes ticker, companyName, currency, isFavorite, currentPrice,
change, percentageChange, source, feed, status, quoteAsOf, fetchedAt,
quoteAgeSeconds, retryAt, error, historySource, history and performancePoints.
Prices/dollar changes are decimal strings or null; percentageChange is a
number or null (1.5 means 1.5%). Never turn a missing price into zero.
source is alpaca; feed is iex. status is ok, stale or unavailable. quoteAsOf
is the market observation time; fetchedAt is the successful retrieval time.
Successful HTTP polling does not mean a new trade happened or the market is
open. IEX is one exchange, not the consolidated US market.

history has {symbol,feed,points,dailyBarsUpdatedAt,status,retryAt,error}.
Each point is {timestamp,price}. performancePoints is a compatibility array
of those prices. Use timestamped points for graphs. Prices and historical
bars are raw/unadjusted, so splits can create discontinuities.

Android teammates should reuse ApiClient.createMarket(context),
MarketApiService, MarketRepository and MarketStock in ui/market/data/.
MarketScreen collects `/api/market/events` over a lifecycle-aware WebSocket
only while STARTED. Each `market.snapshot` includes the full stock list, histories,
a revision and checkedAt. There is no recurring Android HTTP polling. A failed
socket triggers one REST recovery request and reconnects with 1–30s backoff;
every connection receives the current cache without triggering an Alpaca fetch.
MarketSparkline accepts points, color
and a symbol. Company logos are bundled in Android drawable resources.
Backend features should reuse the exported marketService singleton from
service.ts, e.g. await marketService.getStock('MSFT'). Do not create a new
service/cache per request or implement a second Alpaca client. Order
execution still needs separate server-side freshness and business checks.

## Alpaca calls, caching and failure behavior

- Snapshots: GET /v2/stocks/snapshots?symbols=...&feed=iex. One batch for all
  due symbols, cached for four seconds after success.
- History: GET /v2/stocks/bars?symbols=...&timeframe=1Day&feed=iex&... . A
  separate batch cached for one minute. Queries cover 45 calendar days and
  end at UTC midnight; keep 30 daily closes per
  stock. Each page contains up to 1000 bars across all symbols. The provider
  follows next_page_token and reports an error if pagination repeats.
- Concurrent list/individual/history requests share in-flight batch promises.
  Even a cold single-symbol request warms every due catalog symbol. The normal
  server starts two independent background loops immediately: quotes every 4s,
  history every 60s. Each waits its interval after completion so slow requests
  never overlap. Timers stop on shutdown; provider cooldown still takes priority.
  Cache changes (including failures) broadcast to Market WebSocket subscribers.
- Each symbol has its own quote/history/rolling-sample cache. A malformed or
  missing quote affects that symbol; successful symbols remain usable.
- Real quote samples are deduplicated by trade timestamp, max 120 per symbol.
  Combined chart is sorted, deduplicated and capped at 30+120 points per stock.
  Repeated closed-market quotes do not fabricate movement. Fewer than two
  valid timestamps draws no line; flat prices draw a horizontal line.
- At most about 15 normal snapshot batches/minute plus history every minute
  (typically 3–4 history pages, depending on returned bars). Network time and
  cooldown reduce the background rate; separate processes/keys and other callers
  must be considered when estimating total API usage.
  Each backend process has its own in-memory cache, which clears on restart.
- Each HTTP request has a four-second timeout, a response-size limit and no
  redirects. No immediate retry loops. Generic errors use their configured cooldown;
  config/auth errors 60 seconds. HTTP 429 waits at least 60 seconds and honors
  longer Retry-After/X-RateLimit-Reset. Provider-wide cooldown is shared.
- Previously successful quotes and graphs survive errors as stale data. A
  history-only failure does not invalidate a successful quote. Exceptions
  sent to clients never include the raw Axios config or credentials.

Official contract references:
- https://docs.alpaca.markets/us/reference/stocksnapshots-1
- https://docs.alpaca.markets/us/reference/stockbars
- https://developer.android.com/studio/run/emulator-networking-address

## Logos

All 100 stock logos are bundled locally as user-supplied PNGs. No logo service,
additional key, network request or image-loading dependency is required.
GOOGL uses Google's mark for Alphabet's listing. Stocks without a
bundled logo display their ticker in the logo tile.

## Verification

```powershell
# From backend/
npm.cmd run typecheck
npm.cmd run build
npm.cmd test -- tests/mock/market.test.ts tests/mock/marketBatch.test.ts --runInBand
# Manual HTTP check
Invoke-RestMethod http://127.0.0.1:3001/api/market/stocks
```

Backend tests use mocked providers, not live quota. They cover batch sharing,
independent TTLs, every symbol's routes, missing/partial data, pagination,
cooldowns, stale retention, bounds and sanitized errors. The pre-existing
full-suite Prisma/Jest resolver issue remains outside this module.

```powershell
# From frontend/ (requires configured SDK/JDK)
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
# Emulator + full backend + real Google login required for Browse Stocks
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.BrowseStocksE2ETest'
```

BrowseStocksE2ETest checks the real backend catalog of all 100 stocks.
Manually verify that fetchedAt advances after successful cache refreshes and
that navigating away closes the Market socket. Stop
and restart the server to check retained values and automatic recovery.
# Local acceptance testing

For all four Market use cases, run `scripts/run-market-e2e-tests.ps1` from the
repository root (Search, Details, Favorites, then Browse). For the complete twelve-case
set use `scripts/run-frontend-e2e-tests.ps1`. Both use the full ordinary backend via
`npm run dev` and follow [Local E2E Setup](../../../documentation/Local_E2E_Setup.md).
Market-only servers and deterministic fixtures below are separate development
tools; they do not replace the authenticated backend for these acceptance tests.
