import axios from 'axios';
import express from 'express';
import request from 'supertest';
import { AlpacaProvider } from '../../src/market/alpaca';
import { HISTORY_TTL_MS, LIVE_SYMBOLS } from '../../src/market/catalog';
import { createMarketRouter } from '../../src/market/routes';
import { MarketService } from '../../src/market/service';
import { MarketProviderError, PricePoint, ProviderQuote } from '../../src/market/types';

const START = Date.parse('2026-10-23T15:00:00Z');
const history: PricePoint[] = [
  { timestamp: '2026-10-21T04:00:00Z', price: 100 },
  { timestamp: '2026-10-22T04:00:00Z', price: 101 },
];
function fixture() {
  let clock = START;
  const quotes: Record<string, ProviderQuote | MarketProviderError> = Object.fromEntries(LIVE_SYMBOLS.map((s, i) => [s, {
    price: 200 + i, change: i, percentageChange: i / 2, quoteAsOf: new Date(START).toISOString(),
  }]));
  const histories = Object.fromEntries(LIVE_SYMBOLS.map(s => [s, history]));
  const fetchQuotes = jest.fn<Promise<typeof quotes>, [readonly string[]]>().mockResolvedValue(quotes);
  const fetchHistories = jest.fn<Promise<typeof histories>, [readonly string[]]>().mockResolvedValue(histories);
  const fetchQuote = jest.fn();
  const fetchHistory = jest.fn();
  const service = new MarketService({ fetchQuotes, fetchHistories, fetchQuote, fetchHistory }, () => clock);
  const app = express();
  app.use('/api/market', createMarketRouter(service));
  return { service, app, quotes, fetchQuotes, fetchHistories, fetchQuote, fetchHistory,
    advance: (ms: number) => { clock += ms; } };
}

describe('Batch market cache shared across all screens and symbols', () => {
  test('concurrent cold list, single-stock and history calls make only two provider batches', async () => {
    const f = fixture();
    await Promise.all([f.service.getStocks(), f.service.getStock('MSFT'), f.service.getHistory('NVDA')]);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(1);
    expect(f.fetchHistories).toHaveBeenCalledTimes(1);
    expect(f.fetchQuotes).toHaveBeenCalledWith(LIVE_SYMBOLS);
    expect(f.fetchQuote).not.toHaveBeenCalled();
    expect(f.fetchHistory).not.toHaveBeenCalled();
    const stocks = await f.service.getStocks();
    expect(stocks.map(s => s.currentPrice)).toEqual(['200', '201', '202', '203', '204', '205']);
    expect(stocks.every(s => s.source === 'alpaca' && s.history?.points.length === 3)).toBe(true);
    f.advance(8_000);
    await Promise.all([f.service.getStock('TSLA'), f.service.getStocks()]);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
    expect(f.fetchHistories).toHaveBeenCalledTimes(1);
    f.advance(HISTORY_TTL_MS);
    await f.service.getHistory('AMZN');
    expect(f.fetchHistories).toHaveBeenCalledTimes(2);
    expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
  });

  test('one missing/invalid symbol retains its quote; healthy symbols and charts still update', async () => {
    const f = fixture();
    await f.service.getStocks();
    f.advance(8_000);
    f.quotes.MSFT = new MarketProviderError('PROVIDER_INVALID_DATA', 'Missing MSFT snapshot');
    const stocks = await f.service.getStocks();
    expect(stocks.find(s => s.ticker === 'MSFT')).toMatchObject({ status: 'stale', currentPrice: '201' });
    expect(stocks.filter(s => s.status === 'ok')).toHaveLength(5);
    expect(stocks.every(s => s.history?.status === 'ok')).toBe(true);
    f.advance(8_000);
    await f.service.getStocks();
    expect(f.fetchQuotes.mock.calls[2]?.[0]).not.toContain('MSFT');
  });

  test('a batch outage retains all quotes and suppresses repeat requests until cooldown', async () => {
    const f = fixture();
    await f.service.getStocks();
    f.advance(8_000);
    f.fetchQuotes.mockRejectedValueOnce(new MarketProviderError('PROVIDER_RATE_LIMITED', 'Rate limited', 60_000));
    expect((await f.service.getStocks()).every(s => s.status === 'stale' && s.currentPrice !== null)).toBe(true);
    f.advance(59_999);
    await f.service.getStock('NVDA');
    expect(f.fetchQuotes).toHaveBeenCalledTimes(2);
    f.advance(1);
    expect((await f.service.getStocks()).every(s => s.status === 'ok')).toBe(true);
  });

  test.each(LIVE_SYMBOLS)('%s has an individual quote and history endpoint', async symbol => {
    const { app } = fixture();
    const stock = await request(app).get(`/api/market/stocks/${symbol.toLowerCase()}`).expect(200);
    expect(stock.body).toMatchObject({ ticker: symbol, source: 'alpaca', history: { symbol, status: 'ok' } });
    const chart = await request(app).get(`/api/market/stocks/${symbol}/history`).expect(200);
    expect(chart.body.symbol).toBe(symbol);
    expect(chart.body.points.length).toBeGreaterThan(1);
  });
});

describe('Alpaca multi-symbol adapter', () => {
  afterEach(() => jest.restoreAllMocks());
  const provider = () => new AlpacaProvider(() => ({ key: 'test-key', secret: 'test-secret' }), () => START);
  test('one IEX snapshot request parses each symbol independently', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: {
      AAPL: { latestTrade: { p: 200, t: new Date(START).toISOString() }, prevDailyBar: { c: 198 } },
      MSFT: {},
    } });
    const results = await provider().fetchQuotes(['AAPL', 'MSFT', 'TSLA']);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('https://data.alpaca.markets/v2/stocks/snapshots', expect.objectContaining({
      params: { symbols: 'AAPL,MSFT,TSLA', feed: 'iex' },
    }));
    expect(results.AAPL).toMatchObject({ price: 200, change: 2 });
    expect(results.MSFT).toBeInstanceOf(MarketProviderError);
    expect(results.TSLA).toBeInstanceOf(MarketProviderError);
  });

  test('history follows page tokens to reach later symbols and keeps each graph separate', async () => {
    const get = jest.spyOn(axios, 'get')
      .mockResolvedValueOnce({ data: { bars: { AAPL: [{ t: history[1]?.timestamp, c: 101 }] }, next_page_token: 'page2' } })
      .mockResolvedValueOnce({ data: { bars: {
        AAPL: [{ t: history[0]?.timestamp, c: 100 }], MSFT: [{ t: history[0]?.timestamp, c: 400 }],
      }, next_page_token: null } });
    const results = await provider().fetchHistories(['AAPL', 'MSFT', 'TSLA']);
    expect(get).toHaveBeenCalledTimes(2);
    expect(get.mock.calls[1]?.[1]?.params).toMatchObject({
      symbols: 'AAPL,MSFT,TSLA', page_token: 'page2', limit: 1000, feed: 'iex', timeframe: '1Day',
      end: '2026-10-23T00:00:00.000Z',
    });
    expect(results.AAPL).toHaveLength(2);
    expect(results.MSFT).toEqual([{ timestamp: '2026-10-21T04:00:00.000Z', price: 400 }]);
    expect(results.TSLA).toEqual([]);
  });

  test.each([[{ t: 'bad', c: 0 }], { t: history[0]?.timestamp, c: 400 }])(
    'invalid history for one symbol does not erase another chart: %p', async invalid => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { bars: {
      AAPL: [{ t: history[0]?.timestamp, c: 100 }], MSFT: invalid,
    } } });
    const result = await provider().fetchHistories(['AAPL', 'MSFT']);
    expect(result.AAPL).toHaveLength(1);
    expect(result.MSFT).toBeInstanceOf(MarketProviderError);
  });

  test('repeated pagination token fails instead of presenting incomplete history as success', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { bars: {}, next_page_token: 'loop' } });
    await expect(provider().fetchHistories(LIVE_SYMBOLS)).rejects.toMatchObject({ code: 'PROVIDER_INVALID_DATA' });
  });
});
