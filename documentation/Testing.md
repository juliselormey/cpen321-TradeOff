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

## 4. Portfolio Frontend End-to-End Testing

### Test Suite Location

The Portfolio E2E suite is in `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/PortfolioFlowTest.kt`. `RealBackendTest.kt` provides signed-in setup and test-account reset. The tests use the real app, backend routes, and SQL database. `backend/scripts/e2e-server.ts` starts an isolated backend for these tests.

### How to Run E2E Tests

1. From a PowerShell terminal at the repository root, run the one-time setup:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\setup-portfolio-e2e.ps1
   ```

   Enter your MySQL administrator name/password when prompted. The script creates an isolated database and DB user, writes `backend/.env.e2e`, installs backend dependencies if needed, and creates the schema. Then edit that file and replace the Google client ID, Alpaca keys, and `E2E_USER_EMAIL` placeholders. Do not commit this file.
2. In `frontend/local.properties`, set:

   ```properties
   API_BASE_URL=http://10.0.2.2:3002/
   MARKET_API_BASE_URL=http://10.0.2.2:3002/
   ```

3. Start the test backend from the repository root:

   ```powershell
   cd backend
   $env:DOTENV_CONFIG_PATH = '.env.e2e'
   npx.cmd tsx scripts/e2e-server.ts
   ```

   Leave the terminal open. Start the app on an Android emulator and sign in with the Google account named in `E2E_USER_EMAIL`.
4. Open a second terminal at the repository root and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\run-frontend-e2e-tests.ps1
   ```

The tests reset the selected account before and after each scenario. Use only the disposable database; the account is reset to $10,000. The emulator uses `10.0.2.2` to reach your computer.

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
| `quoteRefreshFailureLabelsRetainedPortfolioValuesStale` | Failed quote refresh should mark retained values stale; this test checks behavior the app may not yet implement. |

### Execution Results

The Android app and instrumentation tests compiled. The full Portfolio suite has not completed successfully. An earlier run exposed a missing stale-value warning on Portfolio. Rerun the suite and record current results before submission.

HTML report: `frontend/app/build/reports/androidTests/connected/debug/index.html`.
Raw test results: `frontend/app/build/outputs/androidTest-results/connected/debug/`.
