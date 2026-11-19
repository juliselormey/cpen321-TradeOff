import { ENDPOINTS, Sample, assertPerformance, fixture, measure, report, warm } from './helpers';

test('NFR-2: 10 simultaneous users complete 300 successful requests with per-endpoint p95 < 1000 ms', async () => {
  jest.useRealTimers();
  const { app } = fixture();
  await warm(app);
  // Ten workers start together. Each waits for its own response before sending
  // another, like a user, while the other nine continue independently.
  const users = await Promise.all(Array.from({ length: 10 }, async (_, user) => {
    const samples: Sample[] = [];
    for (let i = 0; i < 30; i++) {
      samples.push(await measure(app, ENDPOINTS[(i + user) % ENDPOINTS.length]!));
    }
    return samples;
  }));
  expect(users).toHaveLength(10);
  for (const samples of users) expect(samples).toHaveLength(30);
  const samples = users.flat();
  report('NFR-2', samples, users.length);
  expect(samples).toHaveLength(300);
  for (const endpoint of ENDPOINTS) expect(samples.filter(sample => sample.endpoint === endpoint)).toHaveLength(100);
  assertPerformance(samples);
});
