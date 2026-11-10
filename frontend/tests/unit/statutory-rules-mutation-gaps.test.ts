import { describe, expect, it } from 'vitest';
import type { NewFiling, StatutoryReturn, VersionProposal } from '@/modules/tax/api/statutoryApi';
import {
  correctable,
  filingProblems,
  latestFrom,
  reasonOf,
  versionProblems,
  whatIfProblems,
  whatIfRequest,
  type WhatIfDraft,
} from '@/modules/tax/business/statutoryRules';

/**
 * Mutation-gap tests for the C-07 / M-17 / C-11 client rules (SRS M-17, BR-TAX-013, SRS-7-28,
 * SRS-7-31): exact keys, anchors and trims that the first suites did not pin down.
 */

function version(over: Partial<VersionProposal> = {}): VersionProposal {
  return {
    effectiveFrom: '2027-04-01',
    layoutFormat: 'PDF',
    layoutNote: '',
    roundingRuleKey: '',
    periodLockRequired: true,
    fields: [{ label: 'Basic', binding: 'COMPONENT', reference: '101', formatMask: null }],
    controls: [],
    reason: 'Layout of the year',
    ...over,
  };
}

const control = (tolerance: string) =>
  ({ label: 'Tax', reconcilesAgainst: 'TDS_REGISTER', tolerance, releaseGate: false }) as never;

function ret(over: Partial<StatutoryReturn>): StatutoryReturn {
  return {
    id: 1,
    type: 'FORM_24Q',
    finYear: '2026-27',
    quarter: 'Q1',
    deductees: 1,
    totalTds: 100,
    status: 'RELEASED',
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

function filing(over: Partial<NewFiling> = {}): NewFiling {
  return {
    filingDate: '2026-10-25',
    signatoryName: 'R K Sharma',
    signatoryDesignation: 'Authorised Signatory',
    acknowledgementToken: 'TOKEN1',
    evidence: { documentDate: '', issuingAuthority: '' } as never,
    file: null,
    ...over,
  };
}

function draft(over: Partial<WhatIfDraft> = {}): WhatIfDraft {
  return { regime: 'OLD', claims: [], salaryChange: '', otherIncome: '', ...over };
}

describe('reasonOf', () => {
  it('SRS-7-8_aReasonStartingWithColonHasAnEmptyCodeAndTheRestAsArgument', () => {
    expect(reasonOf(':TAX.SLAB.OLD')).toEqual({ key: 'error.', args: ['TAX.SLAB.OLD'] });
    expect(reasonOf('TAX-422-X')).toEqual({ key: 'error.TAX-422-X', args: [] });
  });
});

describe('versionProblems gaps', () => {
  it('BR-TAX-013_fieldSourceReferenceIsAnchoredAtBothEnds', () => {
    const fs = (reference: string) =>
      version({ fields: [{ label: 'Src', binding: 'FIELD_SOURCE', reference, formatMask: null }] });
    expect(versionProblems(fs('emp_master.pan_no'), null)).toEqual([]);
    expect(versionProblems(fs('emp.pan!'), null)).toEqual(['m17.badFieldSource']);
    expect(versionProblems(fs('!emp.pan'), null)).toEqual(['m17.badFieldSource']);
  });

  it('BR-TAX-013_toleranceIsTrimmedAndAtMostOnePoint', () => {
    expect(versionProblems(version({ controls: [control(' 1.50 ')] }), null)).toEqual([]);
    expect(versionProblems(version({ controls: [control('1.2.3')] }), null)).toEqual(['m17.badTolerance']);
    expect(versionProblems(version({ controls: [control('12abc')] }), null)).toEqual(['m17.badTolerance']);
    expect(versionProblems(version({ controls: [control('abc12')] }), null)).toEqual(['m17.badTolerance']);
  });

  it('BR-TAX-013_effectiveFromIsAnchoredAtBothEnds', () => {
    expect(versionProblems(version({ effectiveFrom: '2027-04-01x' }), null)).toEqual(['m17.needFrom']);
    expect(versionProblems(version({ effectiveFrom: 'x2027-04-01' }), null)).toEqual(['m17.needFrom']);
  });

  it('BR-TAX-013_noLatestVersionMeansNoAfterLatestProblemAndAnEqualDateIsNotAfter', () => {
    expect(versionProblems(version(), null)).toEqual([]);
    expect(versionProblems(version({ effectiveFrom: '2027-04-01' }), '2027-04-01')).toEqual([
      'm17.afterLatest',
    ]);
    expect(versionProblems(version({ effectiveFrom: '2027-04-02' }), '2027-04-01')).toEqual([]);
  });

  it('BR-TAX-013_aLabelRepeatedWithPaddingIsADuplicate', () => {
    const fields = [
      { label: ' Basic ', binding: 'COMPONENT' as const, reference: '101', formatMask: null },
      { label: 'Basic', binding: 'COMPONENT' as const, reference: '102', formatMask: null },
    ];
    expect(versionProblems(version({ fields }), null)).toEqual(['m17.duplicateLabel']);
  });
});

describe('latestFrom', () => {
  it('BR-TAX-013_theLatestDateWinsInAnyOrder', () => {
    expect(latestFrom([])).toBeNull();
    expect(latestFrom([{ effectiveFrom: '2026-04-01' }, { effectiveFrom: '2027-04-01' }])).toBe('2027-04-01');
    expect(latestFrom([{ effectiveFrom: '2027-04-01' }, { effectiveFrom: '2026-04-01' }])).toBe('2027-04-01');
  });
});

describe('correctable gaps', () => {
  it('SRS-7-7_aFiledOriginalIsCorrectableAndOnlyACorrectionMarksAnotherAsCorrected', () => {
    const filed = ret({ id: 1, status: 'FILED' });
    expect(correctable([filed]).map((r) => r.id)).toEqual([1]);
    // a non-correction row never marks another as corrected, whatever its pointer holds
    const stray = ret({ id: 2, quarter: 'Q2', status: 'GENERATED', correctsReturnId: 1 });
    expect(correctable([filed, stray]).map((r) => r.id)).toEqual([1]);
    // a real correction marks its original corrected
    const fix = ret({ id: 3, correction: true, correctsReturnId: 1, status: 'GENERATED' });
    expect(correctable([filed, fix])).toEqual([]);
  });
});

describe('filingProblems gaps', () => {
  it('SRS-7-31_filingDateIsAnchoredAtBothEnds', () => {
    expect(filingProblems(filing({ filingDate: '2026-10-25x' }), '2026-11-06')).toEqual([
      'c11.needFilingDate',
    ]);
    expect(filingProblems(filing({ filingDate: 'x2026-10-25' }), '2026-11-06')).toEqual([
      'c11.needFilingDate',
    ]);
  });

  it('SRS-7-31_aBlankDesignationAloneNeedsTheSignatory', () => {
    expect(filingProblems(filing({ signatoryDesignation: '   ' }), '2026-11-06')).toEqual([
      'c11.needSignatory',
    ]);
  });
});

describe('whatIfProblems gaps', () => {
  it('SRS-7-28_aBlankClaimRowIsIgnoredWhateverItsPadding', () => {
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '   ', amount: '' }] }))).toEqual([]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '', amount: '   ' }] }))).toEqual([]);
  });

  it('SRS-7-28_claimsWithDifferentCodesAreNotDuplicates', () => {
    const claims = [
      { sectionCode: '80C', amount: '100' },
      { sectionCode: '80D', amount: '50' },
    ];
    expect(whatIfProblems(draft({ claims }))).toEqual([]);
    expect(
      whatIfProblems(
        draft({
          claims: [
            { sectionCode: '80C', amount: '100' },
            { sectionCode: ' 80C ', amount: '5' },
          ],
        }),
      ),
    ).toEqual(['c07w.duplicateClaim']);
  });

  it('SRS-7-28_aClaimAmountIsTrimmedButAtMostOnePoint', () => {
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: ' 100 ' }] }))).toEqual([]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '1.2.3' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '12abc' }] }))).toEqual([
      'c07w.badClaim',
    ]);
  });

  it('SRS-7-28_blankAndPaddedSalaryChangeAndOtherIncome', () => {
    expect(whatIfProblems(draft({ salaryChange: '   ', otherIncome: '   ' }))).toEqual([]);
    expect(whatIfProblems(draft({ salaryChange: ' -500 ', otherIncome: ' 700 ' }))).toEqual([]);
    expect(whatIfProblems(draft({ salaryChange: 'x' }))).toEqual(['c07w.badSalary']);
    expect(whatIfProblems(draft({ otherIncome: '-5' }))).toEqual(['c07w.badIncome']);
  });

  it('SRS-7-28_nothingVariedWhenOnlyBlankPaddedFieldsAreGiven', () => {
    expect(
      whatIfProblems(
        draft({
          regime: '',
          claims: [{ sectionCode: ' ', amount: '' }],
          salaryChange: ' ',
          otherIncome: ' ',
        }),
      ),
    ).toEqual(['c07w.nothingVaried']);
  });
});

describe('whatIfRequest gaps', () => {
  it('SRS-7-28_blankPaddedFieldsBecomeNullAndBlankClaimsAreDropped', () => {
    expect(
      whatIfRequest(
        draft({
          regime: '',
          claims: [
            { sectionCode: '  ', amount: '10' },
            { sectionCode: ' 80C ', amount: ' 150000 ' },
          ],
          salaryChange: '   ',
          otherIncome: '  ',
        }),
      ),
    ).toEqual({
      regime: null,
      claims: [{ sectionCode: '80C', amount: 150000 }],
      salaryChange: null,
      otherIncome: null,
    });
    expect(whatIfRequest(draft({ salaryChange: ' -500 ', otherIncome: ' 7.5 ' }))).toMatchObject({
      regime: 'OLD',
      salaryChange: -500,
      otherIncome: 7.5,
    });
  });
});
