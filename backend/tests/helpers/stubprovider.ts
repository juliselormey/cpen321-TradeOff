import { HISTORY, SYMBOLS } from './testkit';

export const createStubProvider = (state: { price: number; fail: boolean }) => ({
  fetchQuote: async (symbol: string) => {
    if (state.fail) throw new Error('provider down');
    return {
      price: state.price,
      change: 1,
      percentageChange: 1,
      quoteAsOf: new Date().toISOString(),
    };
  },
  fetchHistory: async () => {
    if (state.fail) throw new Error('provider down');
    return HISTORY;
  },
  fetchQuotes: async (symbols: readonly string[]) => {
    if (state.fail) throw new Error('provider down');
    return Object.fromEntries(symbols.map(s => [s, {
      price: state.price,
      change: 1,
      percentageChange: 1,
      quoteAsOf: new Date().toISOString(),
    }]));
  },
  fetchHistories: async (symbols: readonly string[]) => {
    if (state.fail) throw new Error('provider down');
    return Object.fromEntries(symbols.map(s => [s, HISTORY]));
  },
});
