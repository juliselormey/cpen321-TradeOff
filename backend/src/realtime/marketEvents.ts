import type { Server, IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocket, WebSocketServer } from 'ws';
import { marketService, MarketService } from '../market/service';

/** Public market data only. Favorites and account data never enter this stream. */
export function attachMarketEvents(server: Server, service: MarketService = marketService): () => void {
  const sockets = new WebSocketServer({ noServer: true, maxPayload: 1024 });
  const alive = new Set<WebSocket>();
  let revision = 0;
  let pending: NodeJS.Immediate | undefined;
  const snapshot = (): string => JSON.stringify({
    type: 'market.snapshot', revision, checkedAt: new Date().toISOString(),
    stocks: service.getCachedStocks(),
  });
  const send = (client: WebSocket, message: string): void => {
    if (client.readyState !== WebSocket.OPEN) return;
    // Drop slow clients instead of queuing unlimited full snapshots.
    if (client.bufferedAmount > 1024 * 1024) { client.terminate(); return; }
    try { client.send(message, error => { if (error) client.terminate(); }); }
    catch { client.terminate(); }
  };
  const unsubscribe = service.subscribe(() => {
    revision++;
    if (pending) return;
    pending = setImmediate(() => {
      pending = undefined;
      if (!sockets.clients.size) return;
      const message = snapshot();
      for (const client of sockets.clients) send(client, message);
    });
  });
  const heartbeat = setInterval(() => {
    for (const client of sockets.clients) {
      if (!alive.delete(client)) { client.terminate(); continue; }
      client.ping();
    }
  }, 30_000);
  heartbeat.unref();
  const onUpgrade = (request: IncomingMessage, socket: Duplex, head: Buffer): void => {
    if (request.url?.split('?')[0] !== '/api/market/events') return;
    sockets.handleUpgrade(request, socket, head, client => {
      alive.add(client);
      client.on('pong', () => alive.add(client));
      client.on('error', () => client.terminate());
      client.on('close', () => alive.delete(client));
      send(client, snapshot());
    });
  };
  server.on('upgrade', onUpgrade);
  return () => {
    unsubscribe();
    clearInterval(heartbeat);
    if (pending) clearImmediate(pending);
    server.off('upgrade', onUpgrade);
    for (const client of sockets.clients) client.terminate();
    sockets.close();
  };
}
