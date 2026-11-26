# Non-functional requirement tests

## Run from Android Studio's terminal (PowerShell)

```powershell
cd C:\Users\jerry\CPEN321_project\cpen321-TradeOff\backend
npm.cmd run test:nfr
```

No running backend, emulator, database, cloud account, or real API key is needed.
Dependencies and the generated Prisma client must already be installed, as they
are in this checkout. On a fresh checkout use the project's normal dependency
installation instructions and run `npx.cmd prisma generate` first.

Run one requirement:

```powershell
npm.cmd run test:nfr -- responseTime
npm.cmd run test:nfr -- concurrentLoad
npm.cmd run test:nfr -- providerRecovery
```

The command returns a nonzero exit code when any requirement fails. Do not change
an assertion to match a defect just to obtain a green result. Individual runs
overwrite the combined results file with that run's results.

## Plan, inputs, and test oracles

| Requirement | Exercise | Required result |
| --- | --- | --- |
| NFR-1 | Warm all three route shapes, then send 100 sequential requests to each (300 total). | Each endpoint's p95 is strictly below 1000 ms; all responses are 200 and contain usable data. |
| NFR-2 | Warm cache, start 10 simultaneous workers, each making 30 sequential requests; rotate evenly over the three endpoints (300 total). | 100 samples per endpoint; each p95 below 1000 ms; zero unsuccessful or invalid responses. |
| NFR-3 | Retrieve data, expire quote and history caches, fail the provider, restore it, and advance time through the retry boundary. Also exercise a cache with no previously retrieved data. | Retained data is stale; missing data is unavailable; no early retry; next eligible request recovers on the same service instance. Trading and portfolio valuation must reject stale or unavailable prices and accept recovered prices. |

Endpoints: `/api/market/stocks`, `/api/market/stocks/AAPL`, and
`/api/market/stocks/AAPL/history`. AAPL is the representative supported symbol for
parameterized routes; this suite does not separately load-test all 100 symbols.

NFR-1/2 use real timers and `performance.now()` around Supertest HTTP requests.
Warm-up responses are excluded. Every measured response is recorded. p95 uses
the nearest-rank definition: sort N durations, take rank ceil(0.95 * N).
Response bodies are also checked so a fast error response cannot pass.
Coverage instrumentation is disabled to avoid distorting timing. Suites run
serially so the load and sequential tests do not compete against each other.

The real market router, cache, data processing, and serialization run. Only the
external quote/history provider is stubbed, including its batch interfaces. If
the cache expires mid-run, refresh still uses the stub, never Alpaca.

NFR-3 uses Jest fake time only for deterministic cache/retry transitions. It calls
the real service and price consumers; the singleton's getStock method delegates
to the real service under test. Database access is replaced with an empty mock,
so unexpected database use fails. This is component integration and fault
injection, not an Android end-to-end test.

## Lecture basis

Source: `Lectures_export.zip`, supplied with the request.

- W7 L2 Testing I, slide/page 14: test plans, cases, suites, and expected-behavior oracles.
- W7 L2 Testing I, slides/pages 40-45: control failures in external dependencies using test doubles.
- W8 L1 Testing II, slides/pages 24-26: measure performance against stated requirements; measurement instrumentation has overhead.
- W8 L1 Testing II, slide/page 28: performance/load/stress testing are non-functional testing activities.
- W8 L1 Testing II, slide/page 49: check success and error scenarios and preserve regression tests for defects.
- W8 L1 Testing II, slide/page 65: automate non-functional tests and mock external components to reproduce errors.

The 1000 ms threshold and workloads come from the supplied NFRs, not from the
lecture slides. Ten users is a fixed load test, not a stress-to-failure test.

## Evidence to save

The terminal shows named tests, failures, and per-endpoint timing summaries.
Generated files in `backend/reports/nfr/` are ignored by Git:

- `NFR-1.json`: all 300 sequential timings and endpoint summaries.
- `NFR-2.json`: all 300 concurrent timings and endpoint summaries.
- `results.json`: Jest pass/fail report, including failure details.

The timing reports record Node version, OS, CPU, timestamp, workload, and scope.
Keep those files and a terminal screenshot for your test report. A failed
timeout may stop collection early; do not present an older timing file as that
failed run's evidence. Rerunning a timing suite overwrites its timing report.

## Stale-price regression fix

The original stale-portfolio test exposed a defect: portfolio valuation accepted
a retained positive price even when its status was stale. With 2 shares at a
retained price of 100, it reported a holdings value of 200 during the outage.

src/market/valuation.ts now accepts only prices with status "ok", matching
trading's freshness check. Stale or unavailable prices map to null, so held
positions raise PriceUnavailableError and the portfolio API returns 503 rather
than reporting a current valuation. Once the provider recovers, fresh prices
can be used again. The original NFR assertion is unchanged.

## Limits

These are repeatable local backend tests with controlled dependencies. They do
not establish cloud capacity, real Alpaca reliability/latency, HTTPS/proxy or
mobile-network latency, screen rendering time, or database performance. Timing
varies with machine load; close expensive background work and record the actual
environment. Backend NFR tests do not replace the course's GUI end-to-end tests.
