# Testing

## 1. Change History

| **Change Date** | **Modified Sections**                   | **Rationale**                                                                                                    |
| --------------- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 2026-10-20 | Section 4: Front-end test specification | Describe simple user flows through the currently implemented screens and state the sample-data limitations. |
| 2026-11-03 | Section 2.1: Market API tests | Specify mocked and live integration tests for every Market HTTP interface. |
| 2026-11-03 | Section 4: Market E2E tests | Use deterministic market fixtures so the app-to-API flows run without MySQL or Alpaca credentials. |
| 2026-11-04 | Section 2.1: Renumbered Use Cases | Renumber use cases following the removal of delete account (UC-05 to UC-19). |
| 2026-11-18 | Section 3: Non-functional testing | Replace template placeholders with NFR-1/2/3 tests, run instructions, measured results, evidence locations, and the stale-price production fix. |

---

## 2. Back-end Test Specification: APIs

### 2.1. Locations of Back-end Tests and Instructions to Run Them

#### 2.1.1. Tests

| **Interface** | **Describe Group Location, No Mocks** | **Describe Group Location, With Mocks** | **Mocked Component** |
|---|---|---|---|
| `GET /api/market/stocks` (UC-05) | `backend/tests/no-mock/market.test.ts`: `Unmocked API: GET /api/market/stocks` | `backend/tests/mock/marketApi.test.ts`: `Mocked API: GET /api/market/stocks` | Alpaca quote and history provider |
| `GET /api/market/stocks/:symbol` (UC-07) | `backend/tests/no-mock/market.test.ts`: `Unmocked API: GET /api/market/stocks/:symbol` | `backend/tests/mock/marketApi.test.ts`: `Mocked API: GET /api/market/stocks/:symbol` | Alpaca quote and history provider |
| `GET /api/market/stocks/:symbol/history` (UC-07) | `backend/tests/no-mock/market.test.ts`: `Unmocked API: GET /api/market/stocks/:symbol/history` | `backend/tests/mock/marketApi.test.ts`: `Mocked API: GET /api/market/stocks/:symbol/history` | Alpaca history provider |
| `POST /api/trades/execute/market` (UC-08, UC-10) | `backend/tests/no-mock/trades.test.ts`: `Unmocked API: POST /api/trades/execute/market` | `backend/tests/mock/tradesApi.test.ts`: `Mocked API: POST /api/trades/execute/market` | Prisma client, market data lookup |
| `POST /api/trades/execute/limit` (UC-09, UC-10) | `backend/tests/no-mock/trades.test.ts`: `Unmocked API: POST /api/trades/execute/limit` | `backend/tests/mock/tradesApi.test.ts`: `Mocked API: POST /api/trades/execute/limit` | Prisma client, market data lookup |
| `POST /api/trades/orders/:id/cancel` (UC-11) | `backend/tests/no-mock/trades.test.ts`: `Unmocked API: POST /api/trades/orders/:id/cancel` | `backend/tests/mock/tradesApi.test.ts`: `Mocked API: POST /api/trades/orders/:id/cancel` | Prisma client |
| `GET /api/portfolio` (UC-12) | `backend/tests/no-mock/portfolio.test.ts`: `Unmocked API: GET /api/portfolio` | `backend/tests/mock/portfolioApi.test.ts`: `Mocked API: GET /api/portfolio` | Prisma client, market data lookup |

**What the suites verify.** Test names follow the use case scenarios (e.g. `UC-08 8b: insufficient cash is rejected`).

- **No-mock suites** use a real MySQL database and real code paths: database transactions (`db.$transaction`), row-level locking and atomic balance updates across the Account, Position, Order and Trade tables, order cancellation and release of reserved cash, and the background limit-order worker filling pending orders.
    - UC-08 main: a market buy debits cash, updates the position, and records one trade.
    - UC-08 8b: insufficient cash is rejected and balances are unchanged.
    - UC-08: concurrent buys cannot overdraw the account.
    - UC-09 main: a limit price below market creates a pending order and reserves cash.
    - UC-09: the limit-order worker fills the pending order once the price crosses the limit.
    - UC-11 main: cancelling releases reserved cash and leaves the position unchanged.
    - UC-12: the portfolio total includes cash reserved for pending orders.
- **Mocked suites** run with Jest and need no network or database. They test isolated logic and resilience to external failures.
    - UC-05 main: a second request within 8 s is served from cache, and the provider is called once.
    - UC-05 main: a request after 8 s refetches from the provider.
    - UC-05 2a: cache expired or provider error → returns `200` with per-stock `status: 'stale'` or `'unavailable'`.
    - UC-07: stock details or history with provider error reports `status: 'stale'` or `'unavailable'`, and an unsupported symbol → `404`.
    - UC-08 8a, 7a: invalid quantity and unavailable price are rejected.
    - UC-12 3a: unavailable prices for held tickers → `503`.

Authenticated routes are called with a JWT signed with the backend's real secret. The Google ID-token verifier is not exercised in these suites.

#### 2.1.2. Commit Hash Where Tests Run

Not recorded yet. Run the Market API test suites and record the tested commit here before submission.

#### 2.1.3. How to Run Market API Tests

Run mocked tests from the `backend` directory. They use deterministic provider responses and need no credentials, database, or internet:

```powershell
npm.cmd run test:market:api:mock
```

Run no-mock integration tests from the `backend` directory. Put `ALPACA_API_KEY_ID` and `ALPACA_API_SECRET_KEY` in `backend/.env`; they contact Alpaca IEX and need internet access. They do not use MySQL. These tests consume live provider quota, so run them sparingly:

```powershell
npm.cmd run test:market:api:live
```

The tests cover successful responses and unsupported symbols. The no-mock suite expects Alpaca to return usable data; authentication, network, or provider failures should make it fail rather than silently pass.

**Contract note:** Per NFR-3 and UC-05, `GET /api/market/stocks` returns `200` when provider refresh fails, returning each stock with its own `status` (`'ok'`, `'stale'`, or `'unavailable'`). Operations requiring fresh prices (trade execution and portfolio valuation) return `503` when prices cannot be retrieved.

### 2.2. GitHub Actions Configuration Location

`~/.github/workflows/backend-tests.yml`

### 2.3. Jest Coverage Report Screenshots With Mocks

_(Placeholder for Jest coverage screenshot with mocks enabled)_

### 2.4. Jest Coverage Report Screenshots Without Mocks

_(Placeholder for Jest coverage screenshot without mocks)_

---

## 3. Tests of Non-Functional Requirements

### 3.1. Test Locations and Expected Results

| Requirement | Test file | Workload and pass condition |
| --- | --- | --- |
| NFR-1: Response-Time Consistency | [responseTime.test.ts](../backend/tests/nfr/responseTime.test.ts) | Warm the cache, then send 100 sequential requests to each of three Market endpoint shapes (300 total). Each endpoint's p95 must be strictly below 1000 ms; every response must be successful and contain usable data. |
| NFR-2: Concurrent-Load Efficiency | [concurrentLoad.test.ts](../backend/tests/nfr/concurrentLoad.test.ts) | Start 10 concurrent simulated users, each sending 30 requests (300 total, 100 per endpoint). Each endpoint's p95 must be below 1000 ms, with no failed or invalid responses. |
| NFR-3: External-Service Reliability | [providerRecovery.test.ts](../backend/tests/nfr/providerRecovery.test.ts) | Verify retained data is stale during an outage, never-retrieved data is unavailable, trading and portfolio valuation reject unusable prices, and the same service instance recovers after the retry delay without restarting. |

The endpoint shapes are `GET /api/market/stocks`, `GET /api/market/stocks/:symbol`,
and `GET /api/market/stocks/:symbol/history`. AAPL represents the parameterized
routes; this is not a separate load test for every symbol in the catalog.

### 3.2. How to Run

From the repository root in PowerShell:

```powershell
cd backend
npm.cmd run test:nfr
```

If Android Studio's terminal starts in `frontend/`, use `cd ../backend` instead.
The backend dependencies and generated Prisma client must already exist. On a
fresh checkout, install dependencies as described in the project setup and run
`npx.cmd prisma generate`. No running backend, MySQL database, emulator, real
API keys, or cloud configuration is required for these NFR tests.

To run only one requirement from `backend/`:

```powershell
npm.cmd run test:nfr -- responseTime
npm.cmd run test:nfr -- concurrentLoad
npm.cmd run test:nfr -- providerRecovery
```

The runner uses [jest.nfr.config.js](../backend/jest.nfr.config.js), runs suites
serially, and exits with a nonzero code if any test fails.

### 3.3. Measurement and Failure-Injection Method

- **NFR-1/2:** Supertest exercises the real HTTP router, market cache, processing,
  and serialization. Only the external provider is stubbed, so cache expiry
  cannot introduce live Alpaca latency. Warm-up requests are excluded. Real
  timers and `performance.now()` measure each request; coverage instrumentation
  is disabled to avoid distorting timing. p95 is the nearest-rank value at
  `ceil(0.95 * N)` in sorted durations. Status codes and response contents are
  checked so fast error or empty responses cannot pass.
- **NFR-2:** Ten workers start together and each awaits its own response before
  sending another request, while the other workers continue. Requests rotate
  across all three endpoint shapes. This is a fixed-load test, not a test of
  maximum capacity or stress to failure.
- **NFR-3:** Six cases use fake time and a controllable provider to exercise warm
  and cold caches, outage retention, rejection by trading and portfolio
  valuation, retry suppression just before the deadline, and recovery just
  after it. Production price consumers delegate to the real market service
  under test. Database access is mocked; no real trades are placed.

These choices follow the supplied lecture notes: W7 L2 Testing I, pages 14 and
40-45 (test oracles and controlled external failures), and W8 L1 Testing II,
pages 24-28, 49, and 65 (measured performance, non-functional testing, regression
tests, and automation). Thresholds and workloads come from the project's NFRs.

### 3.4. Verified Results and Saved Evidence

The verification run after the production stale-price fix passed **3 suites,
8 tests, 0 failures**. The recorded local measurements are:

| Requirement | Endpoint | Measured requests | p95 (ms) | Failed/invalid responses |
| --- | --- | --- | --- | --- |
| NFR-1 | `/api/market/stocks` | 100 | 8.19 | 0 |
| NFR-1 | `/api/market/stocks/AAPL` | 100 | 1.69 | 0 |
| NFR-1 | `/api/market/stocks/AAPL/history` | 100 | 1.50 | 0 |
| NFR-2 | `/api/market/stocks` | 100 | 36.19 | 0 |
| NFR-2 | `/api/market/stocks/AAPL` | 100 | 33.35 | 0 |
| NFR-2 | `/api/market/stocks/AAPL/history` | 100 | 33.34 | 0 |

Timing report timestamps (UTC): NFR-1 `2026-11-19T02:00:34.446Z`;
NFR-2 `2026-11-19T02:00:35.467Z`. Environment: Node
`v22.23.3`, `win32 10.0.26200`,
AMD Ryzen 7 9800X3D 8-Core Processor.

These are recorded results, not guaranteed future timings. The run verified the
working tree containing the NFR additions and fix; no tested commit hash was
captured. Record the final submitted commit when rerunning for submission.

Generated evidence is saved under `backend/reports/nfr/`:

- `NFR-1.json`: all sequential request timings, endpoint summaries, and environment.
- `NFR-2.json`: all concurrent request timings, endpoint summaries, and environment.
- `results.json`: Jest test outcomes and any failure details from the latest run.

These files are ignored by Git. Save copies and a terminal screenshot with the
submission. Reruns overwrite reports; a single-requirement run replaces
`results.json` with only that run's outcomes. If a run times out before writing
timings, do not use an older timing file as evidence for the failed run.

### 3.5. NFR-3 Defect Found and Fixed

The original suite failed because `loadPrices` accepted a positive retained
price even when its status was `stale`. Portfolio valuation could consequently
present outdated prices as current during a provider outage.

The production fix in [valuation.ts](../backend/src/market/valuation.ts) requires
`status === "ok"` before accepting a price. Stale or unavailable quotes map to
null, causing held positions to raise `PriceUnavailableError`; the portfolio
API handles that error with HTTP 503. Fresh prices become usable again after
provider recovery. The NFR assertions were not weakened or changed to pass.

After the fix, all 8 NFR tests, 35 mocked portfolio/trading API tests, and the
TypeScript typecheck passed. The additional checks were:

```powershell
npm.cmd run typecheck
npm.cmd test -- --runInBand --coverage=false tests/mock/portfolioApi.test.ts tests/mock/tradesApi.test.ts
```

### 3.6. Scope and Limitations

These tests measure local backend behavior with a controlled provider. They do
not establish deployed-server capacity, real Alpaca reliability or latency,
database performance, HTTPS/proxy latency, mobile-network latency, or Android
rendering time. They complement, rather than replace, frontend end-to-end tests.


---

## 4. Market Frontend End-to-End Testing

### Test Suite Location

The Market E2E suite is `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/MarketFlowTest.kt`. It launches the real Android app and sends HTTP requests through the production `/api/market/stocks` route and market cache. `backend/scripts/market-e2e-server.ts` supplies fixed, deterministic quote and history data through that route. It does not connect to MySQL, require sign-in, call Alpaca, or use API keys. The fixture data has Alpaca-shaped responses; passing these tests does not verify live Alpaca connectivity.

### How to Run E2E Tests

1. Make sure backend dependencies are installed. From the `backend` folder, run this once if `node_modules` is missing:

   ```text
   npm install
   ```

2. Start an Android emulator. In the first terminal, run the fixture server and keep it open:

   ```powershell
   cd backend
   npm.cmd run dev:e2e:market
   ```

3. In a second terminal at the repository root, run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\run-market-e2e-tests.ps1
   ```

   The runner temporarily points `MARKET_API_BASE_URL` in `frontend/local.properties` to the fixture on port 3003, runs only `MarketFlowTest`, then restores the original local properties. Stop the fixture with Ctrl+C after tests finish. The emulator uses `10.0.2.2` to reach the host computer. If port 3003 is busy, stop the process using it and rerun the command. No manual URL edits are needed.

### Tested Use Cases

Market: load the 100-stock catalog from the HTTP API, view names, quotes, charts, and bundled logos or ticker badges, and search by company name or ticker including the no-results state.

### Test Scenarios

| Test | Scenario and expected behavior |
|---|---|
| `allSixStocksShowNamesQuotesChartsAndLocalLogos` | The app loads the fixture catalog and verifies the name, quote timestamps, chart, and bundled logo for each of the six original stocks. |
| `searchMatchesTickerAndCompanyAndShowsAnEmptyStateForNoMatch` | Search finds a stock by company and ticker, shows the empty state for an unknown query, and displays the expected fixture quote. |

### Execution Results

The test runner writes Android instrumentation reports to the paths below. Record the latest result after running the suite on an emulator.

HTML report: `frontend/app/build/reports/androidTests/connected/debug/index.html`.
Raw test results: `frontend/app/build/outputs/androidTest-results/connected/debug/`.
