import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FlaggedTdsCases } from '@/modules/tax';
import type { FlaggedTdsCase } from '@/modules/tax/api/taxApi';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * C-01 ▸ TDS cases for review (US-08-07; FRS V0.5 §14.4 Step 4; SRS-7-14, BR-TAX-001): the members
 * whose TDS changed by a proof-deadline reversal or an approved regime reset, read-only, with the
 * round or the approval item they came from; an empty period says so; a failed read is shown.
 */
afterEach(() => vi.unstubAllGlobals());

const REVERSAL: FlaggedTdsCase = {
  employeeId: 7,
  eis: '104882',
  name: 'Ravi Sharma',
  office: 'DO Delhi',
  flag: 'PROOF_DEADLINE_REVERSAL',
  reference: 41,
  reason: 'Proofs not submitted by the deadline',
  at: '2026-10-20T05:30:00Z',
};
const RESET: FlaggedTdsCase = {
  employeeId: 8,
  eis: '105220',
  name: 'Meena Iyer',
  office: null,
  flag: 'REGIME_RESET',
  reference: 900,
  reason: null,
  at: null,
};
const CHECKER = me(['C-01:VIEW']);

describe('C-01 TDS cases for review', () => {
  it('US-08-07: a proof reversal names its round and a regime reset its approval item', async () => {
    const calls = backend({ 'GET /v1/tax/flagged-tds-cases': () => paged([REVERSAL, RESET]) });
    wrap(<FlaggedTdsCases period="202610" />, CHECKER);
    expect(await screen.findByText('104882')).toBeInTheDocument();
    expect(screen.getByText(t('c01.tdsFlag.PROOF_DEADLINE_REVERSAL'))).toBeInTheDocument();
    expect(screen.getByText(t('c01.tdsRound', [41]))).toBeInTheDocument();
    expect(screen.getByText('Proofs not submitted by the deadline')).toBeInTheDocument();
    expect(screen.getByText(t('c01.tdsFlag.REGIME_RESET'))).toBeInTheDocument();
    expect(screen.getByText(t('c01.tdsApproval', [900]))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('period=202610'))).toBe(true);
    // Read-only: no action on the card.
    expect(screen.queryByRole('button', { name: /approve|reject/i })).toBeNull();
  });

  it('US-08-07: a period without a flagged case says so', async () => {
    backend({ 'GET /v1/tax/flagged-tds-cases': () => paged([]) });
    wrap(<FlaggedTdsCases period="202610" />, CHECKER);
    expect(await screen.findByText(t('c01.noTdsReview'))).toBeInTheDocument();
  });

  it('US-08-07: a list that cannot be read is shown, never an empty list', async () => {
    backend({ 'GET /v1/tax/flagged-tds-cases': () => problem(403, 'AUTH-403-SCOPE') });
    wrap(<FlaggedTdsCases period="202610" />, CHECKER);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText(t('c01.noTdsReview'))).toBeNull();
  });
});
