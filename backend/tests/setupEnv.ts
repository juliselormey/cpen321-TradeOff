import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { config } from 'dotenv';

config({ path: resolve(__dirname, '../.env.test'), quiet: true });
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL ??= 'mysql://test:test@127.0.0.1:3306/tradeoff_test';
process.env.JWT_SECRET ??= randomBytes(32).toString('hex');
process.env.GOOGLE_CLIENT_ID ??= 'test-google-client-id';
process.env.E2E_USER_EMAIL ??= 'test-user@test.invalid';
process.env.ALPACA_API_KEY_ID ??= '';
process.env.ALPACA_API_SECRET_KEY ??= '';