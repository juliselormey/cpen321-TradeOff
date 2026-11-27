import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { config } from 'dotenv';

// This ignored file is separate from the deployed server's .env. CI variables win.
config({ path: resolve(__dirname, '../.env.test'), quiet: true });
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL ??= 'mysql://test:test@127.0.0.1:3306/tradeoff_test';
process.env.JWT_SECRET ??= randomBytes(32).toString('hex');
process.env.GOOGLE_CLIENT_ID ??= 'test-google-client-id';
// Empty values prevent dotenv/config from silently loading real keys from .env.
// Live-provider suites require credentials supplied through .env.test or CI.
process.env.ALPACA_API_KEY_ID ??= '';
process.env.ALPACA_API_SECRET_KEY ??= '';
