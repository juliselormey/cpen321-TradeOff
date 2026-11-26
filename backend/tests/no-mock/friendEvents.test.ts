import http from 'node:http';
import request from 'supertest';
import { WebSocket } from 'ws';
import { createApp } from '../../src/app';
import { attachFriendEvents } from '../../src/realtime/friendEvents';
import { bearer } from '../helpers/testkit';
import { befriend, createUser, db, resetDb } from '../helpers/fixtures';

let server: http.Server;
let stop: () => void;
let url: string;
let clients: WebSocket[] = [];
let A: string, B: string, C: string;

const headers = (id: string) => bearer(id) as unknown as Record<string, string>;

beforeAll(async () => {
  server = http.createServer(createApp());
  stop = attachFriendEvents(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Missing test server port');
  url = `ws://127.0.0.1:${address.port}/api/friends/events`;
});
afterAll(async () => {
  stop();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await db.$disconnect();
});
beforeEach(async () => {
  await resetDb();
  [A, B, C] = [await createUser(), await createUser(), await createUser()];
});
afterEach(async () => {
  const closed = clients.filter((c) => c.readyState !== WebSocket.CLOSED)
    .map((c) => new Promise<void>((resolve) => c.once('close', () => resolve())));
  for (const c of clients) c.terminate();
  await Promise.all(closed);
  clients = [];
});

async function connect(id: string): Promise<{ socket: WebSocket; messages: string[] }> {
  const socket = new WebSocket(url, { headers: headers(id) });
  clients.push(socket);
  const messages: string[] = [];
  socket.on('message', (data) => messages.push(String(data)));
  await new Promise<void>((resolve, reject) => {
    socket.once('error', reject);
    socket.once('message', (data) => {
      expect(JSON.parse(String(data))).toEqual({ type: 'friends.ready' });
      resolve();
    });
  });
  return { socket, messages };
}
const READY = JSON.stringify({ type: 'friends.ready' });
const nextMessage = (c: { socket: WebSocket }) => new Promise<unknown>((resolve) => c.socket.once('message', (d) => resolve(JSON.parse(String(d)))));
// A ping/pong round trip proves nothing else was queued for that socket, without sleeping.
const roundTrip = (c: { socket: WebSocket }) => new Promise<void>((resolve) => { c.socket.once('pong', () => resolve()); c.socket.ping(); });
const usernameOf = async (id: string) => (await db.user.findUniqueOrThrow({ where: { id }, select: { username: true } })).username;
const sendRequest = async (from: string, to: string) =>
  (await request(server).post('/api/friends/requests').set(bearer(from)).send({ username: await usernameOf(to) })).body.request.id as string;
const rejected = (h?: Record<string, string>) => new Promise<number | undefined>((resolve) => {
  const client = new WebSocket(url, { headers: h ?? {} });
  clients.push(client);
  client.on('error', () => undefined);
  client.once('unexpected-response', (_req, res) => { res.resume(); resolve(res.statusCode); client.terminate(); });
});

describe('Unmocked: friend events (WebSocket)', () => {
  it('FEV-N1 connecting announces readiness so the app can reload anything missed offline', async () => {
    const first = await connect(A);
    expect(first.messages).toEqual([READY]);
    await new Promise<void>((resolve) => { first.socket.once('close', () => resolve()); first.socket.close(); });
    const second = await connect(A);
    expect(second.messages).toEqual([READY]);
  });

  it.each([
    ['sending a request', async () => { await sendRequest(A, B); }],
    ['accepting a request', async (id: string) => { await request(server).post(`/api/friends/requests/${id}/accept`).set(bearer(B)); }],
    ['declining a request', async (id: string) => { await request(server).delete(`/api/friends/requests/${id}`).set(bearer(B)); }],
    ['cancelling a request', async (id: string) => { await request(server).delete(`/api/friends/requests/${id}`).set(bearer(A)); }],
  ])('FEV-N2 %s notifies both participants and nobody else', async (_name, act) => {
    const requestId = _name === 'sending a request' ? '' : await sendRequest(A, B); // connect after the setup request
    const a = await connect(A);
    const b = await connect(B);
    const outsider = await connect(C);
    const changed = [a, b].map(nextMessage);
    await act(requestId);
    expect(await Promise.all(changed)).toEqual([{ type: 'friends.changed' }, { type: 'friends.changed' }]);
    await roundTrip(outsider);
    expect(outsider.messages).toEqual([READY]);
  });

  it('FEV-N3 unfriending notifies both participants and nobody else', async () => {
    const f = await befriend(A, B);
    const a = await connect(A);
    const b = await connect(B);
    const outsider = await connect(C);
    const changed = [a, b].map(nextMessage);
    expect((await request(server).delete(`/api/friends/${f.id}`).set(bearer(A))).status).toBe(204);
    expect(await Promise.all(changed)).toEqual([{ type: 'friends.changed' }, { type: 'friends.changed' }]);
    await roundTrip(outsider);
    expect(outsider.messages).toEqual([READY]);
  });

  it('FEV-N4 a rejected change sends no notification', async () => {
    const a = await connect(A);
    expect((await request(server).delete('/api/friends/does-not-exist').set(bearer(A))).status).toBe(404);
    expect((await request(server).post('/api/friends/requests/does-not-exist/accept').set(bearer(A))).status).toBe(404);
    await roundTrip(a);
    expect(a.messages).toEqual([READY]);
  });

  it('FEV-N5 missing or invalid credentials are refused with 401', async () => {
    expect(await rejected()).toBe(401);
    expect(await rejected({ Authorization: 'Bearer invalid' })).toBe(401);
  });

  it('FEV-N6 a banned account is refused with 403', async () => {
    await db.user.update({ where: { id: A }, data: { isBanned: true } });
    expect(await rejected(headers(A))).toBe(403);
  });
});