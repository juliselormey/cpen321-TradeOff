# Market API: six Alpaca IEX stocks

AAPL (Apple), MSFT (Microsoft), TSLA (Tesla), NVDA (NVIDIA), GOOGL
(Alphabet), and AMZN (Amazon) all use real Alpaca IEX quotes and history.
There are no mock prices/charts or fabricated offline prices. Company names
and default favorites are catalog metadata, not user-persisted favorites.
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
| GET /api/market/stocks | {liveSymbols, refreshIntervalMs:8000, stocks:[...]} |
| GET /api/market/stocks/:symbol | One supported stock, case-insensitive symbol |
| GET /api/market/stocks/:symbol/history | That stock's real chart history |

Only the six catalog symbols are allowed; unknown symbols return 404. All
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
Keep screen state in each feature's ViewModel; use lifecycle-aware polling
only while visible. The list already includes histories: do not fetch all
histories again every eight seconds. MarketSparkline accepts points, color
and a symbol; company logos are bundled in res/drawable/ic_stock_*.png.
Backend features should reuse the exported marketService singleton from
service.ts, e.g. await marketService.getStock('MSFT'). Do not create a new
service/cache per request or implement a second Alpaca client. Order
execution still needs separate server-side freshness and business checks.

## Alpaca calls, caching and failure behavior

- Snapshots: GET /v2/stocks/snapshots?symbols=...&feed=iex. One batch for all
  due symbols, cached for eight seconds after success.
- History: GET /v2/stocks/bars?symbols=...&timeframe=1Day&feed=iex&... . A
  separate batch cached for 15 minutes. Queries cover 45 calendar days and
  exclude today's incomplete New York daily bar; keep 30 daily closes per
  stock. Page size 1000 is TOTAL across symbols, not per symbol. Follow
  next_page_token (at most ten pages); repeated/incomplete pagination fails
  honestly rather than losing later symbols. Six stocks normally fit one page.
- Concurrent list/individual/history requests share in-flight batch promises.
  Even a cold single-symbol request warms every due catalog symbol. No
  recurring backend timers: no client demand means no Alpaca polling.
- Each symbol has its own quote/history/rolling-sample cache. A malformed or
  missing quote affects that symbol; successful symbols remain usable.
- Real quote samples are deduplicated by trade timestamp, max 120 per symbol.
  Combined chart is sorted, deduplicated and capped at 30+120 points per stock.
  Repeated closed-market quotes do not fabricate movement. Fewer than two
  valid timestamps draws no line; flat prices draw a horizontal line.
- Normally about 7.5 snapshot batches/minute plus history every 15 minutes,
  not six independent quote requests per poll. Separate backend processes
  have separate in-memory caches; restart clears them.
- Each HTTP request has a four-second timeout, a response-size limit and no
  redirects. No immediate retry loops. Generic errors cool down 30 seconds;
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

Six user-supplied PNG logos are bundled locally. No logo service,
additional key, network request or image-loading dependency is required.
GOOGL uses Google's mark for Alphabet's listing. Supplied filenames and
provenance notes are in frontend/third_party/stock-logos/. Replace a drawable and
rebuild if the team wants a different logo treatment. An unknown ticker has
a letter fallback. Logos identify the companies and do not imply endorsement.

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
# Emulator + backend + real credentials required for MarketFlowTest
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.MarketChartTest,com.example.cpen321application.e2e.MarketFlowTest'
```

MarketChartTest uses explicit rendering fixtures; MarketFlowTest checks the
real backend connection plus all six symbols' names, logos and charts.
Manually verify that fetchedAt stays fixed within the cache TTL, advances
after a successful refresh, and that navigating away stops polling. Stop
and restart the server to check retained values and automatic recovery.
