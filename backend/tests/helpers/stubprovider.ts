import { HISTORY } from './testkit';

const DOWN = () => new Error('provider down');

const quote = (price: number) => ({
  price,
  change: 1,
  percentageChange: 1,
  quoteAsOf: new Date().toISOString(),
});

export const createStubProvider = (state: { price: number; fail: boolean }) => ({
  fetchQuote: () => {
    if (state.fail) return Promise.reject(DOWN());
    return Promise.resolve(quote(state.price));
  },
  fetchHistory: () => {
    if (state.fail) return Promise.reject(DOWN());
    return Promise.resolve(HISTORY);
  },
  fetchQuotes: (symbols: readonly string[]) => {
    if (state.fail) return Promise.reject(DOWN());
    return Promise.resolve(Object.fromEntries(symbols.map(s => [s, quote(state.price)])));
  },
  fetchHistories: (symbols: readonly string[]) => {
    if (state.fail) return Promise.reject(DOWN());
    return Promise.resolve(Object.fromEntries(symbols.map(s => [s, HISTORY])));
  },
});

export const createStubProviderClass = (getState: () => { price: number; fail: boolean }) => class StubAlpacaProvider {
  private provider() {
    return createStubProvider(getState());
  }

  fetchQuote(symbol: string) {
    void symbol;
    return this.provider().fetchQuote();
  }

  fetchHistory(symbol: string) {
    void symbol;
    return this.provider().fetchHistory();
  }

  fetchQuotes(symbols: readonly string[]) {
    return this.provider().fetchQuotes(symbols);
  }

  fetchHistories(symbols: readonly string[]) {
    return this.provider().fetchHistories(symbols);
  }
};
