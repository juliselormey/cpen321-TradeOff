import http from 'node:http';
import { WebSocket } from 'ws';
import { attachMarketEvents } from '../../src/realtime/marketEvents';
import type { MarketStock } from '../../src/market/types';

let server: http.Server;
let stop: () => void;
let clients: WebSocket[];
let url: string;
let stocks: MarketStock[];
let changed: (() => void) | undefined;
const getCachedStocks = jest.fn(() => stocks);
const unsubscribe = jest.fn();

beforeEach(async () => {
    clients = [];
    stocks = [{ ticker: 'AAPL', currentPrice: '100', status: 'ok' }] as MarketStock[];
    getCachedStocks.mockClear();
    unsubscribe.mockClear();
    server = http.createServer();
    stop = attachMarketEvents(server, {
        getCachedStocks,
        subscribe: listener => { changed = listener; return unsubscribe; },
    });
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('No server port');
    url = `ws://127.0.0.1:${address.port}/api/market/events`;
});

afterEach(async () => {
    for (const client of clients) client.terminate();
    stop();
    await new Promise<void>(resolve => server.close(() => resolve()));
});

function nextMessage(client: WebSocket): Promise<{ type: string; revision: number; stocks: MarketStock[] }> {
    return new Promise((resolve, reject) => {
        client.once('error', reject);
        client.once('message', data => { client.off('error', reject); resolve(JSON.parse(String(data))); });
    });
}
function connect(): WebSocket {
    const client = new WebSocket(url);
    clients.push(client);
    return client;
}

test('public subscriber receives cached data and later cache events without HTTP polling', async () => {
    const client = connect();
    expect(await nextMessage(client)).toMatchObject({ type: 'market.snapshot', revision: 0, stocks: [{ currentPrice: '100' }] });
    const update = nextMessage(client);
    stocks = [{ ...stocks[0]!, currentPrice: '110' }];
    changed?.();
    expect(await update).toMatchObject({ type: 'market.snapshot', revision: 1, stocks: [{ currentPrice: '110' }] });
});

test('provider failure retains stale cached prices and the next cache event recovers', async () => {
    stocks = [{ ...stocks[0]!, status: 'stale', error: { code: 'PROVIDER_UNAVAILABLE', message: 'Alpaca market data is temporarily unavailable.' } }];
    const client = connect();
    const failed = await nextMessage(client);
    expect(failed.stocks[0]).toMatchObject({ currentPrice: '100', status: 'stale' });
    const recovered = nextMessage(client);
    stocks = [{ ...stocks[0]!, status: 'ok', error: null }];
    changed?.();
    expect((await recovered).stocks[0]?.status).toBe('ok');
});

test('shutdown closes subscribers, unsubscribes once and removes the upgrade handler', async () => {
    const client = connect();
    await nextMessage(client);
    const closed = new Promise<void>(resolve => client.once('close', () => resolve()));
    stop();
    stop();
    await closed;
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(server.listenerCount('upgrade')).toBe(0);
});

test('does not serialize snapshots for cache changes when nobody is subscribed', async () => {
    changed?.();
    await new Promise<void>(resolve => setImmediate(resolve));
    expect(getCachedStocks).not.toHaveBeenCalled();
});
