import { ENDPOINTS, Sample, assertPerformance, fixture, measure, report, warm } from './helpers';

test('NFR-1: 100 sequential requests per warmed Market endpoint have p95 < 1000 ms', async () => {
  jest.useRealTimers();
  const { app } = fixture();
  await warm(app);
  const samples: Sample[] = [];
  for (const endpoint of ENDPOINTS) {
    for (let i = 0; i < 100; i++) samples.push(await measure(app, endpoint));
  }
  report('NFR-1', samples, 1);
  for (const endpoint of ENDPOINTS) expect(samples.filter(sample => sample.endpoint === endpoint)).toHaveLength(100);
  assertPerformance(samples);
});
