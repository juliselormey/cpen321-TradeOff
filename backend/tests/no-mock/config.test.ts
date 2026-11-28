import path from 'node:path';

// dotenv is mocked so the developer's own .env can't change what these tests assert.
jest.mock('dotenv/config', () => ({}));

type EnvModule = typeof import('../../src/config/env');
const original = { ...process.env };

/** env.ts reads process.env once, when it is first imported, so each case imports a fresh copy. */
function load(vars: Record<string, string | undefined> = {}): EnvModule {
  for (const [name, value] of Object.entries(vars)) {
    if (value === undefined) delete process.env[name]; else process.env[name] = value;
  }
  let loaded!: EnvModule;
  jest.isolateModules(() => { loaded = require('../../src/config/env'); });
  return loaded;
}

beforeEach(() => {
  for (const name of ['PORT', 'SSL_KEY_PATH', 'SSL_CERT_PATH', 'SERVER_PUBLIC_IP']) delete process.env[name];
});
afterAll(() => { process.env = { ...original }; });

describe('config: PORT', () => {
  it.each([[undefined], ['']])('CFG-1 defaults to 3000 when PORT is %p', (port) => {
    expect(load({ PORT: port }).env.port).toBe(3000);
  });
  it('CFG-2 uses a valid PORT', () => {
    expect(load({ PORT: '8080' }).env.port).toBe(8080);
    expect(load({ PORT: '1' }).env.port).toBe(1);
    expect(load({ PORT: '65535' }).env.port).toBe(65535);
  });
  it.each([['abc'], ['0'], ['-5'], ['65536'], ['70000']])('CFG-3 rejects PORT=%s at startup', (port) => {
    expect(() => load({ PORT: port })).toThrow(`Invalid PORT: ${port}`);
  });
});

describe('config: TLS and public address', () => {
  it('CFG-4 defaults to the certs folder and an unknown public IP', () => {
    const { env } = load();
    expect(env.sslKeyPath).toBe(path.join(path.dirname(require.resolve('../../src/config/env')), '../../certs/key.pem'));
    expect(env.sslCertPath).toMatch(/certs[\\/]cert\.pem$/);
    expect(env.serverPublicIp).toBe('unknown');
  });
  it('CFG-5 takes overrides from the environment', () => {
    const { env } = load({ SSL_KEY_PATH: '/etc/ssl/key.pem', SSL_CERT_PATH: '/etc/ssl/cert.pem', SERVER_PUBLIC_IP: '203.0.113.7' });
    expect(env).toEqual(expect.objectContaining({ sslKeyPath: '/etc/ssl/key.pem', sslCertPath: '/etc/ssl/cert.pem', serverPublicIp: '203.0.113.7' }));
  });
});

describe('config: requireEnv', () => {
  const NAME = 'TRADEOFF_TEST_ENV_VALUE';
  afterEach(() => { delete process.env[NAME]; });

  it('CFG-6 returns the configured value', () => {
    process.env[NAME] = 'configured';
    expect(load().requireEnv(NAME)).toBe('configured');
  });
  it('CFG-7 throws, naming the variable, when it is missing or empty', () => {
    const { requireEnv } = load();
    expect(() => requireEnv(NAME)).toThrow(`${NAME} is not set`);
    process.env[NAME] = '';
    expect(() => requireEnv(NAME)).toThrow(`${NAME} is not set`);
  });
  it('CFG-8 cannot be tricked into reading inherited object properties', () => {
    expect(() => load().requireEnv('constructor')).toThrow('constructor is not set');
  });
});