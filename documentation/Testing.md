# Testing

For complete one-time database, Google, Alpaca and Android setup, follow
[Local E2E Setup](Local_E2E_Setup.md). No Docker or special server is required.
The normal backend runs with `npm run dev`; then use one of the documented test runners.

## Commands for E2E suites

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
A persistent real provider failure is not hidden; unsupported forced-failure
branches remain documented rather than being reported as covered.

## Market live updates — 2026-11-25

The normal backend now refreshes shared quotes on a 4-second loop and daily
history on a 60-second loop, each waiting after completion. Android Market uses
`/api/market/events` snapshots instead of recurring HTTP polling. Favorites and
account data are unchanged. Provider retry/cooldown takes precedence over timers.

- `backend/tests/mock/marketLive.test.ts`: automatic cold start for all 100 stocks,
  independent quote/history timing, no overlapping slow requests, rate-limit
  cooldown, shutdown cleanup, immediate cache-only socket snapshots, broadcast to
  multiple clients, reconnect resynchronization, stale-price broadcasts and
  coexistence with the friend WebSocket handler. Uses local test sockets with
  mocked Alpaca/database, not the E2E application server.
- Android `MarketStreamTest`: pushed prices without HTTP polling, older-frame
  rejection, socket cancellation, stale prices after disconnect, one REST recovery,
  reconnect with a restarted revision counter and state recovery.
- Verification: all backend mock suites **302 passed, 1 TODO**; Android Market
  unit tests and debug APK build passed. An already-running real local backend
  delivered successive WebSocket snapshots containing **100/100 available quotes**.
  NFR tests **8/8** passed. Two recovery assertions now wait for the provider's
  actual `retryAt`, independently of the shorter successful-quote TTL.
  Browse Stocks E2E was attempted but stopped during account-evidence preparation
  with **HTTP 401 (expired login)**; this is not a passing E2E run. The other four
  Android E2E suites have not been rerun for this change.

From `backend/`: `npx.cmd jest tests/mock --runInBand --coverage=false`.
From `frontend/`: `./gradlew.bat :app:testDebugUnitTest --tests '*Market*Test' :app:assembleDebug`.
The current all-tests runner includes the ten selected-feature cases and two extra
formal cases; run it with
the normal backend, disposable test database, emulator and real Google login.

## Market feature E2E specifications

Market is tested as four independently runnable use cases. The three new cases
below are test specifications, not additions to Requirements and Design section
3.5. The existing formal Browse Stocks specification remains unchanged.

Feature and individual commands are listed in [Commands for E2E suites](#commands-for-e2e-suites).
Source classes are in `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/`.

Browse Stocks runs last because it scrolls all 100 cards. Matching `.sh` runners
are available via `bash`. Individual reports use Gradle's default connected-test
report; the Market runner preserves each fresh report under
`frontend/app/build/reports/e2e-market/<run-id>/<class>/index.html`.
The all-tests runner now includes all twelve implemented use cases, with Browse
last overall. The Trading cases are described below; Both Portfolio use cases are described below.
All aggregate runners share `scripts/lib/run-e2e-classes.ps1` / `.sh`, continue
after a failed suite, and return failure if any suite fails. Normal Gradle output
is retained. No runner starts a server, installs a database or signs in.

Prerequisites: normal full backend started with `npm run dev`, real Alpaca keys,
MySQL/MariaDB with the disposable E2E schema and `E2E_ENABLED=true`, Pixel 9,
both Android URLs pointing to that backend, and an existing real Google login.
See [Local E2E Setup](Local_E2E_Setup.md). Run suites serially, without concurrent
account activity. Account evidence requests share the app's TLS trust/session
configuration, including the Browse test.

### Search Stocks

Actor: authenticated Trader, starting on Market. Precondition: real catalog
loaded. Postcondition: no account or favorites changes.

| Scenario | Automated checks |
| --- | --- |
| Success 1–3 | Open Market / Stocks; require the 100-stock catalog. |
| Success 4–11 | Search full ticker `AAPL`, company `Microsoft`, partial ticker `MSF`, partial name `Micro`, and lower/upper case. Compare result count with actual catalog matching; scroll to every expected result and assert name/ticker, reject visible nonmatches. |
| Failure 4a–4a2 | Enter a string absent from the catalog; assert zero result cards and `No stocks match your search.` |
| Failure recovery | Replace the unknown string with `MSFT`; correct results return. |
| Success 12–13 | Clear the query; the full 100-stock list returns. |
| Postcondition | Compare real account snapshot and favorites before/after; do not click Favorites or any star. |

### View Stock Details

Actor: authenticated Trader. The test selects two actually available stocks with
real history; it does not inject a fixed price or assume one price across calls.
Postcondition: no account or favorites changes.

| Scenario | Automated checks |
| --- | --- |
| Success 1–6 | Select a stock from Stocks; verify trade screen title, company, bundled logo, positive USD price, previous-close change label, and default 1M chart. |
| Success 7–11 | Select 1W, 1D, then 1M. Require selected controls and a chart or the legitimate insufficient-data message for sparse 1D history. |
| Success 12–14 | Tap an older chart point and drag to a later point; inspect displayed price and different real timestamps. |
| Success 15 | Wait for a different server quote retrieval timestamp delivered to the detail card; assert the period and inspected point remain selected. A market-price change is not required during closed hours. |
| Success 16–17 | Return to Market, select a different stock, verify its title/company/logo rather than the previous stock. |
| Postcondition | No BUY/SELL actions; compare account and favorites evidence before/after. |

The retrieval timestamp is exposed through a custom test semantics property,
not a test-only replacement price or visible implementation detail. A successful
snapshot receipt is checked; this does not assert that every possible external
market event or every rendered chart pixel is correct.

### Manage Favorite Stocks

Actor: authenticated Trader. Preparation reads the caller's original favorites,
clears that caller's set through ordinary APIs, then reloads the real UI. Cleanup
restores the original set and verifies it. Trading data is not reset or modified.

| Scenario | Automated checks |
| --- | --- |
| Success 1–8 | Add AAPL through its star in Stocks; star changes, card remains in Stocks, and AAPL appears in Favorites. Verify saved API data. |
| Success 9–11 | Add MSFT, show both favorites, wait for a new live Market snapshot, and assert the tab/list and saved set remain intact. |
| Success 12–13 | Remove AAPL from Stocks; Favorites contains only MSFT. |
| Success 14–15 | Recreate the activity and reopen Favorites; MSFT remains saved. |
| Success 16–18 | Remove MSFT; assert the empty-favorites message. Return to Stocks and verify both unselected stars remain available. |
| Postcondition | Restore original favorites, compare account evidence and saved original set. |

Empty favorites is a normal outcome, not a failure scenario. Search within
Favorites is not part of these tests. No multi-account isolation claim is made.

### Failure coverage boundaries

Only Search has a deliberately reproducible failure in these new specifications:
a user query with no matches. Details and Favorites do not accept an arbitrary
invalid stock through their current UI. Provider/DB errors, WebSocket interruption,
missing history and failed favorite writes are not manufactured by frontend mocks.
Guest authorization flows are not included in this logged-in suite. A naturally
sparse 1D chart is handled, but guaranteed sparse-history coverage is not claimed.
The formal Browse failure scenarios retain their existing documented exclusions;
these new specifications do not waive the PDF's formal-scenario requirement.

### Market suite verification

Verified on Pixel 9 API 36 with the existing normal local backend and renewed
real Google login: Search Stocks passed, View Stock Details passed, and Browse
Stocks passed in the four-case Market runner. Favorites initially failed because
the test tried to scroll directly to a header already outside the LazyColumn's
composed area. It now scrolls through the list's `performScrollToNode` action;
the individual Manage Favorites rerun passed, including original-set restoration
and unchanged account evidence. All four current tests have passing runs; a new
single aggregate run after that test-only correction was not repeated.

The initial aggregate folder retains the failed Favorites attempt alongside the
three passing cases. The passing retry report is preserved in a separate
`e2e-market/<run-id>-favorites-retry/ManageFavoritesE2ETest/` folder. A previous
preparation attempt returned HTTP 401 before renewed login; it is not counted as
a functional pass. The other four legacy Trading/Portfolio/Social suites were not
rerun for this Market-only change. PowerShell parsing, Bash syntax checks and
Android instrumentation compilation passed.


## Trading feature E2E specifications

Four independent cases use the normal full backend, real Alpaca quotes and real
SQL state. Start signed in on Market. Run only on the disposable E2E database:
each suite resets the caller's orders, trades, holdings and cash before/after;
previous trading data is not restored. Favorites are preserved. No other user is
reset. Setup buys and pending orders use the actual trading UI, not fixed API
responses. Shared preparation is in `TradingE2ESupport.kt`; the existing Market
Buy class retains its own setup and now shares the app's TLS-aware evidence client.

Feature and individual commands are listed in [Commands for E2E suites](#commands-for-e2e-suites).
Source classes are in `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/`.

Matching `.sh` scripts run through `bash`. The Trading runner contains only these
four classes and preserves reports under
`frontend/app/build/reports/e2e-trading/<run-id>/<class>/index.html`. It continues
after a failed case and fails overall if any case fails. The Market runner remains
limited to Search, Details, Favorites, Browse; the complete runner now has twelve
implemented cases, with Browse last. No runner starts another backend or signs in.

### Place Market Buy

The existing formal 3.5.2 case is preserved and extended: blank/zero quantity,
insufficient cash, correction/cancellation, one actual purchase, exact cash/share
accounting using the recorded execution price, no pending order, Portfolio's
Bought record, and reopening Trade with persisted state. No provider outage is
manufactured. The formal unavailable-price branch remains an explicit coverage
limitation, not a blanket course-approved exception.

### Place Limit Order

| Scenario | Checks |
| --- | --- |
| Invalid quantity / price | Blank and zero quantity or limit disable BUY; zero values show validation. No account mutation. |
| Insufficient cash | A valid but unaffordable reserve shows the balance blocker and disables BUY. |
| Pending BUY | Two MSFT shares at $1 create one LIMIT BUY/PENDING. Cash falls by $2, reserved cash is $2, holdings/history stay empty. Verify order fields and persisted PENDING status. |
| Portfolio and reopening | Pending card exists on Trade and Portfolio; cash plus reserve remains $10,000. Recreate Activity and verify the same pending state. |
| Immediate fill | Cancel the preparation reserve via UI, choose a buy limit twice the actual observed quote, submit one share. Require FILLED, price at/below limit, exactly one BUY, correct balance/quantity, no pending order or leftover reserve, and persisted data. |

### Cancel Pending Order

| Scenario | Checks |
| --- | --- |
| BUY from Trade | Create an actual pending BUY through UI, cancel it, require CANCELLED in DB evidence, exact reserve return, no pending order, no holding or completed trade created. |
| Portfolio confirmation | The restored balance is shown and the cancelled BUY is absent. |
| SELL preparation | Buy two shares through UI and reserve one with a distant limit SELL. No sale proceeds/history change before execution. |
| SELL from Portfolio | Cancel its card there. Require CANCELLED, unchanged cash/owned shares/completed trades, and available quantity restored from one to two. |
| Reopening | Reopen Trade and Portfolio; no returning order or repeated refund. |

Cancellation evidence is read from the real `/__e2e/account/snapshot`, scoped
to the authenticated profile ID. It compares stored cash, positive positions,
completed trades, pending orders and reserved BUY cash without valuing holdings.
The existing DTO is reused only for test comparisons; no application API response
or quote is replaced. This avoids unrelated Portfolio HTTP 503 during state
checks after a successful cancellation. The UI still loads ordinary Portfolio
APIs and the test still clicks Cancel there; trading setup still requires real
quotes. Authentication failures and incorrect state are not ignored or retried
as successful results. Other Trading cases retain their existing snapshot logic.
The revised Cancel Pending Order case was rerun individually on Pixel 9 against
the real local backend: passed (BUILD SUCCESSFUL, approximately one minute).
No Alpaca outage or replacement response was injected for this verification.

### Sell Shares

| Scenario | Checks |
| --- | --- |
| Independent preparation | Buy four actual MSFT shares through UI. |
| Invalid / excess quantity | Blank/zero or five shares cannot be sold; errors/blockers are visible; account unchanged. |
| Partial market sale | Sell one; require exactly one SELL, actual positive execution price, correct proceeds and quantity falling to three, no pending order. |
| Invalid limit price | Blank/zero limit blocks SELL without mutation. |
| Pending limit sale | Reserve two shares at $1,000,000; owned quantity stays three, available becomes one, cash and completed trades unchanged; order is PENDING. |
| Already reserved shares | Attempt to market-sell two while only one is available; disabled SELL and unchanged state. |
| Immediate limit sale | Cancel the preparation reserve through UI; sell one with a $0.01 floor. Require actual execution at/above the floor and correct proceeds/quantity/history. |
| Sell remainder | Market-sell the final two; no positive holding or pending order, owned/available zero, exactly three completed SELL records overall. |
| Persistence | Portfolio shows Sold activity, no positive MSFT holding, and reopened Trade retains the final balance. |

These prices are legitimate submitted limit orders relative to the observed real
MSFT quote, not injected market values. Tests validate MSFT's quote fits the
$10,000 budget and pending limits before proceeding. External prices can still
change: unexpected actual execution is a real failure, not a mocked success.
Comparison tolerances account for decimal rounding; no fixed execution price is
assumed. No test forces Alpaca errors, a future market crossing, database failures,
network interruption, duplicate cancellation or concurrent fill/cancel races.
Those unsupported E2E branches remain explicitly outside coverage. UI-disabled
invalid inputs are verified as prevented submissions; they do not prove the
backend rejects manually forged requests (backend API tests cover that).

### Trading suite verification

Verified on Pixel 9 API 36 against the existing normal local backend, real
Google session, real SQL database and actual Alpaca prices. All four current
Trading cases have passing runs, including setup/cleanup:

- The first Market Buy attempt exposed a test timing issue: Portfolio's list
  exists before its asynchronous response. The test now waits for the actual
  Bought record rather than assuming list creation means loaded data.
- In the four-case aggregate run, Market Buy and Sell Shares passed. Limit Order
  and Cancel initially failed because Activity recreation restores the previous
  navigation route, not necessarily Market. Shared test navigation now explicitly
  returns through the real UI to Market before selecting a stock.
- Limit Order and Cancel then passed in a two-case retry. A fresh four-case run
  after that test-only navigation correction was not repeated. Original reports
  retain the initial failures; retry reports are separate under `e2e-trading/`.
- PowerShell syntax, Bash syntax and Android instrumentation compilation passed.
  The Market-only and complete twelve-case runners were not rerun for this
  Trading-only change. No production business logic or database schema changed.

Aggregate/retry reports: `frontend/app/build/reports/e2e-trading/<run-id>/<class>/index.html`.
Individual invocations use Gradle's default connected-test report, overwritten
by the next invocation. Both aggregate runs preserved their per-case HTML reports.


## Portfolio feature E2E specifications

Portfolio has two independent use cases. Both require the normal full backend,
real Google login, Alpaca and the disposable SQL test database. Each prepares and
cleans the current test account automatically; previous trades/positions are not
restored. No additional server, fake price or pre-populated tester account is used.

Feature and individual commands are listed in [Commands for E2E suites](#commands-for-e2e-suites).
Source classes are in `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/`.

Matching `.sh` commands run via `bash`. Portfolio-only reports are saved under
`frontend/app/build/reports/e2e-portfolio/<run-id>/<class>/index.html`.
The Market/Trading runners do not add Portfolio test classes; they may navigate
to Portfolio as a postcondition of their own use cases. The complete runner now
contains twelve cases: ten across the three selected main features plus the two
existing Social cases. Browse Stocks remains last overall.

### View Transaction History

The new class reuses normal trading setup/UI helpers, not another feature's test
result. Evidence comparisons use real DB snapshots rather than recalculating
market value. Its viewing phase starts only after preparation has finished.

| Scenario | Automated checks |
| --- | --- |
| Empty-history alternative | Reset the account, open Portfolio, verify the empty holdings area and absence of Recent Activity/history records. Viewing must not change persisted state. |
| Automated independent preparation | Through the real Trade UI buy three MSFT shares, sell one, create/cancel a $1 limit BUY, and leave another $1 limit BUY pending. No synthetic trade records or Alpaca responses are inserted. |
| Success: retrieve and inspect history | Open Portfolio and scroll to Recent Activity. Require exactly the two actual completed trades, SELL then BUY, with no duplicate IDs. Pending/cancelled orders must not become completed history entries. |
| Record fields | For each transaction verify ticker/direction, executed quantity, actual price, signed formatted total and displayed date against real stored evidence. Verify the two visible cards are ordered newest first. |
| Persistence | Recreate/reopen the application pages, require the same records/details without duplicates. |
| Read-only postconditions | Compare accounts, positions, trades, orders and friendships before/after empty viewing and before/after populated viewing/reopening. Favorites remain unchanged through shared teardown. |
| Cleanup | Reset the caller's disposable trading state even after assertion failure, subject to backend availability and valid login. |

The UI's existing Recent Activity list is used; no new history page is invented.
Transaction tags and header-order semantics expose the actual displayed records
without replacing any response. Backend history currently includes up to 100
completed trades; the UI displays dates, not exact times. Forced retrieval/network
failures, second-account isolation, pagination and history beyond 100 records are
not claimed. Empty history is a normal alternative, not an artificial failure.
The existing formal View Portfolio failure exclusions remain documented below.

### Portfolio verification

Verified on Pixel 9 API 36 against the existing normal local backend, real
Google session, SQL data and Alpaca: the new History case passed individually,
then the Portfolio-only runner completed **2/2 successfully**, View Portfolio
followed by View Transaction History. The successful aggregate reports are under
`frontend/app/build/reports/e2e-portfolio/20261125-230413-227/`. Setup and cleanup
completed in both cases. Existing Portfolio evidence requests now use the same
TLS-aware client as the app. PowerShell/Bash runner syntax and instrumentation
compilation passed. No provider/network failure was injected. Other feature
suites and the complete twelve-case runner were not rerun for this change.


## Historical integration and backend verification

After integrating `fb-nfr-thing`, Send Friend Request uses **usernames**, not UUID
friend IDs. The formal use case, test input and generated participant usernames
have been updated together. Firebase notifications and authenticated friend-event
WebSockets remain enabled; no notification/WS feature is replaced by test behavior.

Integration checks: backend TypeScript/production build passed; NFR **8/8**;
targeted Firebase/WebSocket/username/limit-worker/fixture tests **55/55**, followed
by the expanded fixture-safeguard suite **7/7**. Migration deployment was validated
in an isolated scratch database; only the additive notification-device migration
was then applied to the existing local test database. The duplicate trading-table
migration was excluded; see Local E2E Setup before deploying to another database.

Historical merge-check limitations: the mocked backend run reported **280 passed,
11 failed, 1 TODO**, with all failures in the legacy `tests/mock/market.test.ts`.
A comparison using the pre-merge Alpaca adapter also failed (13 cases), so this
merge initially did not have a clean legacy Market suite. The Market failures
have since been resolved as recorded below. The old Android `TradeStateTest`
still references removed APIs and prevents compiling the full unit-test suite.
The Settings instrumentation fake now includes the new notification API methods.
Android application/instrumentation compilation and installation passed. The
first merged friend-request E2E attempt stopped in setup with HTTP 401 (expired
login), before any scenario action. After the tester signed in again, the combined
runner completed **all five E2E tests successfully**, in order: Browse Stocks,
Market Buy, View Portfolio, View Leaderboard, Send Friend Request (usernames).
The corresponding Gradle invocations reported success in 3m 4s, 32s, 34s, 27s
and 36s. Saved HTML reports are under
`frontend/app/build/reports/e2e-all/20261118-215113-763/<test-class>/index.html`.
This is local Pixel 9 API 36 verification using the normal merged backend.
Real Firebase push delivery is not verified by these E2E/fixture checks.

### Market backend regression fixes

After fixing the 11 Market failures, the complete mocked backend run reported
**16 suites passed, 292 tests passed, 0 failed, 1 TODO**. The NFR suite also passed
all **8 tests**; TypeScript checking and the production build passed.
The no-mock backend suite was not rerun as part of this fix.

- The single-symbol history fallback now uses the shared cache/refresh helper:
  TTLs, in-flight deduplication and provider-wide cooldown are respected.
- Single-stock requests without a quote return 503. History failures without any
  cached points return 503. Catalog lists, retained history/quotes and successfully
  empty history retain their intended responses.
- History responses missing the `bars` field are rejected as malformed; explicit
  empty/null bars remain valid. The internal batch representation still permits
  an explicitly absent symbol's history without inventing points.
- Snapshot tests expect the adapter's four-decimal percentage normalization.
- Generic HTTP failure tests expect the existing 8-second retry interval; rate-limit
  and authentication-specific longer delays are preserved.

Command (from `backend`):
`npm.cmd run test:api:mock -- --runInBand`; NFR: `npm.cmd run test:nfr`.

Current run commands are in [Commands for E2E suites](#commands-for-e2e-suites).

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
The combined `run-frontend-e2e-tests` runner includes this suite alongside the four Market cases and the other implemented use cases (twelve total).

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
The combined runner selects twelve implemented use cases, including the ten selected-feature use cases and the two extra Social formal cases.

Preparation resets only the signed-in test account's trading state, replaces its
friendships, and reuses the guarded real database participant setup described for
Leaderboard. The setup now returns generated participant IDs and usernames to the test; it
does not supply replacement production responses. Existing accepted friends,
one pending outgoing request and an unrelated target are independent of old data.
No second Google login or manually prepared recipient is needed. Cleanup removes
the temporary participants/relationships and resets test trading state. Old
friendships/trading data are not restored; use only the disposable test database.

| Formal scenario | Automated actions/checks |
| --- | --- |
| Preconditions | Require actual Google authentication and matching backend URLs; create independent participants automatically. |
| Success 1–3 | Navigate Market → Social → Friends; verify existing accepted friends and outgoing pending requests are displayed. |
| Success 4–6 | Open Add friend, enter the unrelated participant's actual generated username and submit through the UI. |
| Success 7–8 | Production API verifies the target and creates the relationship. Inspect actual DB evidence: exactly one additional relationship, PENDING status, correct sender/recipient, and no change to previous relationships. |
| Success 9 / postcondition | Check sent confirmation and target in outgoing requests; reopen Friends and verify the same request remains pending exactly once through both UI and real API/DB evidence. |
| Failure 7a–7a2 | Submit a random valid-format username with no user; verify the not-found error and unchanged database, then correct the username in the same form and submit successfully. |
| Failure 7b–7b2 | Attempt both an already accepted friend and an already pending recipient; verify the relationship-conflict error, no extra records, and Cancel back to Friends. |
| Failure 7c–7c2 | Submit the current user's actual username; verify self-add rejection and unchanged database, then cancel. |
| Additional invariants | All participants' balances, positions, trades and orders are unchanged by accepted/rejected friend-request actions. |

All listed formal success/failure scenarios are covered; no failure omission or
injected provider/server error is needed for this use case. The self-username rejection
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
| 2026-11-25 | E2E command guide, feature grouping and test specifications | Group ten cases under Market/Trading/Portfolio, retain two extra formal Social cases, document per-case/feature/all commands, cleanup and verified coverage limits. |
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

## 4. Browse Stocks E2E — formal specification 3.5.1

Suite: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/BrowseStocksE2ETest.kt`.
Test: `browseStocksShowsCatalogQuotesAndLogosWithoutChangingAccountData`.
It replaces the deterministic `MarketFlowTest` and the empty `MarketE2ETest.kt`.
No fixed prices or substitute provider is used. The normal backend and real
Alpaca credentials must be running; follow [Local E2E Setup](Local_E2E_Setup.md).
Start signed in on Market as required by the course's feature-test convention.
The product supports signed-out browsing too; guest behavior is not covered here.

Run `./scripts/run-browse-stocks-e2e-tests.ps1` or its `.sh` counterpart.
The existing `run-market-e2e-tests.ps1` now runs all four real-backend Market cases with Browse last;
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
