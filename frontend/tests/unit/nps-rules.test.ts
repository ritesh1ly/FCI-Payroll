import { describe, expect, it } from 'vitest';
import { isMismatch, isYymm, NPS_STATUSES, reportRangeOk, yymmOf } from '@/modules/nps/business/npsRules';

/** I-14 NPS Deduction screen rules (plan 18 phase 18c; SRS-9-1a, SRS-9-6). */
describe('I-14 NPS rules', () => {
  it('SRS-9-1a: only a flag / amount mismatch holds the lock, and mismatches are listed first', () => {
    expect(isMismatch('FLAG_WITHOUT_AMOUNT')).toBe(true);
    expect(isMismatch('AMOUNT_WITHOUT_FLAG')).toBe(true);
    expect(isMismatch('RECEIVED')).toBe(false);
    expect(isMismatch('APPLIED')).toBe(false);
    expect(isMismatch('STOPPED_SEPARATION')).toBe(false);
    expect(NPS_STATUSES.slice(0, 2)).toEqual(['FLAG_WITHOUT_AMOUNT', 'AMOUNT_WITHOUT_FLAG']);
    expect(NPS_STATUSES).toEqual([
      'FLAG_WITHOUT_AMOUNT',
      'AMOUNT_WITHOUT_FLAG',
      'RECEIVED',
      'APPLIED',
      'STOPPED_SEPARATION',
    ]);
  });

  it('SRS-9-1: a month is YYYYMM, January to December', () => {
    expect(isYymm('202610')).toBe(true);
    expect(isYymm('202601')).toBe(true);
    expect(isYymm('202612')).toBe(true);
    expect(isYymm('202600')).toBe(false);
    expect(isYymm('202613')).toBe(false);
    expect(isYymm('2026-10')).toBe(false);
    expect(isYymm('20261')).toBe(false);
    expect(isYymm('x202610')).toBe(false);
    expect(isYymm('2026101')).toBe(false);
    expect(yymmOf('2026-10-26')).toBe('202610');
  });

  it('SRS-9-6: the report covers from .. to, both months, in order', () => {
    expect(reportRangeOk('202604', '202610')).toBe(true);
    expect(reportRangeOk('202610', '202610')).toBe(true);
    expect(reportRangeOk('202611', '202610')).toBe(false);
    expect(reportRangeOk('2026', '202610')).toBe(false);
    expect(reportRangeOk('202604', '202613')).toBe(false);
  });
});
