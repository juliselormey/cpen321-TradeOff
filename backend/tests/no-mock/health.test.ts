import request from 'supertest';

import createAppDefault, { createApp } from '../../src/app';

// Interface GET /health
describe('Unmocked: GET /health', () => {
  // Input: GET request to /health
  // Expected status code: 200
  // Expected behavior: service reports healthy status
  // Expected output: { status: "ok" }
  test('Healthy service', async () => {
    const response = await request(createApp()).get('/health');

    expect(response.status).toBe(200);
    expect(response.body).toEqual({ status: 'ok' });
  });
});

// Interface GET /does-not-exist
describe('Unmocked: GET /does-not-exist', () => {
  // Input: GET request to an unregistered route
  // Expected status code: 404
  // Expected behavior: request is rejected; no state is changed
  // Expected output: { error: "Not Found" }
  test('Unregistered route', async () => {
    const response = await request(createApp()).get('/does-not-exist');

    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: 'Not Found' });
  });
});

// Interface: any unregistered route / malformed input
describe('Unmocked: unknown routes and malformed input', () => {
  // Input: any method on an unregistered route
  // Expected status code: 404
  // Expected output: { error: "Not Found" }
  test.each(['post', 'put', 'patch', 'delete'] as const)('%s on an unregistered route', async (method) => {
    const response = await request(createApp())[method]('/does-not-exist');

    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: 'Not Found' });
  });

  // Input: syntactically invalid JSON
  // Expected behavior: rejected with an error status and a JSON error message; the server stays up
  test('malformed JSON body', async () => {
    const app = createApp();
    const response = await request(app).post('/api/auth/google')
      .set('Content-Type', 'application/json').send('{"idToken":');

    expect(response.status).toBe(400);
    expect(response.body).toEqual({ error: 'Invalid request body' });
    expect((await request(app).get('/health')).status).toBe(200);
  });
});

// Interface: app configuration (body limits, CORS, proxy, e2e router)
describe('Unmocked: app configuration', () => {
  // Input: a JSON body over the 100 kB parser limit
  // Expected status code: 413 (a client error, not a server fault)
  test('oversized body', async () => {
    const response = await request(createApp()).post('/api/auth/google').send({ idToken: 'x'.repeat(200_000) });

    expect(response.status).toBe(413);
    expect(response.body).toEqual({ error: 'Request body too large' });
  });

  // Input: a cross-origin request and a CORS preflight
  // Expected behavior: any origin is allowed (the Android client and web tools call the API directly)
  test('CORS headers and preflight', async () => {
    const app = createApp();
    const simple = await request(app).get('/health').set('Origin', 'http://example.com');
    expect(simple.headers['access-control-allow-origin']).toBe('*');

    const preflight = await request(app).options('/api/portfolio')
      .set('Origin', 'http://example.com').set('Access-Control-Request-Method', 'GET');
    expect(preflight.status).toBe(204);
    expect(preflight.headers['access-control-allow-methods']).toContain('GET');
  });

  // Expected behavior: the app trusts one proxy hop (the cloud load balancer / reverse proxy)
  test('trust proxy and default export', () => {
    expect(createApp().get('trust proxy')).toBe(1);
    expect(createAppDefault).toBe(createApp);
  });

  // Expected behavior: the destructive e2e fixture router is not reachable unless explicitly enabled
  test('e2e routes are disabled by default', async () => {
    const saved = process.env.E2E_ENABLED;
    delete process.env.E2E_ENABLED;
    try {
      const response = await request(createApp()).post('/__e2e/account/reset');
      expect(response.status).toBe(404);
      expect(response.body).toEqual({ error: 'Not Found' });
    } finally {
      if (saved !== undefined) process.env.E2E_ENABLED = saved;
    }
  });
});