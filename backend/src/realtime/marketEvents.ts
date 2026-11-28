import type { Server, IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocket, WebSocketServer } from 'ws';
import { marketService, MarketService } from '../market/service';
import { REFRESH_INTERVAL_MS } from '../market/catalog';

/** Public market snapshots, using the same cache and provider limits as the REST API. */
export function attachMarketEvents(
    server: Server,
    service: Pick<MarketService, 'getStocks'> = marketService,
    intervalMs = REFRESH_INTERVAL_MS,
): () => void {
    const sockets = new WebSocketServer({ noServer: true, maxPayload: 1024 });
    const alive = new Set<WebSocket>();
    let loading = false;
    let stopped = false;

    const broadcast = (message: string): void => {
        if (stopped) return;
        for (const client of sockets.clients) {
            if (client.readyState !== WebSocket.OPEN) continue;
            if (client.bufferedAmount > 1024 * 1024) { client.terminate(); continue; }
            client.send(message, error => { if (error) client.terminate(); });
        }
    };
    const publish = async (): Promise<void> => {
        if (stopped || loading || sockets.clients.size === 0) return;
        loading = true;
        try {
            const stocks = await service.getStocks();
            broadcast(JSON.stringify({ type: 'market.snapshot', stocks, checkedAt: new Date().toISOString() }));
        } catch {
            broadcast(JSON.stringify({ type: 'market.error', message: 'Market data is temporarily unavailable.' }));
        } finally {
            loading = false;
        }
    };
    const refresh = setInterval(() => { void publish(); }, intervalMs);
    refresh.unref();
    const heartbeat = setInterval(() => {
        for (const client of sockets.clients) {
            if (!alive.delete(client)) { client.terminate(); continue; }
            client.ping();
        }
    }, 30_000);
    heartbeat.unref();

    const onUpgrade = (request: IncomingMessage, socket: Duplex, head: Buffer): void => {
        if (request.url?.split('?')[0] !== '/api/market/events') return;
        if (stopped) { socket.destroy(); return; }
        sockets.handleUpgrade(request, socket, head, client => {
            alive.add(client);
            client.on('pong', () => alive.add(client));
            client.on('error', () => client.terminate());
            client.on('close', () => alive.delete(client));
            // Registration happens first; one in-flight load serves all new subscribers.
            void publish();
        });
    };
    server.on('upgrade', onUpgrade);
    return () => {
        if (stopped) return;
        stopped = true;
        clearInterval(refresh);
        clearInterval(heartbeat);
        server.off('upgrade', onUpgrade);
        for (const client of sockets.clients) client.terminate();
        sockets.close();
    };
}
