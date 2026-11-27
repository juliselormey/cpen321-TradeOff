import http from 'node:http';
import type { AddressInfo } from 'node:net';
import type { Duplex } from 'node:stream';
import jwt from 'jsonwebtoken';
import { WebSocket } from 'ws';
import { db } from '../../src/prisma/db';
import * as authMiddleware from '../../src/middleware/auth';
import { attachFriendEvents, publishFriendsChanged } from '../../src/realtime/friendEvents';

// Mocked: slow or broken clients, dead connections, expiring sessions, and an unavailable user lookup.
// The normal connect / notify / reconnect flow runs against MySQL in unmocked/friendEvents.test.ts.
jest.mock('../../src/prisma/db', () => require('../helpers/mockDb').mockDbModule());

const SECRET = 'friend-events-failures-secret';
const m = (f: unknown) => f as jest.Mock;
const token = (sub = 'u1', options: jwt.SignOptions = { expiresIn: '1h' }) => jwt.sign({ sub }, SECRET, options);
const READY = { type: 'friends.ready' };
const CHANGED = { type: 'friends.changed' };

let server: http.Server | undefined;
let stop: (() => void) | undefined;
let url = '';
let clients: WebSocket[] = [];
let spies: jest.SpyInstance[] = [];
const spy = <T extends jest.SpyInstance>(s: T): T => { spies.push(s); return s; };

async function start(): Promise<void> {
  server = http.createServer((_req, res) => { res.statusCode = 404; res.end(); });
  stop = attachFriendEvents(server);
  await new Promise<void>((resolve) => server!.listen(0, '127.0.0.1', resolve));
  url = `ws://127.0.0.1:${(server!.address() as AddressInfo).port}/api/friends/events`;
}
const until = async (check: () => boolean | Promise<boolean>) => {
  for (let i = 0; i < 500; i++) { if (await check()) return; await new Promise((r) => setImmediate(r)); }
  throw new Error('condition never became true');
};
const openConnections = () => new Promise<number>((resolve) => server!.getConnections((_e, n) => resolve(n)));
const nextMessage = (s: WebSocket) => new Promise<unknown>((resolve) => s.once('message', (d) => resolve(JSON.parse(String(d)))));
const closed = (s: WebSocket) => new Promise<number>((resolve) => s.once('close', (code) => resolve(code)));
const roundTrip = (s: WebSocket) => new Promise<void>((resolve) => { s.once('pong', () => resolve()); s.ping(); });
async function connect(t = token()): Promise<WebSocket> {
  const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${t}` } });
  clients.push(socket);
  expect(await new Promise((resolve, reject) => { socket.once('error', reject); socket.once('message', (d) => resolve(JSON.parse(String(d)))); })).toEqual(READY);
  return socket;
}
const rejectedStatus = () => new Promise<number | undefined>((resolve) => {
  const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token()}` } });
  clients.push(socket);
  socket.on('error', () => undefined);
  socket.once('unexpected-response', (_req, res) => { res.resume(); resolve(res.statusCode); socket.terminate(); });
});
const original = { send: WebSocket.prototype.send };
const patchSend = (failure: 'callback' | 'throw') => spy(jest.spyOn(WebSocket.prototype, 'send')).mockImplementation(function (this: WebSocket, ...args: unknown[]) {
  const callback = args[1];
  if (typeof callback !== 'function') return (original.send as (...a: unknown[]) => void).call(this, args[0]); // the ready message passes through
  if (failure === 'throw') throw new Error('send exploded');
  (callback as (e?: Error) => void)(new Error('send failed'));
} as never);

beforeEach(() => {
  jest.resetAllMocks();
  process.env.JWT_SECRET = SECRET;
  m(db.user.findUnique).mockResolvedValue({ id: 'u1', isBanned: false });
});
afterEach(async () => {
  jest.useRealTimers();
  for (const s of spies) s.mockRestore();
  spies = [];
  for (const c of clients) c.terminate();
  clients = [];
  try { stop?.(); } catch { /* already stopped by the test */ }
  server?.closeAllConnections();
  await new Promise<void>((resolve) => (server ? server.close(() => resolve()) : resolve()));
  server = undefined; stop = undefined;
});

describe('Mocked: publishing to bad connections', () => {
  it('FEF-1 a client that stops reading (buffer over the limit) is dropped instead of queueing forever', async () => {
    await start();
    const c = await connect();
    const gone = closed(c);
    spy(jest.spyOn(WebSocket.prototype, 'bufferedAmount', 'get')).mockReturnValue(1_000_000);
    publishFriendsChanged(['u1']);
    await gone;
  });
  it('FEF-2 sockets that are no longer open are skipped', async () => {
    await start();
    await connect();
    const send = spy(jest.spyOn(WebSocket.prototype, 'send'));
    send.mockClear();
    const state = spy(jest.spyOn(WebSocket.prototype, 'readyState', 'get')).mockReturnValue(WebSocket.CLOSING);
    publishFriendsChanged(['u1']);
    expect(send).not.toHaveBeenCalled();
    state.mockRestore();
  });
  it('FEF-3 a failed send drops that connection', async () => {
    await start();
    const c = await connect();
    const gone = closed(c);
    patchSend('callback');
    publishFriendsChanged(['u1']);
    await gone;
  });
  it('FEF-4 a send that throws drops that connection without throwing to the caller', async () => {
    await start();
    const c = await connect();
    const gone = closed(c);
    patchSend('throw');
    expect(() => publishFriendsChanged(['u1'])).not.toThrow();
    await gone;
  });
});

describe('Mocked: connection lifetime', () => {
  const fakeTimers = () => jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate', 'queueMicrotask'] });

  it('FEF-5 the heartbeat pings clients, keeps the ones that answer, and drops the ones that do not', async () => {
    fakeTimers();
    await start();
    const live = await connect();
    const dead = await connect();
    (dead as unknown as { pong: () => void }).pong = () => undefined; // this client never answers pings
    const gotPing = new Promise((resolve) => live.once('ping', resolve));
    jest.advanceTimersByTime(30_000);
    await gotPing;
    await roundTrip(live); // by now the server has processed live's pong
    const deadClosed = closed(dead);
    jest.advanceTimersByTime(30_000);
    await deadClosed;
    expect(live.readyState).toBe(WebSocket.OPEN);
    const message = nextMessage(live);
    publishFriendsChanged(['u1']);
    expect(await message).toEqual(CHANGED);
  });

  it('FEF-6 a connection is closed with 1008 when its token expires', async () => {
    fakeTimers();
    await start();
    const c = await connect(token('u1', { expiresIn: 5 }));
    const code = closed(c);
    jest.advanceTimersByTime(4_000);
    expect(c.readyState).toBe(WebSocket.OPEN);
    jest.advanceTimersByTime(1_000);
    expect(await code).toBe(1008);
  });

  it('FEF-7 a session that expires while the handshake is being authorised is refused with 401', async () => {
    await start();
    spy(jest.spyOn(authMiddleware, 'authenticateBearer')).mockResolvedValue({ userId: 'u1', expiresAt: Date.now() - 1 });
    expect(await rejectedStatus()).toBe(401);
  });

  it('FEF-8 an unavailable user lookup is a 503, not a 401', async () => {
    await start();
    m(db.user.findUnique).mockRejectedValue(new Error('db down'));
    expect(await rejectedStatus()).toBe(503);
  });
});

describe('Mocked: handshake edge cases', () => {
  it('FEF-9 upgrade requests for other URLs are left alone', async () => {
    await start();
    const socket = { destroyed: false, on: jest.fn() } as unknown as Duplex;
    server!.emit('upgrade', { url: '/somewhere-else', headers: {} }, socket, Buffer.alloc(0));
    expect(socket.on).not.toHaveBeenCalled();
    expect(m(db.user.findUnique)).not.toHaveBeenCalled();
  });

  it('FEF-10 an upgrade request with no URL is ignored', async () => {
    await start();
    expect(() => server!.emit('upgrade', { headers: {} }, { on: jest.fn() }, Buffer.alloc(0))).not.toThrow();
    expect(m(db.user.findUnique)).not.toHaveBeenCalled();
  });

  it('FEF-11 shutting down while a handshake is being authorised destroys that connection', async () => {
    await start();
    let release: (user: unknown) => void = () => undefined;
    m(db.user.findUnique).mockReturnValue(new Promise((resolve) => { release = resolve; }));
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token()}` } });
    clients.push(socket);
    const failed = new Promise<Error>((resolve) => socket.once('error', resolve));
    await until(() => m(db.user.findUnique).mock.calls.length > 0);
    stop!();
    release({ id: 'u1', isBanned: false });
    expect((await failed).message).toBeTruthy();
  });

  it.each(['authorised', 'rejected'] as const)('FEF-12 a client that leaves while its handshake is pending is ignored when it is %s', async (outcome) => {
    await start();
    let settle: (user: unknown) => void = () => undefined;
    let fail: (error: Error) => void = () => undefined;
    m(db.user.findUnique).mockReturnValue(new Promise((resolve, reject) => { settle = resolve; fail = reject; }));
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token()}` } });
    clients.push(socket);
    socket.on('error', () => undefined);
    await until(() => m(db.user.findUnique).mock.calls.length > 0);
    const socketClosed = closed(socket);
    socket.terminate();
    await socketClosed;
    if (outcome === 'authorised') settle({ id: 'u1', isBanned: false }); else fail(new Error('db down'));
    for (let i = 0; i < 10; i++) await new Promise((r) => setImmediate(r));
    expect(() => publishFriendsChanged(['u1'])).not.toThrow();
  });

  it('FEF-13 a socket error during the handshake is swallowed and the connection still completes', async () => {
    await start();
    let raw: Duplex | undefined;
    server!.prependListener('upgrade', (_req, socket) => { raw = socket; });
    let release: (user: unknown) => void = () => undefined;
    m(db.user.findUnique).mockReturnValue(new Promise((resolve) => { release = resolve; }));
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token()}` } });
    clients.push(socket);
    const ready = new Promise((resolve, reject) => { socket.once('error', reject); socket.once('message', (d) => resolve(JSON.parse(String(d)))); });
    await until(() => raw !== undefined && m(db.user.findUnique).mock.calls.length > 0);
    expect(() => raw!.emit('error', new Error('ECONNRESET'))).not.toThrow();
    release({ id: 'u1', isBanned: false });
    expect(await ready).toEqual(READY);
  });
});
