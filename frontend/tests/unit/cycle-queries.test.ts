import { describe, expect, it } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { cycleKeys, invalidateCycle } from '@/modules/jobs/orchestration/cycleQueries';

/** SRS-15-17: the Gate Override & Undo / Reversal Register is refreshed after a gate action. */
describe('SRS_15_17 cycle query invalidation', () => {
  const seeded = () => {
    const client = new QueryClient();
    client.setQueryData(cycleKeys.register(), { rows: [] });
    client.setQueryData(cycleKeys.gates('2026-10'), []);
    client.setQueryData(cycleKeys.gates('2026-09'), []);
    return client;
  };

  it('SRS_15_17_registerRefreshedWithThePeriod', async () => {
    const client = seeded();
    await invalidateCycle(client, '2026-10');
    expect(client.getQueryState(cycleKeys.register())?.isInvalidated).toBe(true);
    expect(client.getQueryState(cycleKeys.gates('2026-10'))?.isInvalidated).toBe(true);
  });

  it('SRS_15_17_otherPeriodsUntouched', async () => {
    const client = seeded();
    await invalidateCycle(client, '2026-10');
    expect(client.getQueryState(cycleKeys.gates('2026-09'))?.isInvalidated).toBe(false);
  });

  it('SRS_15_17_emptyPeriodStillRefreshesTheRegister', async () => {
    const client = seeded();
    await invalidateCycle(client, '');
    expect(client.getQueryState(cycleKeys.register())?.isInvalidated).toBe(true);
  });
});
