import { HISTORY_TTL_MS, REFRESH_INTERVAL_MS } from './catalog';
import { marketService, MarketService } from './service';

/** One loop per dataset, shared by every client. Never overlaps slow requests. */
export function startMarketRefresh(service: MarketService = marketService): () => void {
  let stopped = false;
  const timers = new Set<NodeJS.Timeout>();
  const loop = async (refresh: () => Promise<void>, interval: number): Promise<void> => {
    try { await refresh(); }
    catch (error: unknown) { console.error('Market refresh failed', error); }
    if (stopped) return;
    const timer = setTimeout(() => {
      timers.delete(timer);
      void loop(refresh, interval);
    }, interval);
    timer.unref();
    timers.add(timer);
  };
  void loop(() => service.refreshQuotes(), REFRESH_INTERVAL_MS);
  void loop(() => service.refreshHistories(), HISTORY_TTL_MS);
  return () => {
    stopped = true;
    for (const timer of timers) clearTimeout(timer);
    timers.clear();
  };
}
