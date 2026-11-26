const base = require('./jest.config');

module.exports = {
  ...base,
  testMatch: ['**/tests/nfr/**/*.test.ts'],
  // Instrumentation distorts performance measurements; measure without coverage.
  collectCoverage: false,
  verbose: true,
  silent: false,
  setupFiles: ['<rootDir>/tests/nfr/setup.ts'],
  testTimeout: 120000,
};
