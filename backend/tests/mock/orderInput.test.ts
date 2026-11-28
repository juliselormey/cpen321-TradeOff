import { parseOrder } from '../../src/trading/orderInput';
import { OrderError } from '../../src/trading/settlement';

test.each([0, -1, Infinity, NaN, 1000001, 0.00001, '1'])('rejects invalid quantity %s', quantity => {
    expect(() => parseOrder({ ticker: 'AAPL', side: 'BUY', quantity }, 'MARKET')).toThrow(OrderError);
});
test('validates limit price and never invokes a supplied toString property', () => {
    expect(() => parseOrder({ ticker: 'AAPL', side: { toString: 'bad' }, quantity: 1 }, 'MARKET')).toThrow(OrderError);
    expect(() => parseOrder({ ticker: 'AAPL', side: 'SELL', quantity: 1, limitPrice: 0.00001 }, 'LIMIT')).toThrow(OrderError);
    expect(parseOrder({ ticker: 'aapl', side: 'buy', quantity: 0.0001 }, 'MARKET')).toEqual({
        ticker: 'AAPL', side: 'BUY', type: 'MARKET', quantity: 0.0001, limitPrice: null,
    });
});
