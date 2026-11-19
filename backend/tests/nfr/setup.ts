import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

// No real credentials, database, running backend, or Alpaca access is needed.
process.env.JWT_SECRET = 'nfr-local-tests-only';
process.env.ALPACA_API_KEY_ID = '';
process.env.ALPACA_API_SECRET_KEY = '';
mkdirSync(resolve(__dirname, '../../reports/nfr'), { recursive: true });
