import { describe, expect, it } from 'vitest';
import { draftProblem, LEAVE_PARAMETERS } from '@/modules/leave/business/leaveRules';

/** Mutation-gap tests for leaveRules.ts (SRS-LV-14, M-20 field table). */
describe('leaveRules mutation gaps', () => {
  const draft = (over: Record<string, string> = {}) => ({
    code: 'ENCASH_DIVISOR',
    leaveType: '',
    value: '30',
    effectiveFrom: '2026-07-01',
    reason: 'Circular 14/2026',
    ...over,
  });

  it('SRS-LV-14: each M-20 parameter keeps its kind', () => {
    expect(LEAVE_PARAMETERS).toEqual({
      LWP_DIVISOR: 'DIVISOR',
      ENCASH_DIVISOR: 'POSITIVE',
      ENCASH_PAY_ELEMENTS: 'PAY_CODES',
      INSERVICE_PER_OCCASION_LIMIT: 'DAYS',
      INSERVICE_PER_YEAR_LIMIT: 'DAYS',
      SEPARATION_AGGREGATE_CEILING: 'DAYS',
      ENCASH_PAY_CODES: 'PAY_CODES',
      PAY_EFFECT: 'PAY_EFFECT',
    });
  });

  it('SRS-LV-14: the date is exactly YYYY-MM-DD', () => {
    expect(draftProblem(draft())).toBeNull();
    expect(draftProblem(draft({ effectiveFrom: 'x2026-07-01' }))).toBe('m20.problem.date');
    expect(draftProblem(draft({ effectiveFrom: '2026-07-01x' }))).toBe('m20.problem.date');
  });

  it('SRS-LV-14: a blank days limit is refused, a typed one is trimmed', () => {
    const days = { code: 'INSERVICE_PER_YEAR_LIMIT', leaveType: 'EL' };
    expect(draftProblem(draft({ ...days, value: '   ' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ ...days, value: ' 0 ' }))).toBeNull();
  });

  it('SRS-LV-14: a divisor must be CALENDAR_DAYS or above zero', () => {
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: '0' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: '' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: ' CALENDAR_DAYS ' }))).toBeNull();
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: '26' }))).toBeNull();
  });

  it('SRS-LV-14: pay codes are checked after trimming', () => {
    expect(draftProblem(draft({ code: 'ENCASH_PAY_CODES', value: ' 101+102 ' }))).toBeNull();
  });
});
