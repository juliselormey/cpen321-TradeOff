import type { Server } from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';
import { authenticateBearer, AuthenticationError } from '../middleware/auth';

const subscribers = new Map<string, Set<WebSocket>>();
const HEARTBEAT_MS = 30_000;
const MAX_BUFFERED_BYTES = 64 * 1024;
const changedMessage = JSON.stringify({ type: 'friends.changed' });

/** Call only after a successful committed mutation, for its two participants. */
export function publishFriendsChanged(userIds: readonly string[]): void {
    for (const id of new Set(userIds)) {
        for (const socket of subscribers.get(id) ?? []) {
            if (socket.readyState !== WebSocket.OPEN) continue;
            if (socket.bufferedAmount > MAX_BUFFERED_BYTES) { socket.terminate(); continue; }
            // Push delivery must not turn a committed mutation into an HTTP failure.
            try { socket.send(changedMessage, error => { if (error) socket.terminate(); }); }
            catch { socket.terminate(); }
        }
    }
}

/** Authentication uses the same bearer token and account checks as the REST API. */
export function attachFriendEvents(server: Server): () => void {
    const sockets = new WebSocketServer({ noServer: true, maxPayload: 1024 });
    const alive = new Set<WebSocket>();
    const heartbeat = setInterval(() => {
        for (const socket of sockets.clients) {
            if (!alive.delete(socket)) { socket.terminate(); continue; }
            socket.ping();
        }
    }, HEARTBEAT_MS);
    heartbeat.unref();

    let stopped = false;
    // A named handler lets shutdown detach the listener and close all live clients.
    const onUpgrade = (request: import('node:http').IncomingMessage,
        socket: import('node:stream').Duplex, head: Buffer): void => {
        if (request.url?.split('?')[0] !== '/api/friends/events') return;
        const ignoreSocketError = (): void => {};
        socket.on('error', ignoreSocketError);
        void authenticateBearer(request.headers.authorization).then(session => {
            if (socket.destroyed) return;
            if (stopped) { socket.destroy(); return; }
            if (session.expiresAt !== undefined && session.expiresAt <= Date.now()) {
                throw new AuthenticationError(401, 'Expired token');
            }
            sockets.handleUpgrade(request, socket, head, client => {
                socket.removeListener('error', ignoreSocketError);
                const group = subscribers.get(session.userId) ?? new Set<WebSocket>();
                subscribers.set(session.userId, group);
                group.add(client);
                alive.add(client);
                client.on('pong', () => alive.add(client));
                client.on('error', () => client.terminate());
                // Check expiration on a bounded interval, avoiding overflowing setTimeout
                // for long-lived tokens. REST refreshes continue to check account status.
                const expiration = setInterval(() => {
                    if (session.expiresAt !== undefined && Date.now() >= session.expiresAt) {
                        client.close(1008, 'Session expired');
                    }
                }, 1000);
                expiration.unref();
                client.on('close', () => {
                    clearInterval(expiration);
                    alive.delete(client);
                    group.delete(client);
                    if (group.size === 0) subscribers.delete(session.userId);
                });
                // Ready is sent after registration so the client's fresh HTTP snapshot
                // cannot miss a mutation in the subscribe/snapshot transition.
                client.send(JSON.stringify({ type: 'friends.ready' }));
            });
        }).catch((error: unknown) => {
            const status = error instanceof AuthenticationError ? error.status : 503;
            if (!socket.destroyed) socket.end(`HTTP/1.1 ${status} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
        });
    };
    server.on('upgrade', onUpgrade);
    return () => {
        stopped = true;
        clearInterval(heartbeat);
        server.off('upgrade', onUpgrade);
        for (const client of sockets.clients) client.terminate();
        sockets.close();
    };
}
