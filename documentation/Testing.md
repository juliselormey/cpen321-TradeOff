# Testing

For complete one-time database, Google, Alpaca and Android setup, follow
[Local E2E Setup](Local_E2E_Setup.md). No Docker or special server is required.
The normal backend runs with `npm run dev`; then use one of the documented test runners.

## Commands for the five formal use cases

Complete [Local E2E Setup](Local_E2E_Setup.md) once: running MySQL/MariaDB,
isolated test database with migrations, real Google/Alpaca credentials in
`backend/.env`, `E2E_ENABLED=true`, and matching Android/backend URLs.
Each session: keep `npm run dev` running from `backend`, open Pixel 9 API 36,
sign in through Google and return to Market. Run commands below from the repository root.

| Formal case | Test class | PowerShell command |
| --- | --- | --- |
| 3.5.1 Browse Stocks | `BrowseStocksE2ETest` | `.\scripts\run-browse-stocks-e2e-tests.ps1` |
| 3.5.2 Place Market Buy | `TradingE2ETest` | `.\scripts\run-market-buy-e2e-tests.ps1` |
| 3.5.3 View Portfolio | `PortfolioE2ETest` | `.\scripts\run-view-portfolio-e2e-tests.ps1` |
| 3.5.4 Send Friend Request | `SendFriendRequestE2ETest` | `.\scripts\run-send-friend-request-e2e-tests.ps1` |
| 3.5.5 View Leaderboard | `LeaderboardE2ETest` | `.\scripts\run-view-leaderboard-e2e-tests.ps1` |
| All five, sequentially | All above | `.\scripts\run-frontend-e2e-tests.ps1` |

The all-tests runner explicitly starts and waits for Browse → Buy → Portfolio →
Leaderboard → Send Friend Request. Each suite is independent; the ordering does
not supply data to later tests. It attempts every suite even after a test failure
and exits unsuccessfully if any suite fails. It uses the existing server and login.
On macOS/Linux, run `bash scripts/run-frontend-e2e-tests.sh`; individual scripts
also have `.sh` equivalents. Old special-server suites are excluded.

Normal Gradle output remains unchanged. The combined runner preserves each fresh
HTML report at `frontend/app/build/reports/e2e-all/<run-id>/<test-class>/index.html`.
The default `reports/androidTests/connected/debug/index.html` is overwritten per
suite and represents only the last invocation. A failure before reporting appears
in terminal output, not as a copied stale report. Google sessions can expire;
HTTP 401 requires signing in again before rerunning the failed suite.

## View Portfolio E2E — formal specification 3.5.3

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/PortfolioE2ETest.kt`.
Test: `viewPortfolioShowsEmptyAndFundedStatesWithoutMutatingAccount`.
This is one independent UI acceptance test using the actual backend, SQL,
Google-authenticated session, and live Alpaca data. It uses the same disposable
backend and authenticated account-reset endpoint described in the Market Buy
setup below. Running `TradingE2ETest` first is unnecessary.

Start the normal backend with E2E setup enabled as described below, configure both Android backend URLs
to that server, install the app, and sign in through Google. Then run
`./scripts/run-view-portfolio-e2e-tests.ps1` on Windows or
`bash scripts/run-view-portfolio-e2e-tests.sh` on Bash.
Each run resets the current testing account's trading data before and after
the scenario. All account preparation is automatic.

| Formal scenario | Automated actions and expected checks |
| --- | --- |
| Preconditions | Require Google sign-in and the disposable backend; reset only the current user's cash, positions, trades and orders. |
| Success 1–2 | Start on Market, open Portfolio, wait for the actual cash balance to load. |
| Success 8 | With no positions, verify the empty-holdings message, $10,000 cash/total, zero profit/loss, and unchanged server account data. |
| Automated preparation | Through the trading UI, buy two MSFT shares and create a pending BUY for 100 shares at $1, reserving $100. The app uses actual production order APIs. These are setup actions performed outside the viewing assertions. |
| Success 1–4 | Reopen Portfolio; check cash, MSFT symbol, quantity, average cost, actual current price and current holding value against server evidence. |
| Success 5 | Check total value including reserved cash, unrealized profit/loss and return percentage, allocation values/percentages and the allocation chart. |
| Success 6–7 | Scroll to Pending Orders; check BUY, ticker, quantity, limit price and PENDING status without cancelling the order. |
| Postconditions | Compare cash, holdings, pending orders and transaction history before/after viewing, then reload Portfolio and compare again. |
| Failure 3a–3a3 | Excluded from this no-mock acceptance test. Deterministically failing only external stock prices requires controlling Alpaca or substituting provider responses. The PDF permits omitting backend failures that require mocking. The normal reload path is exercised without injecting an outage. |

The test intentionally expects the formal specification's reserved-cash total.
It does not adjust expected results to the app's current calculation.
### Verified execution

After correcting reserved-cash handling in the Android response model and
portfolio calculations, the Portfolio test was rerun on Pixel 9 and passed:
**1 test, 0 failures**, Gradle `BUILD SUCCESSFUL in 44s`. That run preceded the
switch to the normal-server fixture endpoint. The updated normal server has
passed startup/health/authentication checks, but a complete Buy+Portfolio run
with the final setup instructions has not yet been recorded. No cloud run is claimed.

#### Historical failing run (before the reserved-cash fix)

Executed on Pixel 9 API 36 on 2026-11-18 (Vancouver), with real Google sign-in,
the local SQL-backed server and live Alpaca. Test/app compilation passed.
The final scenario ran in 21.053 seconds. Result: **1 failed, 0 errors,
0 skipped**, with three display assertions reported together after the
remaining viewing/reload and account-immutability checks executed.

The failures identify a product mismatch with formal success step 5:

| Check | Expected from actual server data | Actual UI |
| --- | --- | --- |
| Portfolio total including the $100 reservation | $10,000.00 | $9,900.00 |
| MSFT allocation | 10.2% | 10.3% |
| Cash allocation including the reservation | $8,982.99 | $8,882.99 |

The empty-account checks, real holding/price display, profit/loss checks,
pending-order display, and account-immutability checks reached the final
assertion successfully. The UI's `totalPortfolioValue` and allocation calculations
in `PortfolioScreen.kt` omitted reserved cash, while the production portfolio API
included it. This mismatch has since been fixed; the test expectation was preserved.

Instrumentation log excerpt:

```text
PortfolioE2ETest.viewPortfolioShowsEmptyAndFundedStatesWithoutMutatingAccount
Success 5: total value must include reserved cash: expected $10,000.00; actual $9,900.00
Success 5: MSFT allocation: expected 10.2%; actual 10.3%
Success 5: Cash allocation must include reserved cash: expected $8,982.99; actual $8,882.99
tests=1 failures=1 errors=0 skipped=0
```

Earlier attempts were blocked by the emulator's Package Manager returning
`Broken pipe` and an expired login returning HTTP 401. The emulator was cold
booted and the tester signed in again before the recorded scenario run.
Failure 3a remains excluded as explained above. No physical-device run is
claimed. Instrumentation reports are generated in the standard
`frontend/app/build/reports/androidTests/connected/debug/` directory.

## Place Market Buy E2E — formal specification 3.5.2

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/TradingE2ETest.kt`,
test `marketBuyValidatesInputAndFundsThenPersistsPurchase`.
This suite uses the actual app, production API routes, a disposable SQL database,
and live Alpaca prices. It starts with a Google-authenticated user on Market.
It supplies no replacement Google token, market provider, or API responses.

### Setup and execution

Follow [Local E2E Setup](Local_E2E_Setup.md), including the isolated database and
`E2E_ENABLED=true` in the normal `backend/.env`. Start `npm run dev`, sign in on
Market, then run the combined or individual test runner. The reset endpoint is
`POST /__e2e/account/reset`. It deletes trading data for the authenticated user;
it does not restore previous trades. No second account or special host is needed.

### Traceability and expected results

| Formal scenario | Automated actions and checks |
| --- | --- |
| Preconditions | Require an existing Google session, reset the current account automatically, verify empty positions/history/orders and $10,000 cash. |
| Success 1–4 | From Market search MSFT, open its Trade screen, choose Market Order and wait until live quote and account data allow BUY. |
| Failure 8a–8a1 | Enter zero shares, check the validation message and disabled BUY, verify server account and history are unchanged. |
| Failure 8b–8b1 | Enter one billion shares, check the insufficient-funds message including available cash, disabled BUY, and unchanged server state. |
| Failure 8b2 cancellation | Return to Market without buying, verify no transaction or balance change, then reopen the Trade screen. |
| Failure 8a2 / 8b2 correction | Enter one share and verify BUY becomes enabled. |
| Success 5–8 | Submit BUY through the UI. The production server obtains the actual Alpaca price and validates funds and quantity. |
| Success 9–10 and postconditions | Check the fill message, exactly one MSFT BUY transaction, positive execution price, correct cost, reduced cash, one share with matching average cost, and no pending orders. Reopen the Trade screen and read backend state again to verify persistence. |
| Failure 7a–7a2 | Excluded from this no-mock acceptance suite. A deterministic Alpaca price-fetch failure requires controlling the external provider. The course PDF permits omitting backend failures that require mocking. Network disconnection would affect all API requests and would not specifically demonstrate this price-fetch failure. |

### Verified execution

Executed on 2026-11-18 on Pixel 9 API 36 (Android 16), using the real local
backend on port 3004, a disposable SQL database, live Alpaca IEX data, and an
interactive Google sign-in before running. The Android app and instrumentation
APK compiled successfully. Backend production compilation passed.

Result: **1 passed, 0 failed, 0 errors, 0 skipped**.
The latest test case took 16.727 seconds. Instrumentation reported:

```xml
<testsuite name="com.example.cpen321application.e2e.TradingE2ETest"
           tests="1" failures="0" errors="0" skipped="0" time="17.281">
  <testcase name="marketBuyValidatesInputAndFundsThenPersistsPurchase"
            classname="com.example.cpen321application.e2e.TradingE2ETest"
            time="16.727" />
</testsuite>
```

The initial development run failed because the selector matched both the MSFT
search input and the stock card. The final test selects the card by its company
name and passed with that correction. A later run exposed a missing wait for
the live price after reopening Trade. Both visits now wait up to 30 seconds
for BUY to become enabled. The corrected test passed on rerun.
Physical-device execution is pending. Failure 7a–7a2 remains excluded for the
reason specified in the traceability table. A subsequent merge from
`username-test-expand` fixed the stray `h` that previously blocked backend
typechecking. Backend typechecking was rerun after the merge.
Reports are generated under `frontend/app/build/reports/androidTests/connected/debug/`
and `frontend/app/build/outputs/androidTest-results/connected/debug/`.

## View Leaderboard E2E — formal specification 3.5.5

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LeaderboardE2ETest.kt`.
Test: `viewLeaderboardRanksAcceptedFriendsByValueAndReturnWithoutMutatingAccounts`.
Follow [Local E2E Setup](Local_E2E_Setup.md), start the normal backend with
`npm run dev`, sign in through Google and return to Market. From the root run
`./scripts/run-view-leaderboard-e2e-tests.ps1` (or its `.sh` counterpart).
The combined `run-frontend-e2e-tests` runner selects all five formal-use-case suites.

Preparation is independent: reset this account's trading state, remove its existing
friendships, first verify self-only state, then create eight real database users.
Six are accepted friends (both relationship directions), one has a pending request,
and one is unrelated. One accepted friend holds MSFT, valued using actual market
prices. Cash/starting balances deliberately produce different value/return orders.
These are persisted fixtures, not mocked responses or substituted market prices.
No second Google login, hardcoded tester account or preceding feature test is needed.
Requires the same opt-in test-database guard as account reset. It erases the current
test account's friendships; they are not restored. Cleanup removes the generated
users (cascading their accounts/positions/relationships) and resets current-user
trading state. Original profile, Google session and Favorites are preserved.

| Formal scenario | Automated actions and checks |
| --- | --- |
| Preconditions | Require the existing Google session; prepare isolated real database state automatically. |
| Success 1–2 | Navigate from Market to Social and select Leaderboard. |
| Success 3 | Read the actual leaderboard API; require exactly self plus six accepted friends, excluding pending/unrelated users. One holding exercises the live valuation path. |
| Success 4–5 | Verify default portfolio-value ranking, every displayed rank, name, USD value and return against the backend data. Select See all to include entries outside the top five. |
| Success 6–8 | Select Return %, require an order different from value order, and verify every reordered row. Switch back and exercise a normal Refresh. |
| Success 9 | Before creating participants, require only the signed-in user's entry and the no-friends message. |
| Postconditions | Compare real database snapshots before/after self-only viewing and before/after populated viewing, sorting and refreshing: all participant balances, positions, trades, orders and friendships remain unchanged. |
| Failure 3a–3a3 | Excluded: a deterministic server/provider outage would require fault injection or replacement behavior. No mock is introduced. Normal Refresh is exercised but is not claimed as failure-retry coverage. |

Verified on Pixel 9 API 36 with the normal local backend, authenticated Google
session and real SQL/market data. JUnit XML: **1 test, 0 failures, 0 errors,
0 skipped**, suite time **10.941 seconds**. Gradle reported `BUILD SUCCESSFUL in 24s`.
Backend TypeScript checking and Android instrumentation compilation passed.
The separate backend fixture-safeguard suite passed all six checks; its mocked
database is restricted to backend tests, not used by this Android E2E test.
No cloud or physical-device execution is claimed.

## Send Friend Request E2E — formal specification 3.5.4

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/SendFriendRequestE2ETest.kt`.
Test: `sendFriendRequestValidatesIdsAndRelationshipsThenPersistsOnePendingRequest`.
The empty `SocialE2ETest.kt` placeholder was replaced. Follow
[Local E2E Setup](Local_E2E_Setup.md), start the ordinary `npm run dev` backend,
sign in through Google and return to Market. Run
`./scripts/run-send-friend-request-e2e-tests.ps1` or its `.sh` counterpart.
The combined runner now selects all five formal-use-case suites.

Preparation resets only the signed-in test account's trading state, replaces its
friendships, and reuses the guarded real database participant setup described for
Leaderboard. The setup now returns generated participant IDs to the test; it
does not supply replacement production responses. Existing accepted friends,
one pending outgoing request and an unrelated target are independent of old data.
No second Google login or manually prepared recipient is needed. Cleanup removes
the temporary participants/relationships and resets test trading state. Old
friendships/trading data are not restored; use only the disposable test database.

| Formal scenario | Automated actions/checks |
| --- | --- |
| Preconditions | Require actual Google authentication and matching backend URLs; create independent participants automatically. |
| Success 1–3 | Navigate Market → Social → Friends; verify existing accepted friends and outgoing pending requests are displayed. |
| Success 4–6 | Open Add friend, enter the unrelated participant's actual generated UUID and submit through the UI. |
| Success 7–8 | Production API verifies the target and creates the relationship. Inspect actual DB evidence: exactly one additional relationship, PENDING status, correct sender/recipient, and no change to previous relationships. |
| Success 9 / postcondition | Check sent confirmation and target in outgoing requests; reopen Friends and verify the same request remains pending exactly once through both UI and real API/DB evidence. |
| Failure 7a–7a2 | Submit a random valid-format UUID with no user; verify the not-found error and unchanged database, then correct the ID in the same form and submit successfully. |
| Failure 7b–7b2 | Attempt both an already accepted friend and an already pending recipient; verify the relationship-conflict error, no extra records, and Cancel back to Friends. |
| Failure 7c–7c2 | Submit the current user's actual ID; verify self-add rejection and unchanged database, then cancel. |
| Additional invariants | All participants' balances, positions, trades and orders are unchanged by accepted/rejected friend-request actions. |

All listed formal success/failure scenarios are covered; no failure omission or
injected provider/server error is needed for this use case. The self-ID rejection
is performed by the real UI; the conflict/not-found failures use the actual API.

Verified on Pixel 9 API 36 with the normal local backend: **1 test, 0 failures,
0 errors, 0 skipped**, JUnit suite time **14.676 seconds**, Gradle
`BUILD SUCCESSFUL in 26s`. Android compilation and backend TypeScript checks
passed; the separate backend fixture-safeguard suite passed all six checks.
No cloud or physical-device execution is claimed.

## 1. Change History

| **Change Date** | **Modified Sections**                   | **Rationale**                                                                                                    |
| --------------- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 2026-10-20 | Section 4: Front-end test specification | Describe simple user flows through the currently implemented screens and state the sample-data limitations. |
| 2026-11-03 | Section 2.1: Market API tests | Specify mocked and live integration tests for every Market HTTP interface. |
| 2026-11-03 | Section 4: Market E2E tests | Use deterministic market fixtures so the app-to-API flows run without MySQL or Alpaca credentials. |
| 2026-11-04 | Section 2.1: Renumbered Use Cases | Renumber use cases following the removal of delete account (UC-05 to UC-19). |

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

## 4. Browse Stocks E2E — formal specification 3.5.1

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/BrowseStocksE2ETest.kt`.
Test: `browseStocksShowsCatalogQuotesAndLogosWithoutChangingAccountData`.
It replaces the deterministic `MarketFlowTest` and the empty `MarketE2ETest.kt`.
No fixed prices or substitute provider is used. The normal backend and real
Alpaca credentials must be running; follow [Local E2E Setup](Local_E2E_Setup.md).
Start signed in on Market as required by the course's feature-test convention.
The product supports signed-out browsing too; guest behavior is not covered here.

Run `./scripts/run-browse-stocks-e2e-tests.ps1` or its `.sh` counterpart.
The existing `run-market-e2e-tests.ps1` is now an alias to this real-backend test;
it no longer rewrites URLs or requires port 3003. The common E2E runner includes
Browse Stocks, Buy, Portfolio and Leaderboard.

| Formal scenario | Automated actions/checks |
| --- | --- |
| Preconditions / 1 | Recreate the real app on Market with the existing Google session and matching Market/account backend URLs. No account reset or pre-existing portfolio data is needed. |
| 2 | Read the actual market API, require 100 distinct catalog entries, Alpaca source and valid quote statuses, with at least one positive real quote; wait for the app's complete catalog. |
| 3 | For every stock check ticker, company name, logo accessibility node, displayed USD price and signed percentage change. Available prices must be positive. Do not assert fixed prices that can move during browsing. |
| 4 | Scroll the unfiltered LazyColumn to every one of the 100 stock cards and display its price/change; no search shortcut or stock selection. |
| Postconditions | Compare real database snapshots and saved Favorites before/after browsing. The test performs no reset, trades, friend changes or star clicks. |
| 2a–2a3 | No forced provider outage or recovery: deterministic failure requires controlling Alpaca. If missing/stale data occurs naturally, check its visible indicator and absent-price placeholder. This does not prove retention of a prior quote or recovery; those remain excluded E2E scenarios and are covered separately by backend tests. |

Logo checks verify the rendered logo node exists; they do not perform pixel-level
brand recognition. Quote checks verify real API availability and UI value format,
not equality to one old quote captured before a long scrolling run.

### Execution results

Verified on Pixel 9 API 36 against the normal local backend and real Alpaca data:
**1 test, 0 failures, 0 errors, 0 skipped**, suite time **135.174 seconds**.
Gradle reported `BUILD SUCCESSFUL in 2m 26s`. All 100 stock cards were visited;
the final account/Favorites immutability assertions passed. Android compilation,
backend TypeScript checking and all six backend fixture-safeguard checks passed.
No forced-outage/recovery, guest-mode, physical-device or cloud coverage is claimed.
Reports: `frontend/app/build/reports/androidTests/connected/debug/index.html`
and `frontend/app/build/outputs/androidTest-results/connected/debug/`.
