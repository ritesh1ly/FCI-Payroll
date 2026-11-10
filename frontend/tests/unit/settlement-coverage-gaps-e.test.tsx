import { screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import { backend, me, paged, wrap } from './support/harness';

/**
 * Coverage of O-05's remaining path (SRS-10-19): with no case in the unit nothing is chosen, so no case detail and no
 * order copy is read.
 */
afterEach(() => vi.unstubAllGlobals());

describe('O-05 remaining paths', () => {
  it('SRS_10_19_anEmptyCaseListChoosesNoCaseAndReadsNoDetail', async () => {
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => ({
        separations: 0,
        settled: 0,
        settledAmount: 0,
        byType: [],
        awaitingClearance: 0,
        hqApproval: 0,
        netRecovery: 0,
      }),
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([], 0, url),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:CREATE']));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/settlement-cases?'))).toBe(true));
    await screen.findAllByRole('table');
    expect(calls.some((c) => /\/v1\/settlement-cases\/\d+/.test(c.url))).toBe(false);
  });
});
