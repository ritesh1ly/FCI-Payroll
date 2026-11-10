import { describe, expect, it } from 'vitest';
import {
  claimProblems,
  EMPTY_CLAIM,
  isMedical,
  validAmount,
  yymm,
} from '@/modules/allowance/business/claimRules';

/** I-03 checks before a labour claim is sent (plan 10; SRS I-03 field table). */

const GOOD = {
  memberNumber: 'L10001',
  claimType: 'LABOUR_TA',
  periodFrom: '2026-10-01',
  periodTo: '2026-10-05',
  claimedAmount: '6000.50',
};

describe('I-03 claim rules', () => {
  it('SRS_11_13_aCompleteClaimHasNoProblem', () => {
    expect(claimProblems(GOOD)).toEqual([]);
  });

  it('SRS_11_13_anEmptyFormNamesEveryField', () => {
    expect(claimProblems(EMPTY_CLAIM)).toEqual([
      'i03.bad.member',
      'i03.bad.type',
      'i03.bad.period',
      'i03.bad.amount',
    ]);
  });

  it('SRS_11_6_aPeriodEndingBeforeItStartsIsSaid', () => {
    expect(claimProblems({ ...GOOD, periodTo: '2026-09-30' })).toEqual(['i03.bad.periodOrder']);
    expect(claimProblems({ ...GOOD, periodFrom: '01-10-2026' })).toEqual(['i03.bad.period']);
    expect(claimProblems({ ...GOOD, memberNumber: 'L-1' })).toEqual(['i03.bad.member']);
  });

  it('SRS_11_9_anAmountIsPositiveWithAtMostTwoDecimals', () => {
    expect(validAmount('5000')).toBe(true);
    expect(validAmount('5000.5')).toBe(true);
    expect(validAmount('5000.55')).toBe(true);
    expect(validAmount('5000.555')).toBe(false);
    expect(validAmount('0')).toBe(false);
    expect(validAmount('1.2.3')).toBe(false);
    expect(validAmount('abc')).toBe(false);
    expect(validAmount('12345678901')).toBe(false);
    expect(validAmount('')).toBe(false);
  });

  it('SRS_11_19_aMedicalTypeIsKnownByItsCode', () => {
    expect(isMedical('MEDICAL')).toBe(true);
    expect(isMedical('MEDICAL_INDOOR')).toBe(true);
    expect(isMedical('LABOUR_TA')).toBe(false);
  });

  it('SRS_11_23_aMonthInputBecomesYyyymm', () => {
    expect(yymm('2026-10')).toBe('202610');
    expect(yymm('2026-13')).toBeNull();
    expect(yymm('2026-00')).toBeNull();
    expect(yymm('')).toBeNull();
  });
});

describe('I-03 claim checks at their edges', () => {
  it('SRS-11-13: an empty draft is empty and names every missing field', () => {
    expect(EMPTY_CLAIM).toEqual({
      memberNumber: '',
      claimType: '',
      periodFrom: '',
      periodTo: '',
      claimedAmount: '',
    });
    expect(claimProblems(EMPTY_CLAIM)).toEqual([
      'i03.bad.member',
      'i03.bad.type',
      'i03.bad.period',
      'i03.bad.amount',
    ]);
  });

  it('SRS-11-13: the member number is trimmed and the dates are whole ISO days', () => {
    const ok = { ...GOOD, memberNumber: '  L10001  ' };
    expect(claimProblems(ok)).toEqual([]);
    expect(claimProblems({ ...GOOD, periodFrom: 'x2026-10-01' })).toContain('i03.bad.period');
    expect(claimProblems({ ...GOOD, periodTo: '2026-10-05x' })).toContain('i03.bad.period');
    expect(claimProblems({ ...GOOD, periodFrom: '2026-10-05', periodTo: '2026-10-05' })).toEqual([]);
    expect(claimProblems({ ...GOOD, periodFrom: '2026-10-06', periodTo: '2026-10-05' })).toEqual([
      'i03.bad.periodOrder',
    ]);
  });

  it('SRS-11-9: an amount has one point at most, ten digits and two decimals, trimmed', () => {
    expect(validAmount(' 12.5 ')).toBe(true);
    expect(validAmount('1.2.3')).toBe(false);
    expect(validAmount('.5')).toBe(false);
    expect(validAmount('5.')).toBe(false);
    expect(validAmount('12345678901')).toBe(false);
    expect(validAmount('1.234')).toBe(false);
  });

  it('SRS-11-23: a month is exactly YYYY-MM, January and December included', () => {
    expect(yymm('2026-01')).toBe('202601');
    expect(yymm('2026-12')).toBe('202612');
    expect(yymm('x2026-01')).toBeNull();
    expect(yymm('2026-01x')).toBeNull();
  });
});
