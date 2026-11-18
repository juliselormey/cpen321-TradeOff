import http from 'node:http';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { WebSocket } from 'ws';
import { createApp } from '../../src/app';
import { attachFriendEvents } from '../../src/realtime/friendEvents';
import { db } from '../../src/prisma/db';

jest.mock('../../src/prisma/db', () => ({ db: {
    user: { findUnique: jest.fn() },
    friendship: { create: jest.fn(), findFirst: jest.fn(), updateMany: jest.fn(), deleteMany: jest.fn() },
} }));

const sender = '00000000-0000-4000-8000-000000000001';
const recipient = '00000000-0000-4000-8000-000000000002';
const outsider = '00000000-0000-4000-8000-000000000003';
let server: http.Server;
let stop: () => void;
let url: string;
let clients: WebSocket[];
const token = (id: string): string => jwt.sign({}, process.env.JWT_SECRET!, { subject: id, expiresIn: '1h' });

beforeEach(async () => {
    jest.resetAllMocks();
    (db.user.findUnique as jest.Mock).mockImplementation(async ({ where }) => ({ id: where.id, isBanned: false }));
    (db.friendship.findFirst as jest.Mock).mockResolvedValue({ senderId: sender, recipientId: recipient });
    clients = [];
    server = http.createServer(createApp());
    stop = attachFriendEvents(server);
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Missing test server port');
    url = `ws://127.0.0.1:${address.port}/api/friends/events`;
});

afterEach(async () => {
    const closed = clients.filter(client => client.readyState !== WebSocket.CLOSED)
        .map(client => new Promise<void>(resolve => client.once('close', () => resolve())));
    for (const client of clients) client.terminate();
    stop();
    await Promise.all(closed);
    await new Promise<void>(resolve => server.close(() => resolve()));
});

async function connect(id: string): Promise<{ socket: WebSocket; messages: string[] }> {
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token(id)}` } });
    clients.push(socket);
    const messages: string[] = [];
    socket.on('message', data => messages.push(String(data)));
    await new Promise<void>((resolve, reject) => {
        socket.once('error', reject);
        socket.once('message', data => {
            expect(JSON.parse(String(data))).toEqual({ type: 'friends.ready' });
            resolve();
        });
    });
    return { socket, messages };
}

test.each([
    ['post', '/api/friends/requests', { userId: recipient }, sender],
    ['post', '/api/friends/requests/request-id/accept', {}, recipient],
    ['delete', '/api/friends/requests/request-id', {}, sender],
    ['delete', '/api/friends/requests/request-id', {}, recipient],
    ['delete', '/api/friends/request-id', {}, sender],
] as const)('%s %s pushes only to both participants after success', async (method, path, body, actor) => {
    (db.friendship.create as jest.Mock).mockResolvedValue({ id: 'request-id', senderId: sender, recipientId: recipient });
    (db.friendship.updateMany as jest.Mock).mockResolvedValue({ count: 1 });
    (db.friendship.deleteMany as jest.Mock).mockResolvedValue({ count: 1 });
    const a = await connect(sender);
    const b = await connect(recipient);
    const unrelated = await connect(outsider);
    const changed = [a, b].map(client => new Promise<void>(resolve => {
        client.socket.once('message', data => {
            expect(JSON.parse(String(data))).toEqual({ type: 'friends.changed' });
            resolve();
        });
    }));
    const response = await request(server)[method](path).set('Authorization', `Bearer ${token(actor)}`).send(body);
    expect(response.status).toBe(method === 'post' && path.endsWith('/requests') ? 201 : 204);
    await Promise.all(changed);
    // A ping/pong establishes a wire roundtrip after the HTTP response, avoiding a sleep.
    await new Promise<void>(resolve => { unrelated.socket.once('pong', () => resolve()); unrelated.socket.ping(); });
    expect(unrelated.messages).toEqual([JSON.stringify({ type: 'friends.ready' })]);
});

test('a failed conditional mutation sends no change notification', async () => {
    const client = await connect(sender);
    (db.friendship.deleteMany as jest.Mock).mockResolvedValue({ count: 0 });
    await request(server).delete('/api/friends/request-id').set('Authorization', `Bearer ${token(sender)}`).expect(404);
    await new Promise<void>(resolve => { client.socket.once('pong', () => resolve()); client.socket.ping(); });
    expect(client.messages).toHaveLength(1);
});

test.each([undefined, 'Bearer invalid'])('rejects missing or invalid authentication: %s', async authorization => {
    const client = new WebSocket(url, { headers: authorization ? { Authorization: authorization } : {} });
    clients.push(client);
    client.on('error', () => {});
    const status = await new Promise<number | undefined>(resolve => {
        client.once('unexpected-response', (_req, res) => { res.resume(); resolve(res.statusCode); client.terminate(); });
    });
    expect(status).toBe(401);
});

test('rejects banned accounts', async () => {
    (db.user.findUnique as jest.Mock).mockResolvedValue({ id: sender, isBanned: true });
    const client = new WebSocket(url, { headers: { Authorization: `Bearer ${token(sender)}` } });
    clients.push(client);
    client.on('error', () => {});
    const status = await new Promise<number | undefined>(resolve => {
        client.once('unexpected-response', (_req, res) => { res.resume(); resolve(res.statusCode); client.terminate(); });
    });
    expect(status).toBe(403);
});

test('reconnecting receives ready so Android can reload changes missed offline', async () => {
    const first = await connect(sender);
    await new Promise<void>(resolve => { first.socket.once('close', () => resolve()); first.socket.close(); });
    const second = await connect(sender);
    expect(second.messages).toEqual([JSON.stringify({ type: 'friends.ready' })]);
});
