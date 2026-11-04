# Testing

## 1. Change History

| **Change Date** | **Modified Sections**                   | **Rationale**                                                                                                    |
| --------------- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 2026-10-20 | Section 4: Front-end test specification | Describe simple user flows through the currently implemented screens and state the sample-data limitations. |
| 2026-11-03 | Section 2.1: Market API tests | Specify mocked and live integration tests for every Market HTTP interface. |
| 2026-11-03 | Section 4: Market E2E tests | Use deterministic market fixtures so the app-to-API flows run without MySQL or Alpaca credentials. |

---

## 2. Back-end Test Specification: APIs

### 2.1. Locations of Back-end Tests and Instructions to Run Them

#### 2.1.1. Tests

| **Interface** | **Describe Group Location, No Mocks** | **Describe Group Location, With Mocks** | **Mocked Component** |
|---|---|---|---|
| `GET /api/market/stocks` | `backend/tests/no-mock/market.test.ts` — `Unmocked API: GET /api/market/stocks` | `backend/tests/mock/marketApi.test.ts` — `Mocked API: GET /api/market/stocks` | Alpaca quote and history provider |
| `GET /api/market/stocks/:symbol` | `backend/tests/no-mock/market.test.ts` — `Unmocked API: GET /api/market/stocks/:symbol` | `backend/tests/mock/marketApi.test.ts` — `Mocked API: GET /api/market/stocks/:symbol` | Alpaca quote and history provider |
| `GET /api/market/stocks/:symbol/history` | `backend/tests/no-mock/market.test.ts` — `Unmocked API: GET /api/market/stocks/:symbol/history` | `backend/tests/mock/marketApi.test.ts` — `Mocked API: GET /api/market/stocks/:symbol/history` | Alpaca history provider |

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

**Contract note:** The supplied Market specification says `GET /api/market/stocks` returns `503` when the provider is unavailable. Current implementation instead returns `200` with each affected stock marked `status: "unavailable"` and a `null` price. The mocked API test records current implementation behavior; update the route and corresponding test if the specification's overall `503` is mandatory.

### 2.2. GitHub Actions Configuration Location

`~/.github/workflows/backend-tests.yml`

### 2.3. Jest Coverage Report Screenshots With Mocks

_(Placeholder for Jest coverage screenshot with mocks enabled)_

### 2.4. Jest Coverage Report Screenshots Without Mocks

_(Placeholder for Jest coverage screenshot without mocks)_

---

## 3. Tests of Non-Functional Requirements

### 3.1. Test Locations in Git

| **Non-Functional Requirement**  | **Location in Git**                            |
| ------------------------------- | ---------------------------------------------- |
| **Performance (Response Time)** | [`backend/tests/nfr/response_time.test.js`](#) |
| **Chat Data Security**          | [`backend/tests/nfr/chat_security.test.js`](#) |

### 3.2. Test Verification and Logs

- **Performance (Response Time)**
  - **Verification:** This test suite simulates multiple concurrent API calls using Jest along with a load-testing utility to mimic real-world user behavior. The focus is on key endpoints such as user login and study group search to ensure that each call completes within the target response time of 2 seconds under normal load. The test logs capture metrics such as average response time, maximum response time, and error rates. These logs are then analyzed to identify any performance bottlenecks, ensuring the system can handle expected traffic without degradation in user experience.
  - **Log Output**
    ```
    [Placeholder for response time test logs]
    ```

- **Chat Data Security**
  - **Verification:** ...
  - **Log Output**
    ```
    [Placeholder for chat security test logs]
    ```

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

Market: load all six supported stocks from the HTTP API, view each name, quote, chart, and local logo, and search by company name or ticker including the no-results state.

### Test Scenarios

| Test | Scenario and expected behavior |
|---|---|
| `allSixStocksShowNamesQuotesChartsAndLocalLogos` | The app receives six fixture stocks and renders each company name, quote timestamps, chart, and bundled logo. |
| `searchMatchesTickerAndCompanyAndShowsAnEmptyStateForNoMatch` | Search finds a stock by company and ticker, shows the empty state for an unknown query, and displays the expected fixture quote. |

### Execution Results

The test runner writes Android instrumentation reports to the paths below. Record the latest result after running the suite on an emulator.

HTML report: `frontend/app/build/reports/androidTests/connected/debug/index.html`.
Raw test results: `frontend/app/build/outputs/androidTest-results/connected/debug/`.
