import { describe, expect, it } from 'vitest';
import { blankPeriod } from '@/modules/jobs/business/calendarRules';
import { ABANDON_AWAITING_APPROVAL, finYears } from '@/modules/jobs/business/jobRules';

/**
 * Mutation-gap tests for jobs rules.
 * Equivalent mutants, not tested: cycleRules.ts L25 Regex (the empty parts a single separator leaves
 * are skipped by `part !== ''`).
 */
describe('jobs rules mutation gaps', () => {
  it('JobRequestService: the pending command of an abandon proposal is spelled out', () => {
    expect(ABANDON_AWAITING_APPROVAL).toBe('ABANDON_AWAITING_APPROVAL');
  });

  it('financial years are listed newest first as YYYY-YY, across a century of two-digit endings', () => {
    expect(finYears('2026-07', 3)).toEqual(['2026-27', '2025-26', '2024-25']);
    expect(finYears('2027-02', 2)).toEqual(['2026-27', '2025-26']);
    expect(finYears('2099-04', 2)).toEqual(['2099-00', '2098-99']);
  });

  it('a blank calendar period ends on the last day of its month', () => {
    expect(blankPeriod('2026-04')).toMatchObject({ start: '2026-04-01', end: '2026-04-30' });
    expect(blankPeriod('2028-02').end).toBe('2028-02-29');
    expect(blankPeriod('2026-02').end).toBe('2026-02-28');
    expect(blankPeriod('2026-12').end).toBe('2026-12-31');
  });
});
