import express from 'express';
import request from 'supertest';
import { performance } from 'node:perf_hooks';
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { cpus, platform, release } from 'node:os';
import { MarketService } from '../../src/market/service';
import { createMarketRouter } from '../../src/market/routes';
import { LIVE_SYMBOLS, STOCK_CATALOG } from '../../src/market/catalog';
import { MarketProviderError, PricePoint, ProviderQuote } from '../../src/market/types';

export const LIMIT_MS = 1000;
export const SYMBOL = LIVE_SYMBOLS[0]!;
export const ENDPOINTS = [
  '/api/market/stocks',
  `/api/market/stocks/${SYMBOL}`,
  `/api/market/stocks/${SYMBOL}/history`,
];

export function fixture() {
  const state = { fail: false, price: 100 };
  const quote = (): ProviderQuote => {
    if (state.fail) throw new MarketProviderError('PROVIDER_UNAVAILABLE', 'Simulated outage');
    return { price: state.price, change: 1, percentageChange: 1, quoteAsOf: new Date().toISOString() };
  };
  const history = (): PricePoint[] => {
    if (state.fail) throw new MarketProviderError('PROVIDER_UNAVAILABLE', 'Simulated outage');
    return [{ timestamp: new Date(Date.now() - 86400000).toISOString(), price: state.price - 1 }];
  };
  const provider = {
    fetchQuote: jest.fn(async (_symbol: string) => quote()),
    fetchHistory: jest.fn(async (_symbol: string) => history()),
    fetchQuotes: jest.fn(async (symbols: readonly string[]) =>
      Object.fromEntries(symbols.map(symbol => [symbol, quote()]))),
    fetchHistories: jest.fn(async (symbols: readonly string[]) =>
      Object.fromEntries(symbols.map(symbol => [symbol, history()]))),
  };
  const service = new MarketService(provider, () => Date.now());
  const app = express();
  app.use('/api/market', createMarketRouter(service));
  return { app, service, provider, state };
}

export interface Sample { endpoint: string; elapsedMs: number; status: number; valid: boolean }

export async function measure(app: express.Express, endpoint: string): Promise<Sample> {
  const start = performance.now();
  const response = await request(app).get(endpoint).timeout({ response: 5000, deadline: 10000 });
  const elapsedMs = performance.now() - start;
  // A fast error or empty response must not pass a performance test.
  const body = response.body;
  const valid = endpoint.endsWith('/history')
    ? body.status === 'ok' && body.symbol === SYMBOL && Array.isArray(body.points) && body.points.length > 0
    : endpoint.endsWith('/stocks')
      ? Array.isArray(body.stocks) && body.stocks.length === STOCK_CATALOG.length &&
        body.stocks.every((stock: { status: string; currentPrice: string }) => stock.status === 'ok' && Number(stock.currentPrice) > 0)
      : body.status === 'ok' && body.ticker === SYMBOL && Number(body.currentPrice) > 0;
  return { endpoint, elapsedMs, status: response.status, valid };
}

export function percentile95(samples: Sample[]): number {
  if (!samples.length) throw new Error('Cannot calculate p95 without samples');
  const sorted = samples.map(sample => sample.elapsedMs).sort((a, b) => a - b);
  return sorted[Math.ceil(sorted.length * 0.95) - 1]!;
}

export async function warm(app: express.Express): Promise<void> {
  for (const endpoint of ENDPOINTS) {
    const sample = await measure(app, endpoint);
    expect(sample.status).toBe(200);
    expect(sample.valid).toBe(true);
  }
}

export function report(requirement: string, samples: Sample[], users: number): void {
  const summaries = ENDPOINTS.map(endpoint => {
    const group = samples.filter(sample => sample.endpoint === endpoint);
    return { endpoint, requests: group.length, p95Ms: percentile95(group),
      maxMs: Math.max(...group.map(sample => sample.elapsedMs)),
      failures: group.filter(sample => sample.status !== 200 || !sample.valid).length };
  });
  const output = resolve(__dirname, '../../reports/nfr');
  mkdirSync(output, { recursive: true });
  writeFileSync(resolve(output, `${requirement}.json`), JSON.stringify({
    requirement, recordedAt: new Date().toISOString(), simulatedUsers: users, thresholdMs: LIMIT_MS,
    environment: { node: process.version, os: `${platform()} ${release()}`, cpu: cpus()[0]?.model },
    scope: 'Local Supertest HTTP, real router/cache/serialization, stubbed provider, no coverage instrumentation',
    summaries, samples,
  }, null, 2));
  for (const summary of summaries) console.log(
    `${requirement} ${summary.endpoint}: n=${summary.requests}, p95=${summary.p95Ms.toFixed(2)} ms, failures=${summary.failures}`);
}

export function assertPerformance(samples: Sample[]): void {
  expect(samples.every(sample => sample.status === 200 && sample.valid)).toBe(true);
  for (const endpoint of ENDPOINTS) {
    expect(percentile95(samples.filter(sample => sample.endpoint === endpoint))).toBeLessThan(LIMIT_MS);
  }
}
