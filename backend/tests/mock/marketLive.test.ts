import http from 'node:http';
import { WebSocket } from 'ws';
import { MarketService } from '../../src/market/service';
import { startMarketRefresh } from '../../src/market/refreshWorker';
import { attachMarketEvents } from '../../src/realtime/marketEvents';
import { attachFriendEvents } from '../../src/realtime/friendEvents';
import { LIVE_SYMBOLS } from '../../src/market/catalog';
import { MarketProviderError } from '../../src/market/types';

jest.mock('../../src/prisma/db', () => ({ db: {} }));

function fixture() {
  const fetchQuotes = jest.fn().mockImplementation(async (symbols: string[]) => Object.fromEntries(
    symbols.map(symbol => [symbol, { price: 200, change: 2, percentageChange: 1,
      quoteAsOf: new Date().toISOString() }]),
  ));
  const fetchHistories = jest.fn().mockImplementation(async (symbols: string[]) => Object.fromEntries(
    symbols.map(symbol => [symbol, [{ price: 198, timestamp: '2026-11-01T00:00:00Z' }]]),
  ));
  const service = new MarketService({ fetchQuotes, fetchHistories, fetchQuote: jest.fn(), fetchHistory: jest.fn() }, () => Date.now());
  return { service, fetchQuotes, fetchHistories };
}

describe('Background market refresh', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  test('warms all 100 stocks without a client, refreshes independently, and stops cleanly', async () => {
    const f = fixture();
    const stop = startMarketRefresh(f.service);
    try {
      await jest.advanceTimersByTimeAsync(0);
      expect(f.fetchQuotes).toHaveBeenCalledWith(LIVE_SYMBOLS);
      expect(f.fetchHistories).toHaveBeenCalledWith(LIVE_SYMBOLS);
      await jest.advanceTimersByTimeAsync(4_000);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
      expect(f.fetchHistories).toHaveBeenCalledTimes(1);
      await jest.advanceTimersByTimeAsync(56_000);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(16);
      expect(f.fetchHistories).toHaveBeenCalledTimes(2);
      stop();
      await jest.advanceTimersByTimeAsync(60_000);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(16);
      expect(f.fetchHistories).toHaveBeenCalledTimes(2);
    } finally { stop(); }
  });

  test('does not overlap a slow quote request and preserves provider rate-limit cooldown', async () => {
    const f = fixture();
    let release!: (value: Record<string, MarketProviderError>) => void;
    f.fetchQuotes.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    const stop = startMarketRefresh(f.service);
    try {
      await jest.advanceTimersByTimeAsync(20_000);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
      release(Object.fromEntries(LIVE_SYMBOLS.map(symbol => [symbol,
        new MarketProviderError('PROVIDER_RATE_LIMITED', 'Rate limited', 60_000)])));
      await jest.advanceTimersByTimeAsync(59_999);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
      await jest.advanceTimersByTimeAsync(1);
      expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
    } finally { stop(); }
  });
});

describe('Market WebSocket', () => {
  let server: http.Server;
  let stop: () => void;
  let stopFriends: () => void;
  let url: string;
  const clients: WebSocket[] = [];
  let f: ReturnType<typeof fixture>;

  beforeEach(async () => {
    f = fixture();
    server = http.createServer();
    // Both noServer handlers must coexist on the same HTTP server.
    stopFriends = attachFriendEvents(server);
    stop = attachMarketEvents(server, f.service);
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Missing server address');
    url = `ws://127.0.0.1:${address.port}/api/market/events`;
  });

  afterEach(async () => {
    const closed = clients.filter(client => client.readyState !== WebSocket.CLOSED)
      .map(client => new Promise<void>(resolve => client.once('close', () => resolve())));
    for (const client of clients.splice(0)) client.terminate();
    stop();
    stopFriends();
    await Promise.all(closed);
    await new Promise<void>(resolve => server.close(() => resolve()));
  });

  function next(socket: WebSocket): Promise<{ type: string; revision: number; stocks: { ticker: string; currentPrice: string | null; status: string }[] }> {
    return new Promise((resolve, reject) => {
      socket.once('message', data => resolve(JSON.parse(String(data))));
      socket.once('error', reject);
    });
  }

  async function connect() {
    const socket = new WebSocket(url);
    clients.push(socket);
    const message = await next(socket);
    return { socket, message };
  }

  test('connection reads cache without Alpaca calls; cache refresh broadcasts and reconnect resyncs', async () => {
    const a = await connect();
    expect(a.message.type).toBe('market.snapshot');
    expect(a.message.stocks).toHaveLength(100);
    expect(f.fetchQuotes).not.toHaveBeenCalled();
    const b = await connect();
    const updated = Promise.all([next(a.socket), next(b.socket)]);
    await Promise.all([f.service.refreshQuotes(), f.service.refreshHistories()]);
    const snapshots = await updated;
    expect(snapshots[0]?.stocks.every(stock => stock.currentPrice === '200')).toBe(true);
    expect(snapshots[0]).toEqual(snapshots[1]);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
    expect(f.fetchHistories).toHaveBeenCalledTimes(1);
    const reconnected = await connect();
    expect(reconnected.message.stocks).toEqual(snapshots[0]?.stocks);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
  });

  test('provider failure is pushed as stale while preserving the last good prices', async () => {
    await f.service.refreshQuotes();
    const a = await connect();
    expect(a.message.stocks[0]?.status).toBe('ok');
    // Use a separate controlled clock to expire the cache without real waiting.
    const clock = jest.spyOn(Date, 'now').mockReturnValue(Date.now() + 5_000);
    try {
      f.fetchQuotes.mockRejectedValueOnce(new MarketProviderError('PROVIDER_UNAVAILABLE', 'Offline'));
      const updated = next(a.socket);
      await f.service.refreshQuotes();
      const snapshot = await updated;
      expect(snapshot.stocks.every(stock => stock.status === 'stale' && stock.currentPrice === '200')).toBe(true);
    } finally { clock.mockRestore(); }
  });
});
