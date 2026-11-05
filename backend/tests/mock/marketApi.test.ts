import express from 'express';
import request from 'supertest';
import { createMarketRouter } from '../../src/market/routes';
import { MarketService } from '../../src/market/service';
import { LIVE_SYMBOLS } from '../../src/market/catalog';
import { MarketProviderError, PricePoint, ProviderQuote, QuoteProvider } from '../../src/market/types';

const NOW = Date.parse('2026-10-23T15:00:00.000Z');
const history: PricePoint[] = [
  { timestamp: '2026-10-21T14:30:00.000Z', price: 99 },
  { timestamp: '2026-10-22T14:30:00.000Z', price: 100 },
];

function fixture(overrides: Partial<QuoteProvider> = {}) {
  const quotes = new Map<string, ProviderQuote>(
    LIVE_SYMBOLS.map((ticker, index) => [ticker, {
      price: 100 + index,
      change: 1,
      percentageChange: 1,
      quoteAsOf: new Date(NOW).toISOString(),
    }])
  );

  const getQuote = (ticker: string): ProviderQuote => {
    const q = quotes.get(ticker);
    if (!q) throw new Error(`Missing quote for ${ticker}`);
    return q;
  };

  const fetchQuote = jest.fn<Promise<ProviderQuote>, [string]>((ticker: string) => Promise.resolve(getQuote(ticker)));
  const fetchHistory = jest.fn<Promise<PricePoint[]>, [string]>(() => Promise.resolve(history));
  const fetchQuotes = jest.fn<Promise<Record<string, ProviderQuote | MarketProviderError>>, [readonly string[]]>(
    symbols => Promise.resolve(Object.fromEntries(symbols.map(symbol => [symbol, getQuote(symbol)]))),
  );
  const fetchHistories = jest.fn<Promise<Record<string, PricePoint[] | MarketProviderError>>, [readonly string[]]>(
    symbols => Promise.resolve(Object.fromEntries(symbols.map(symbol => [symbol, history]))),
  );
  const provider: QuoteProvider = { fetchQuote, fetchHistory, fetchQuotes, fetchHistories, ...overrides };
  const app = express();
  app.use('/api/market', createMarketRouter(new MarketService(provider, () => NOW)));
  return { app, provider, fetchQuote, fetchHistory, fetchQuotes, fetchHistories };
}

describe('Mocked API: GET /api/market/stocks', () => {
  test('Input: GET with no parameters; Output: six catalog stocks and refresh metadata (200)', async () => {
    const f = fixture();
    const response = await request(f.app).get('/api/market/stocks').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toMatchObject({ liveSymbols: LIVE_SYMBOLS, refreshIntervalMs: 8_000 });
    expect(response.body.stocks).toHaveLength(6);
    expect(response.body.stocks[0]).toMatchObject({
      ticker: 'AAPL', companyName: 'Apple Inc.', currentPrice: '100', source: 'alpaca',
      feed: 'iex', status: 'ok', isFavorite: true,
    });
    expect(response.body.stocks[0].history.points).toHaveLength(3);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
    expect(f.fetchQuotes).toHaveBeenCalledWith(LIVE_SYMBOLS);
    expect(f.fetchHistories).toHaveBeenCalledTimes(1);
    expect(f.fetchHistories).toHaveBeenCalledWith(LIVE_SYMBOLS);
  });

  test('provider outage returns a partial-availability response (200) without inventing prices', async () => {
    const providerError = new MarketProviderError('MARKET_NOT_CONFIGURED', 'Configure Alpaca credentials.', 60_000);
    const f = fixture({
      fetchQuotes: jest.fn().mockRejectedValue(providerError),
      fetchHistories: jest.fn().mockRejectedValue(providerError),
    });
    const response = await request(f.app).get('/api/market/stocks').expect(200);

    expect(response.body.stocks).toHaveLength(6);
    expect(response.body.stocks.every((stock: { status: string; currentPrice: unknown }) =>
      stock.status === 'unavailable' && stock.currentPrice === null,
    )).toBe(true);
    expect(response.body.stocks[0].error.code).toBe('MARKET_NOT_CONFIGURED');
  });
});

describe('Mocked API: GET /api/market/stocks/:symbol', () => {
  test('Input: supported ticker; Output: quote, company metadata, and history (200)', async () => {
    const f = fixture();
    const response = await request(f.app).get('/api/market/stocks/aapl').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toMatchObject({
      ticker: 'AAPL', companyName: 'Apple Inc.', currency: 'USD', currentPrice: '100',
      change: '1', percentageChange: 1, source: 'alpaca', feed: 'iex', status: 'ok',
      history: { symbol: 'AAPL', status: 'ok' },
    });
    expect(response.body.quoteAsOf).toBe(new Date(NOW).toISOString());
    expect(f.fetchQuotes).toHaveBeenCalledWith(LIVE_SYMBOLS);
  });

  test('Input: unsupported ticker; Output: UNKNOWN_SYMBOL (404), with no provider call', async () => {
    const f = fixture();
    const response = await request(f.app).get('/api/market/stocks/NOTREAL').expect(404);

    expect(response.body).toEqual({
      error: { code: 'UNKNOWN_SYMBOL', message: 'Unsupported market symbol.' },
    });
    expect(f.fetchQuotes).not.toHaveBeenCalled();
    expect(f.fetchHistories).not.toHaveBeenCalled();
  });

  test('Input: supported ticker while provider is unavailable; Output: error body (503)', async () => {
    const f = fixture({
      fetchQuotes: jest.fn().mockRejectedValue(
        new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.'),
      ),
      fetchHistories: jest.fn().mockResolvedValue({}),
    });
    const response = await request(f.app).get('/api/market/stocks/AAPL').expect(503);

    expect(response.body).toMatchObject({
      ticker: 'AAPL', currentPrice: null, status: 'unavailable',
      error: { code: 'PROVIDER_UNAVAILABLE' },
    });
  });
});

describe('Mocked API: GET /api/market/stocks/:symbol/history', () => {
  test('Input: live ticker; Output: ordered IEX price points (200)', async () => {
    const f = fixture();
    const response = await request(f.app).get('/api/market/stocks/MSFT/history').expect(200);

    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toMatchObject({ symbol: 'MSFT', feed: 'iex', status: 'ok' });
    expect(response.body.points).toHaveLength(2);
    expect(response.body.points.map((point: PricePoint) => point.timestamp)).toEqual(
      history.map(point => point.timestamp),
    );
    expect(f.fetchHistories).toHaveBeenCalledWith(LIVE_SYMBOLS);
  });

  test('Input: unsupported ticker; Output: UNKNOWN_SYMBOL (404), with no provider call', async () => {
    const f = fixture();
    const response = await request(f.app).get('/api/market/stocks/NOTREAL/history').expect(404);

    expect(response.body).toEqual({
      error: { code: 'UNKNOWN_SYMBOL', message: 'Unsupported market symbol.' },
    });
    expect(f.fetchHistories).not.toHaveBeenCalled();
  });

  test('Input: live ticker while history provider fails; Output: error body (503)', async () => {
    const f = fixture({
      fetchQuotes: jest.fn().mockResolvedValue({}),
      fetchHistories: jest.fn().mockRejectedValue(
        new MarketProviderError('PROVIDER_UNAVAILABLE', 'Alpaca market data is temporarily unavailable.'),
      ),
    });
    const response = await request(f.app).get('/api/market/stocks/AAPL/history').expect(503);

    expect(response.body).toMatchObject({
      symbol: 'AAPL', status: 'unavailable',
      error: { code: 'PROVIDER_UNAVAILABLE' },
    });
  });
});
