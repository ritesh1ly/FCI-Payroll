import { describe, expect, it } from 'vitest';
import type { StatutoryReturn } from '@/modules/tax/api/statutoryApi';
import {
  challanProblems,
  correctable,
  whatIfProblems,
  whatIfRequest,
  type WhatIfDraft,
} from '@/modules/tax/business/statutoryRules';

/**
 * C-11 keyed challan, C-11 correction originals and the C-07 what-if, each with its boundaries
 * (SRS-7-7, SRS-7-21, SRS C-11 l.2625, SRS-7-28): pinned in a rules test so mutation testing
 * reaches them.
 */

function ret(over: Partial<StatutoryReturn>): StatutoryReturn {
  return {
    id: 1,
    type: 'FORM_24Q',
    finYear: '2026-27',
    quarter: 'Q1',
    deductees: 1,
    totalTds: 100,
    status: 'GENERATED',
    validated: false,
    validationUtility: null,
    validationResult: [],
    correction: false,
    correctsReturnId: null,
    generatedAt: null,
    releasedBy: null,
    filings: [],
    ...over,
  };
}

describe('SRS-7-7 correctable originals', () => {
  it('SRS-7-7: a filed original not yet corrected is correctable', () => {
    expect(correctable([ret({ id: 1, status: 'FILED' })]).map((r) => r.id)).toEqual([1]);
    expect(correctable([ret({ id: 1, status: 'REJECTED' })])).toEqual([]);
  });
});

describe('SRS C-11 l.2625 keyed challan', () => {
  const ok = {
    cin: 'CIN0001',
    bsr: '0510308',
    date: '2026-10-07',
    amount: '1250.50',
    reference: 'Oct TDS',
    period: 'Q2',
  };
  const problems = (over: Partial<typeof ok>) => challanProblems({ ...ok, ...over });

  it('SRS-7-21: a complete challan has no problem', () => {
    expect(problems({})).toEqual([]);
    expect(problems({ cin: ' CIN0001 ', bsr: ' 0510308 ', amount: ' 1 ', period: ' 202610 ' })).toEqual([]);
  });

  it('SRS-7-21: the CIN is 1 to 30 letters or digits, whole', () => {
    expect(problems({ cin: 'C'.repeat(30) })).toEqual([]);
    expect(problems({ cin: 'C'.repeat(31) })).toEqual(['c11.problem.cin']);
    expect(problems({ cin: '' })).toEqual(['c11.problem.cin']);
    expect(problems({ cin: 'CIN-1' })).toEqual(['c11.problem.cin']);
  });

  it('SRS-7-21: the BSR code is 1 to 20 letters or digits, whole', () => {
    expect(problems({ bsr: '0' })).toEqual([]);
    expect(problems({ bsr: '0'.repeat(20) })).toEqual([]);
    expect(problems({ bsr: '0'.repeat(21) })).toEqual(['c11.problem.bsr']);
    expect(problems({ bsr: '' })).toEqual(['c11.problem.bsr']);
    expect(problems({ bsr: '-0510308' })).toEqual(['c11.problem.bsr']);
    expect(problems({ bsr: '0510308-' })).toEqual(['c11.problem.bsr']);
    expect(problems({ bsr: '---' })).toEqual(['c11.problem.bsr']);
  });

  it('SRS-7-21: the date is required', () => {
    expect(problems({ date: '' })).toEqual(['c11.problem.date']);
  });

  it('SRS-7-21: the amount is above 0, 1 to 16 rupee digits and at most two paise digits', () => {
    for (const a of ['0.01', '1', '1.5', '1234567890123456', '1234567890123456.99'])
      expect(problems({ amount: a })).toEqual([]);
    for (const a of [
      '',
      '0',
      '0.00',
      '-1',
      '1.234',
      '1.2.3',
      'x1',
      '1x',
      '12345678901234567',
      '.5',
      '1.',
      'abc',
    ]) {
      expect(problems({ amount: a })).toEqual(['c11.problem.amount']);
    }
  });

  it('SRS-7-21: the reference is at most 50 characters without a comma', () => {
    expect(problems({ reference: 'R'.repeat(50) })).toEqual([]);
    expect(problems({ reference: '' })).toEqual([]);
    expect(problems({ reference: 'R'.repeat(51) })).toEqual(['c11.problem.reference']);
    expect(problems({ reference: 'a,b' })).toEqual(['c11.problem.reference']);
  });

  it('SRS-7-21: the period is a quarter Q1 to Q4 or a month of six digits', () => {
    for (const p of ['Q1', 'Q4', '202610']) expect(problems({ period: p })).toEqual([]);
    for (const p of ['Q0', 'Q5', 'QA', 'Q1x', 'xQ1', '20261', '2026100', 'abcdef', '2', '']) {
      expect(problems({ period: p })).toEqual(['c11.problem.period']);
    }
  });

  it('SRS-7-21: every problem of the challan is listed, in field order', () => {
    expect(challanProblems({ cin: '', bsr: '', date: '', amount: '', reference: ',', period: '' })).toEqual([
      'c11.problem.cin',
      'c11.problem.bsr',
      'c11.problem.date',
      'c11.problem.amount',
      'c11.problem.reference',
      'c11.problem.period',
    ]);
  });
});

describe('SRS-7-28 what-if, trimmed values', () => {
  const draft = (over: Partial<WhatIfDraft> = {}): WhatIfDraft => ({
    regime: '',
    claims: [],
    salaryChange: '',
    otherIncome: '',
    ...over,
  });

  it('SRS-7-28: claims, the salary change and other income are checked trimmed', () => {
    expect(whatIfProblems(draft({ claims: [{ sectionCode: ' 80C ', amount: ' 5 ' }] }))).toEqual([]);
    expect(
      whatIfProblems(
        draft({
          claims: [
            { sectionCode: '80C', amount: '5' },
            { sectionCode: '80D', amount: '6' },
          ],
        }),
      ),
    ).toEqual([]);
    expect(whatIfProblems(draft({ salaryChange: ' -5 ' }))).toEqual([]);
    expect(whatIfProblems(draft({ otherIncome: ' 5 ' }))).toEqual([]);
    expect(whatIfProblems(draft({ regime: 'NEW', salaryChange: '  ', otherIncome: '  ' }))).toEqual([]);
  });

  it('SRS-7-28: an amount with two points is refused', () => {
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '1.2.3' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(whatIfProblems(draft({ salaryChange: '-1.2.3' }))).toEqual(['c07w.badSalary']);
    expect(whatIfProblems(draft({ otherIncome: '1.2.3' }))).toEqual(['c07w.badIncome']);
  });

  it('SRS-7-28: a blank salary change or other income is "as approved" (null), not zero', () => {
    expect(whatIfRequest(draft({ regime: 'OLD', salaryChange: '  ', otherIncome: '  ' }))).toEqual({
      regime: 'OLD',
      claims: [],
      salaryChange: null,
      otherIncome: null,
    });
    expect(whatIfRequest(draft({ salaryChange: ' -5 ', otherIncome: ' 7.5 ' }))).toMatchObject({
      regime: null,
      salaryChange: -5,
      otherIncome: 7.5,
    });
  });
});

describe('SRS-7-28 what-if, blank claim rows', () => {
  const draft = (over: Partial<WhatIfDraft> = {}): WhatIfDraft => ({
    regime: 'NEW',
    claims: [],
    salaryChange: '',
    otherIncome: '',
    ...over,
  });

  it('SRS-7-28: a claim row of blanks is an empty row, neither checked nor sent', () => {
    const blankRows = [
      { sectionCode: '  ', amount: '' },
      { sectionCode: '', amount: '  ' },
    ];
    expect(whatIfProblems(draft({ claims: blankRows }))).toEqual([]);
    expect(whatIfProblems(draft({ regime: '', claims: blankRows }))).toEqual(['c07w.nothingVaried']);
    expect(
      whatIfRequest(draft({ claims: [...blankRows, { sectionCode: ' 80C ', amount: '5' }] })).claims,
    ).toEqual([{ sectionCode: '80C', amount: 5 }]);
  });
});
