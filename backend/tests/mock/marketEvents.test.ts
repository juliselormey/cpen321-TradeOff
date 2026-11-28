import http from 'node:http';
import { WebSocket } from 'ws';
import { attachMarketEvents } from '../../src/realtime/marketEvents';
import type { MarketStock } from '../../src/market/types';

let server: http.Server;
let stop: () => void;
let clients: WebSocket[];
let url: string;
let price: string;
let unavailable: boolean;
const getStocks = jest.fn(async () => {
    if (unavailable) throw new Error('Provider internals must not reach the client');
    return [{ ticker: 'AAPL', currentPrice: price, status: 'ok' }] as MarketStock[];
});

beforeEach(async () => {
    clients = [];
    price = '100';
    unavailable = false;
    getStocks.mockClear();
    server = http.createServer();
    stop = attachMarketEvents(server, { getStocks }, 30);
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

function nextMessage(client: WebSocket): Promise<{ type: string; stocks?: MarketStock[]; message?: string }> {
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

test('public subscriber receives an initial snapshot and later price updates without HTTP polling', async () => {
    const client = connect();
    expect(await nextMessage(client)).toMatchObject({ type: 'market.snapshot', stocks: [{ currentPrice: '100' }] });
    price = '110';
    expect(await nextMessage(client)).toMatchObject({ type: 'market.snapshot', stocks: [{ currentPrice: '110' }] });
});

test('a provider failure is sanitized and the next snapshot recovers', async () => {
    unavailable = true;
    const client = connect();
    expect(await nextMessage(client)).toEqual({ type: 'market.error', message: 'Market data is temporarily unavailable.' });
    unavailable = false;
    expect((await nextMessage(client)).type).toBe('market.snapshot');
});

test('shutdown closes subscribers and removes the upgrade handler', async () => {
    const client = connect();
    await nextMessage(client);
    const closed = new Promise<void>(resolve => client.once('close', () => resolve()));
    stop();
    await closed;
    expect(server.listenerCount('upgrade')).toBe(0);
});

test('does not fetch provider data when nobody is subscribed', async () => {
    await new Promise(resolve => setTimeout(resolve, 75));
    expect(getStocks).not.toHaveBeenCalled();
});
