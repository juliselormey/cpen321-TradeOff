# Market: Alpaca IEX, cached snapshots and history

Only **AAPL** uses Alpaca Market Data. MSFT, TSLA, NVDA, GOOGL and AMZN
retain their existing mock values and charts. This increment does not add
orders, Buy/Sell, Portfolio integration, a database, Redis or WebSockets.
Android talks only to our backend. No Alpaca credential is sent to Android.

## Credentials and local setup

Create an Alpaca account with Market Data Basic and generate an API key ID
and secret in its dashboard. A paper-account key can access the data service
according to that account's entitlements. We use the market-data host, never
a trading/order endpoint. Finnhub keys do not work with Alpaca.

Put both credentials in the ignored `backend/.env`:

```dotenv
ALPACA_API_KEY_ID=your_key_id
ALPACA_API_SECRET_KEY=your_secret
```

Do not put real keys in `.env.example`, source code, logs, Android resources
or BuildConfig. Restart the backend after changing environment values.

For the local emulator demo (no auth database/TLS setup required):

```powershell
cd backend
npm run dev:market
```

This runs only the existing Market router and development health endpoint
on `127.0.0.1:3001`. In another terminal, run:

```powershell
adb reverse tcp:3001 tcp:3001
```

In `frontend/local.properties`, set:

```properties
MARKET_API_BASE_URL=http://127.0.0.1:3001/
# Optional, to preserve an installed app signed with another key:
DEBUG_APPLICATION_ID_SUFFIX=.marketdemo
```

Rebuild/install the Android app and open **TradeOff Market Dev**. Restoring
adb forwarding may be necessary after reconnecting/restarting the emulator.
Keep the dev server running. The override affects only Market, not login.
Without it, Market uses API_BASE_URL, which needs this router deployed.
The regular HTTPS backend still needs its existing Google/JWT/MySQL/TLS setup.

## External API requests

All requests go to `https://data.alpaca.markets`, with backend-only
`APCA-API-KEY-ID` and `APCA-API-SECRET-KEY` headers and **explicit feed=iex**:

- `GET /v2/stocks/AAPL/snapshot?feed=iex`: newest valid trade price, or an
  available minute/daily bar close if no valid trade exists. Dollar and
  percentage changes use prevDailyBar.c; missing prior close yields null
  changes, never an invented baseline.
- `GET /v2/stocks/AAPL/bars?timeframe=1Day&feed=iex&...`: at most 30 recent
  daily closes within a 45-calendar-day lookback. The end precedes today's
  New York session so partial current-day daily bars are not mixed in.
  sort=desc selects the newest bounded subset in one request; we deliberately
  do not paginate through the older dataset. Results are sorted ascending
  before charting. adjustment=raw matches the unadjusted snapshot prices.

IEX covers one exchange, not the full consolidated US market. A successful
HTTP refresh does not imply a new trade or an open exchange. Price, quote
timestamp and retrieval timestamp may behave differently outside trading
hours. Basic-plan entitlements/limits must be checked for the actual account.

Official references:
- https://docs.alpaca.markets/us/reference/stocksnapshotsingle
- https://docs.alpaca.markets/us/reference/stockbarsingle-1
- https://docs.alpaca.markets/us/docs/about-market-data-api

## Backend cache rules

MarketService owns separate JavaScript Maps keyed by symbol:

| Cache | Lifetime / bound | Behavior |
| --- | --- | --- |
| Current price | 8 seconds after successful fetch | price, change, percentage, quote timestamp, retrieval time |
| Historical daily bars | 15 minutes; max 30 points | fetched on demand, not every snapshot refresh |
| Rolling trade samples | max 120 points | appended only from successful real snapshots, deduplicated by trade timestamp |
| In-flight operations | one per symbol and data type | concurrent clients share snapshot/history promises |

Snapshot and history requests run concurrently on a cold load. No recurring
server timers run: after clients stop asking, external requests stop. Android
waits approximately eight seconds between completed requests and cancels its
loop when Market stops being active. Multiple frontend clients share one
backend process's caches. At continuous demand that is about 7.5 snapshot
requests/minute plus an occasional history fetch, not one provider poll per
phone. In-memory caches are independent across processes and reset on restart.

Chart points combine historical daily closes and bounded recent samples,
sorted by timestamp. Daily bars retain their provider period-start timestamps;
samples retain actual trade timestamps, not the time we polled. Repeated
closed-market snapshots do not create extra points or random movement.
The combined chart is capped at 150 points. A single point is not drawn as a
line. The frontend uses a small custom Compose Canvas with a timestamp-based
x-axis and safely handles constant prices; no chart dependency was added.

Both external requests have four-second timeouts, bounded response sizes,
disabled redirects and no immediate retry loops. Generic failures cool down
for 30 seconds; auth/config failures for 60 seconds. HTTP 429 waits at least
60 seconds and honors longer Retry-After/X-RateLimit-Reset guidance. Shared
provider backoff applies across snapshot and history to avoid retry storms.
Previously successful values survive errors and are labelled stale. A failed
live response is never replaced by the old AAPL mock price. A history-only
failure does not invalidate a successful current price.

Use the exported `marketService` singleton rather than constructing services
per HTTP request. QuoteProvider is the provider-independent extension point.
The existing allowlist enables only AAPL; expanding real stocks later requires
explicit per-stock configuration, not unrestricted caller-controlled fetches.

## Our HTTP contract

### GET /api/market/stocks

Returns `{ liveSymbol: "AAPL", refreshIntervalMs: 8000, stocks: [...] }`.
HTTP 200 indicates the mixed list is available. Inspect per-stock status:
the five mocks remain usable even if AAPL has no provider data.

### GET /api/market/stocks/AAPL

Returns the individual stock. No successful quote yet: HTTP 503, null price,
and an explicit issue. Cached quote after a failed refresh: HTTP 200 with
status=stale and the old fetchedAt. Other supported symbols return mock data
without calling Alpaca; unsupported symbols return 404.

| Field | Meaning |
| --- | --- |
| ticker/companyName/currency/isFavorite | Display metadata; USD; favorites are still demonstration values |
| currentPrice/change | Decimal strings; null when unavailable |
| percentageChange | Change relative to the snapshot's previous daily close |
| source/feed | alpaca + iex for AAPL, mock + null for samples |
| status | ok / stale / unavailable / mock |
| quoteAsOf/fetchedAt | Provider data time / last successful snapshot retrieval time |
| quoteAgeSeconds | Age of provider data, not cache age |
| retryAt/error | Safe retry guidance and {code,message}; no request config/credentials |
| historySource | alpaca / unavailable / mock |
| history | AAPL's chart object; null for mocks |
| performancePoints | Numeric chart compatibility field; real points for AAPL, unchanged samples for mocks |

### GET /api/market/stocks/AAPL/history

Returns `{symbol,feed,points,dailyBarsUpdatedAt,status,retryAt,error}`. Points
are `{timestamp,price}` pairs. This endpoint reuses the same history cache,
does not trigger a snapshot request, and fetches daily bars only when due.
An empty successful history returns 200 with no invented points. An outage
without any points returns 503; retained points return 200/stale. Non-AAPL
history returns 404. All responses use Cache-Control: no-store so clients
see our current status rather than a browser/proxy's cached success.

## Verification

Offline backend tests (no credentials or market quota):

```powershell
cd backend
npm run typecheck
npm run build
npm test -- tests/mock/market.test.ts --runInBand
```

They cover both caches, concurrency, independent TTLs, bounded/deduplicated
history, old snapshots, empty/invalid data, partial failures, credentials,
HTTP errors and shared backoff. The existing full-suite Prisma/Jest resolver
issue remains separate from this module.

Android build/unit tests:

```powershell
cd frontend
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

MarketChartTest runs component rendering tests with labelled deterministic
fixtures; it is not evidence of real Alpaca connectivity. The sample-search
flow verifies both section headings and unchanged navigation. The connected
provider flow requires actual Alpaca credentials and the running backend:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Manual live check:
1. GET the stock list: AAPL source=alpaca/feed=iex; five source=mock entries.
2. Request AAPL twice inside eight seconds: fetchedAt must remain the same.
3. After eight seconds, request again: fetchedAt advances on success, while
   quoteAsOf/price may remain unchanged during closed-market periods.
4. Call /AAPL/history repeatedly: dailyBarsUpdatedAt stays stable for 15
   minutes; rolling real samples may accumulate without another bars call.
5. On Android, verify Alpaca IEX connection, price/change, actual history
   chart (when at least two distinct timestamps exist), and both stock sections.
6. Stop backend: Android keeps previous prices/history and shows an outage.
   Restart: it reconnects. Missing credentials must show an explicit setup
   error, not a fabricated quote or successful real-data label.
7. Leave Market or background Android: its polling coroutine is cancelled.

No trading authorization, order execution, or Portfolio changes are included.
