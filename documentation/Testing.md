# Testing

## 1. Change History

| **Change Date** | **Modified Sections**                   | **Rationale**                                                                                                    |
| --------------- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 2026-10-20 | Section 4: Front-end test specification | Describe simple user flows through the currently implemented screens and state the sample-data limitations. |

---

## 2. Back-end Test Specification: APIs

### 2.1. Locations of Back-end Tests and Instructions to Run Them

#### 2.1.1. Tests

| **Interface**                 | **Describe Group Location, No Mocks**               | **Describe Group Location, With Mocks**          | **Mocked Components**              |
| ----------------------------- | --------------------------------------------------- | ------------------------------------------------ | ---------------------------------- |
| **POST /user/login**          | [`tests/no-mock/authenticationLogin.test.js#L1`](#) | [`tests/mock/authenticationLogin.test.js#L1`](#) | Google Authentication API, User DB |
| **POST /study-groups/create** | ...                                                 | ...                                              | Study Group DB                     |
| ...                           | ...                                                 | ...                                              | ...                                |
| ...                           | ...                                                 | ...                                              | ...                                |

#### 2.1.2. Commit Hash Where Tests Run

e606decfba06f9cc5e03b99ea210799f50084b73

#### 2.1.3. Explanation on How to Run the Tests

1. **Clone the Repository**:
  - Open your terminal and run:
    ```
    git clone https://github.com/example/your-project.git
    ```

2. **...**

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

## 4. Front-end Test Specification

### 4.1. Test Location and Setup

**Test directory:** `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/`

**Setup:** Open the app on the emulator, sign in beforehand, and start on Market. Perform each flow through the app's screens rather than opening individual UI components in isolation.

**Scope:** These plans follow the currently available frontend user journeys from start to finish. Market, Portfolio, and Social use sample data, and Trade is a placeholder. These flows do not yet verify an end-to-end connection to the backend and database.

### 4.2. User Flows and Expected Results

#### Flow 1: Search for a Stock and Open Trade

| Scenario step | Test action and expected result |
| --- | --- |
| 1. The user opens Market. | Verify that the search field and sample stock list appear. |
| 2. The user searches for a stock. | Enter `AAPL`. Verify that Apple appears and nonmatching stock rows disappear. |
| 3. The user selects the stock. | Tap an Apple row. Verify that the Trade screen displays `Trade: AAPL`. |
| 4. The user returns to Market. | Use Android Back. Verify that Market reappears and the search field can be used again. |
| 2a. The search has no matching stocks. | Enter `ZZZZ_NOT_A_STOCK`. Verify that no stock rows appear. Clear the field and verify that the sample list returns. |

Buying and selling are not tested because the current Trade screen only displays the selected ticker.

#### Flow 2: View Portfolio Information

| Scenario step | Test action and expected result |
| --- | --- |
| 1. The user navigates to Portfolio. | From Market, tap Portfolio in the bottom navigation. Verify that the Portfolio heading and MOCK DATA label appear. |
| 2. The user reviews the summary. | Verify that total portfolio value, available cash, and profit/loss show the local sample values. |
| 3. The user reviews investments and activity. | Scroll through Asset Allocation, Holdings, and Recent Activity. Verify that the sample holdings and transaction details appear. |
| 4. The user leaves Portfolio. | Tap Back to home. Verify that Market appears. |

This flow checks viewing existing sample information; it does not check real account balances or live price updates.

#### Flow 3: View Rankings and Use the Friends Screen

| Scenario step | Test action and expected result |
| --- | --- |
| 1. The user navigates to Social. | From Market, tap Social. Verify that the Leaderboard and Friends tabs appear. |
| 2. The user reviews the leaderboard. | Verify that sample traders and ranks appear. Change the sort mode and check that the list follows Total value or Return % as selected. |
| 3. The user expands the list. | Tap See all and verify that all seven sample traders can be viewed. Tap Show top 5 and verify that the list is limited to five. |
| 4. The user views friends. | Tap Friends. Verify that sample friends appear. Change the name sort mode and check A-Z and Z-A ordering. |
| 5. The user opens Add friend. | Tap Add friend. Verify that the dialog and ID/username field appear. |
| 5a. The input is empty. | Verify that the dialog's Add friend button is disabled. |
| 6. The user enters an ID or username. | Enter a non-blank value, then tap Add friend. Verify that the preview-only message appears. No actual friend request is expected. |
| 7. The user returns to Market. | Close the dialog and tap Back to home. Verify that Market appears. |

### 4.3. Test Results and Logs

- **Status:** Planned; these flows have not been implemented as automated tests or executed for this report.
- **Existing automated test:** `ExampleInstrumentedTest.kt` checks only the app's package name, not these user flows.
- **Results and logs:** Not available yet. Record actual pass/fail results and logs after running the tests.
## 4. Portfolio Frontend End-to-End Testing

### Test Suite Location

The Portfolio E2E suite is in `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/PortfolioFlowTest.kt`. `RealBackendTest.kt` provides the signed-in test setup. These tests use the real app, API routes, and SQL database. The isolated test server is `backend/scripts/e2e-server.ts`.

### How to Run E2E Tests

1. Create an empty MySQL database named `tradeoff_e2e_<name>` and add `backend/.env.e2e` with `DATABASE_URL`, `GOOGLE_CLIENT_ID`, `JWT_SECRET`, Alpaca keys, and `E2E_USER_EMAIL`. Keep this file private; Git ignores it.
2. Set these URLs in `frontend/local.properties`:

   ```properties
   API_BASE_URL=http://10.0.2.2:3002/
   MARKET_API_BASE_URL=http://10.0.2.2:3002/
   ```

3. In a terminal, prepare and start the backend:

   ```powershell
   cd backend
   npm.cmd ci
   $env:DOTENV_CONFIG_PATH = '.env.e2e'
   npx.cmd prisma generate
   npx.cmd prisma db push
   npx.cmd tsx scripts/e2e-server.ts
   ```

   Leave this terminal open. Start the app on an Android emulator and sign in with the Google account listed in `E2E_USER_EMAIL`.
4. In a second terminal at the repository root, run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\run-frontend-e2e-tests.ps1
   ```

The tests reset the selected account before and after each case. Use only the disposable database: test data is reset to $10,000. `10.0.2.2` is the emulator address for the host computer.

### Tested Use Cases

Portfolio: view an empty account, open a stock from Holdings and trade it, check balances/trade activity/allocation, and verify portfolio polling and error behavior.

### Test Scenarios

| Test | Scenario and expected behavior |
|---|---|
| `emptyPortfolioShowsStartingCashWithoutInventedHoldings` | Empty account displays $10,000 cash/value and no invented positions. |
| `holdingPurchaseUpdatesSharesCashActivityAndAllocation` | Open a holding, buy a share, return to Portfolio; shares, cash, activity, and allocation reflect the order. |
| `externalTradeRefreshesOpenPortfolioWithoutNavigation` | A trade made while Portfolio stays open appears after polling. |
| `priceMovementRevaluesPortfolioWithoutChangingSharesOrCash` | Changed quote updates value, P/L, and allocation while shares and cash remain constant. |
| `portfolioFailureShowsErrorRetainsBalanceAndRecovers` | Portfolio request failure shows an error, retains cash, and recovers. |
| `quoteRefreshFailureLabelsRetainedPortfolioValuesStale` | Failed quote refresh should mark retained values stale; this checks behavior the app may not yet implement. |

### Execution Results

The Android app and instrumentation tests compiled. The full Portfolio suite has not completed successfully. An earlier run exposed a missing stale-value warning on Portfolio. Rerun the suite and record the current results before submission.

HTML report: `frontend/app/build/reports/androidTests/connected/debug/index.html`.
Raw test results: `frontend/app/build/outputs/androidTest-results/connected/debug/`.
