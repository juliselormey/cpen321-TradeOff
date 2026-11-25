import request from 'supertest';

import { createApp } from '../../src/app';

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

    expect([400, 500]).toContain(response.status);
    expect(typeof response.body.error).toBe('string');
    expect((await request(app).get('/health')).status).toBe(200);
  });
});