import { describe, expect, it } from 'vitest';
import type {
  Certificate,
  NewFiling,
  StatutoryReturn,
  VersionProposal,
} from '@/modules/tax/api/statutoryApi';
import {
  challanFileProblem,
  correctable,
  filingProblems,
  isFinYear,
  latestFrom,
  mayReissue,
  mayUploadPartA,
  membersOf,
  mergedCount,
  backgroundJobLink,
  openQuarters,
  quarterMonths,
  reasonOf,
  versionProblems,
  whatIfProblems,
  whatIfRequest,
  type WhatIfDraft,
} from '@/modules/tax/business/statutoryRules';

/**
 * The client checks of C-07, M-17, C-10 and C-11 (SRS M-17, BR-TAX-012, BR-TAX-013, SRS-7-7,
 * SRS-7-8, SRS-7-21, SRS-7-31): what the server refuses is said before the request is sent.
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

const field = (label: string, binding: VersionProposal['fields'][number]['binding'], reference: string) => ({
  label,
  binding,
  reference,
  formatMask: null,
});

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

function filing(over: Partial<NewFiling> = {}): NewFiling {
  return {
    filingDate: '2026-10-25',
    signatoryName: 'R K Sharma',
    signatoryDesignation: 'Authorised Signatory',
    acknowledgementToken: 'TOKEN1',
    evidence: {
      documentType: 'CERTIFICATE',
      reference: '',
      documentDate: '2026-10-25',
      issuingAuthority: 'TIN-FC',
    },
    file: null,
    ...over,
  };
}

const cert = (status: Certificate['status']) => ({ status }) as Certificate;

describe('C-07 reasons', () => {
  it('SRS_7_26_aReasonIsItsMessageKeyAndItsArgument', () => {
    expect(reasonOf('TAX-422-REGIME-REQUIRED')).toEqual({ key: 'error.TAX-422-REGIME-REQUIRED', args: [] });
    expect(reasonOf('TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD')).toEqual({
      key: 'error.TAX-422-RATE-NOT-CONFIGURED',
      args: ['TAX.SLAB.OLD'],
    });
    expect(reasonOf('TAX-422-RATE-NOT-CONFIGURED:pt_state_master PB').args).toEqual(['pt_state_master PB']);
  });

  it('SRS_7_1_aFinancialYearIsYyyyDashYy', () => {
    expect(isFinYear('2026-27')).toBe(true);
    expect(isFinYear('2026')).toBe(false);
    expect(isFinYear('2026-2027')).toBe(false);
    expect(isFinYear(' 2026-27')).toBe(false);
  });
});

describe('M-17 version', () => {
  it('BR_TAX_013_aValidVersionHasNoProblem', () => {
    expect(versionProblems(version(), null)).toEqual([]);
    expect(
      versionProblems(
        version({
          fields: [
            field('Basic', 'COMPONENT', '101'),
            field('PAN', 'FIELD_SOURCE', 'employee.pan_number'),
            field('Total', 'AGGREGATE', 'SUM'),
            field('Gross', 'DERIVED', 'BASIC + DA'),
            field('Form', 'LITERAL', 'Form 16'),
          ],
          controls: [{ label: 'TDS', reconcilesAgainst: 'CHALLAN', tolerance: '0.50', releaseGate: true }],
          roundingRuleKey: 'ROUND.RUPEE_1',
          layoutNote: 'Layout of FCI',
        }),
        '2026-04-01',
      ),
    ).toEqual([]);
  });

  it('BR_MIS_002_theVersionStartsAfterTheLatestOne', () => {
    expect(versionProblems(version({ effectiveFrom: '' }), null)).toEqual(['m17.needFrom']);
    expect(versionProblems(version({ effectiveFrom: '01-04-2027' }), null)).toEqual(['m17.needFrom']);
    expect(versionProblems(version({ effectiveFrom: '2026-04-01' }), '2026-04-01')).toEqual([
      'm17.afterLatest',
    ]);
    expect(versionProblems(version({ effectiveFrom: '2026-03-31' }), '2026-04-01')).toEqual([
      'm17.afterLatest',
    ]);
    expect(versionProblems(version({ effectiveFrom: '2026-04-02' }), '2026-04-01')).toEqual([]);
  });

  it('BR_TAX_013_everyFieldHasALabelOnceAndAReferenceThatResolves', () => {
    expect(versionProblems(version({ fields: [] }), null)).toEqual(['m17.needField']);
    expect(versionProblems(version({ fields: [field(' ', 'COMPONENT', '101')] }), null)).toEqual([
      'm17.badLabel',
    ]);
    expect(versionProblems(version({ fields: [field('x'.repeat(201), 'COMPONENT', '101')] }), null)).toEqual([
      'm17.badLabel',
    ]);
    expect(versionProblems(version({ fields: [field('x'.repeat(200), 'COMPONENT', '101')] }), null)).toEqual(
      [],
    );
    expect(versionProblems(version({ fields: [field('मूल', 'COMPONENT', '101')] }), null)).toEqual([
      'm17.badLabel',
    ]);
    expect(
      versionProblems(
        version({ fields: [field('Basic', 'COMPONENT', '101'), field(' Basic ', 'COMPONENT', '102')] }),
        null,
      ),
    ).toEqual(['m17.duplicateLabel']);
    expect(versionProblems(version({ fields: [field('A', 'COMPONENT', ' ')] }), null)).toEqual([
      'm17.needReference',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'AGGREGATE', 'AVG')] }), null)).toEqual([
      'm17.badAggregate',
    ]);
    for (const a of ['SUM', 'COUNT', 'MIN', 'MAX']) {
      expect(versionProblems(version({ fields: [field('A', 'AGGREGATE', a)] }), null)).toEqual([]);
    }
    expect(versionProblems(version({ fields: [field('A', 'COMPONENT', 'BASIC')] }), null)).toEqual([
      'm17.badComponent',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'COMPONENT', '1234567')] }), null)).toEqual([
      'm17.badComponent',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'FIELD_SOURCE', 'employee')] }), null)).toEqual([
      'm17.badFieldSource',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'FIELD_SOURCE', 'Employee.PAN')] }), null)).toEqual([
      'm17.badFieldSource',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'LITERAL', 'x'.repeat(1001))] }), null)).toEqual([
      'm17.badReference',
    ]);
    expect(versionProblems(version({ fields: [field('A', 'LITERAL', 'x'.repeat(1000))] }), null)).toEqual([]);
    expect(versionProblems(version({ fields: [field('A', 'LITERAL', 'फ़ॉर्म')] }), null)).toEqual([
      'm17.badReference',
    ]);
  });

  it('SRS_7_8_eachControlTotalHasALabelAndAPaiseTolerance', () => {
    const control = (label: string, tolerance: string) => ({
      label,
      reconcilesAgainst: 'POSTED_PAYROLL' as const,
      tolerance,
      releaseGate: false,
    });
    expect(versionProblems(version({ controls: [control('TDS', '0')] }), null)).toEqual([]);
    expect(versionProblems(version({ controls: [control('TDS', '12.5')] }), null)).toEqual([]);
    expect(versionProblems(version({ controls: [control('TDS', '1.005')] }), null)).toEqual([
      'm17.badTolerance',
    ]);
    expect(versionProblems(version({ controls: [control('TDS', '-1')] }), null)).toEqual([
      'm17.badTolerance',
    ]);
    expect(versionProblems(version({ controls: [control('TDS', '')] }), null)).toEqual(['m17.badTolerance']);
    expect(versionProblems(version({ controls: [control('', '0')] }), null)).toEqual(['m17.badLabel']);
  });

  it('BR_MIS_002_theRoundingKeyTheNoteAndTheReason', () => {
    expect(versionProblems(version({ roundingRuleKey: 'round' }), null)).toEqual(['m17.badRounding']);
    expect(versionProblems(version({ roundingRuleKey: 'R'.repeat(101) }), null)).toEqual(['m17.badRounding']);
    expect(versionProblems(version({ layoutNote: 'x'.repeat(4001) }), null)).toEqual(['m17.badNote']);
    expect(versionProblems(version({ layoutNote: 'x'.repeat(4000) }), null)).toEqual([]);
    expect(versionProblems(version({ layoutNote: 'टिप्पणी' }), null)).toEqual(['m17.badNote']);
    expect(versionProblems(version({ reason: ' ' }), null)).toEqual(['m17.needReason']);
    expect(versionProblems(version({ reason: 'कारण' }), null)).toEqual(['m17.needReason']);
    // Several problems are listed once each.
    expect(
      versionProblems(
        version({
          effectiveFrom: '',
          fields: [field('', 'COMPONENT', ''), field('', 'COMPONENT', '')],
          reason: '',
        }),
        null,
      ),
    ).toEqual(['m17.needFrom', 'm17.badLabel', 'm17.duplicateLabel', 'm17.needReason']);
  });

  it('BR_MIS_002_theLatestVersionStart', () => {
    expect(latestFrom([])).toBeNull();
    expect(
      latestFrom([
        { effectiveFrom: '2026-04-01' },
        { effectiveFrom: '2027-04-01' },
        { effectiveFrom: '2025-04-01' },
      ]),
    ).toBe('2027-04-01');
  });
});

describe('C-10 certificates', () => {
  it('SRS_7_9_partAUntilReleaseAndReissueOnlyAfter', () => {
    expect(mayUploadPartA(cert('PENDING_PART_A'))).toBe(true);
    expect(mayUploadPartA(cert('MERGED'))).toBe(true);
    expect(mayUploadPartA(cert('RELEASED'))).toBe(false);
    expect(mayUploadPartA(cert('DRAFT'))).toBe(false);
    expect(mayReissue(cert('RELEASED'))).toBe(true);
    expect(mayReissue(cert('MERGED'))).toBe(false);
    expect(mergedCount([cert('MERGED'), cert('RELEASED'), cert('MERGED')])).toBe(2);
    expect(mergedCount([])).toBe(0);
  });
});

describe('C-10 member population', () => {
  it('SRS_7_9_theMembersAreEisNumbersAndNoneMeansTheWholeUnit', () => {
    expect(membersOf('')).toEqual({ eis: [], ok: true });
    expect(membersOf('  ')).toEqual({ eis: [], ok: true });
    expect(membersOf('870001, 870003 870001	870005')).toEqual({
      eis: ['870001', '870003', '870005'],
      ok: true,
    });
    expect(membersOf('870001,87-0003').ok).toBe(false);
    expect(membersOf('x'.repeat(21)).ok).toBe(false);
    expect(membersOf('x'.repeat(20)).ok).toBe(true);
    const many = Array.from({ length: 1001 }, (_, i) => `E${i}`).join(',');
    expect(membersOf(many).ok).toBe(false);
    expect(membersOf(Array.from({ length: 1000 }, (_, i) => `E${i}`).join(',')).ok).toBe(true);
  });
});

describe('C-11 returns', () => {
  it('SRS_7_7_theMonthsOfEachQuarter', () => {
    expect(quarterMonths('2026-27', 'Q1')).toEqual(['202604', '202605', '202606']);
    expect(quarterMonths('2026-27', 'Q2')).toEqual(['202607', '202608', '202609']);
    expect(quarterMonths('2026-27', 'Q3')).toEqual(['202610', '202611', '202612']);
    expect(quarterMonths('2026-27', 'Q4')).toEqual(['202701', '202702', '202703']);
  });

  it('SRS_NF_7_theBackgroundRunOpensTheC13FormWithTheJobAndItsYear', () => {
    expect(backgroundJobLink('TAX_24Q_RETURN', { finYear: '2025-26', quarter: 'Q2' })).toBe(
      '/payroll?s=C-13&job=TAX_24Q_RETURN&finYear=2025-26&quarter=Q2',
    );
    expect(backgroundJobLink('TAX_FORM16_PART_B', { finYear: '2025-26' })).toBe(
      '/payroll?s=C-13&job=TAX_FORM16_PART_B&finYear=2025-26',
    );
  });

  it('SRS_7_7_oneOriginalPerQuarterAndOneCorrectionPerReturn', () => {
    const rows = [
      ret({ id: 1, quarter: 'Q1', status: 'FILED' }),
      ret({ id: 2, quarter: 'Q2', status: 'RELEASED' }),
      ret({ id: 3, quarter: 'Q3', status: 'GENERATED' }),
      ret({
        id: 4,
        quarter: 'Q1',
        type: 'FORM_24Q_CORRECTION',
        correction: true,
        correctsReturnId: 1,
        status: 'FILED',
      }),
    ];
    // Q3 is only generated: it is regenerated in place; Q1 and Q2 are corrected instead.
    expect(openQuarters(rows)).toEqual(['Q3', 'Q4']);
    expect(openQuarters([])).toEqual(['Q1', 'Q2', 'Q3', 'Q4']);
    expect(
      openQuarters([
        ret({ id: 5, quarter: 'Q1', status: 'DRAFT' }),
        ret({ id: 6, quarter: 'Q2', status: 'VALIDATED' }),
        ret({ id: 7, quarter: 'Q3', status: 'REJECTED' }),
      ]),
    ).toEqual(['Q1', 'Q2', 'Q4']);
    // Return 1 is corrected already; 3 is not released; a correction is never corrected.
    expect(correctable(rows).map((r) => r.id)).toEqual([2]);
    expect(correctable([ret({ id: 9, status: 'VALIDATED' })])).toEqual([]);
  });

  it('SRS_7_31_theFilingIsDatedNoLaterThanTodayAndNamesTheSignatory', () => {
    const today = '2026-10-25';
    expect(filingProblems(filing(), today)).toEqual([]);
    expect(filingProblems(filing({ filingDate: '' }), today)).toEqual(['c11.needFilingDate']);
    expect(filingProblems(filing({ filingDate: '2026-10-26' }), today)).toEqual(['c11.filingAhead']);
    expect(filingProblems(filing({ signatoryName: ' ' }), today)).toEqual(['c11.needSignatory']);
    expect(filingProblems(filing({ signatoryDesignation: '' }), today)).toEqual(['c11.needSignatory']);
    expect(filingProblems(filing({ acknowledgementToken: 'टोकन' }), today)).toEqual(['c11.printable']);
    expect(filingProblems(filing({ signatoryName: 'नाम' }), today)).toEqual(['c11.printable']);
    const file = new File(['%PDF'], 'ack.pdf');
    expect(filingProblems(filing({ file }), today)).toEqual([]);
    expect(
      filingProblems(filing({ file, evidence: { ...filing().evidence, issuingAuthority: ' ' } }), today),
    ).toEqual(['c11.needEvidence']);
    expect(
      filingProblems(filing({ file, evidence: { ...filing().evidence, documentDate: '' } }), today),
    ).toEqual(['c11.needEvidence']);
  });

  it('SRS_7_21_theChallanFileIsChosenNotEmptyAndAtMostTenMegabytes', () => {
    expect(challanFileProblem(null)).toBe('c11.needFile');
    expect(challanFileProblem(new File([], 'c.csv'))).toBe('c11.emptyFile');
    expect(challanFileProblem(new File(['cin'], 'c.csv'))).toBeNull();
    const big = new File(['x'], 'c.csv');
    Object.defineProperty(big, 'size', { value: 10 * 1024 * 1024 + 1 });
    expect(challanFileProblem(big)).toBe('c11.fileTooLarge');
    const limit = new File(['x'], 'c.csv');
    Object.defineProperty(limit, 'size', { value: 10 * 1024 * 1024 });
    expect(challanFileProblem(limit)).toBeNull();
  });
});

describe('edges the server refuses too', () => {
  const control = (tolerance: string) => ({
    label: 'TDS',
    reconcilesAgainst: 'CHALLAN' as const,
    tolerance,
    releaseGate: true,
  });

  it('BR_TAX_013_aToleranceIsOneAmountOfAtMostTwelveRupeeDigits', () => {
    expect(versionProblems(version({ controls: [control('1.2.3')] }), null)).toEqual(['m17.badTolerance']);
    expect(versionProblems(version({ controls: [control('5x')] }), null)).toEqual(['m17.badTolerance']);
    expect(versionProblems(version({ controls: [control(' 5 ')] }), null)).toEqual([]);
  });

  it('SRS_7_26_aReasonWithAnEmptyCodeKeepsItsArgument', () => {
    expect(reasonOf(':X')).toEqual({ key: 'error.', args: ['X'] });
  });

  it('BR_TAX_013_aFieldSourceIsExactlyTableDotColumn', () => {
    for (const ref of ['employee.pan-x', '1employee.pan']) {
      expect(versionProblems(version({ fields: [field('PAN', 'FIELD_SOURCE', ref)] }), null)).toEqual([
        'm17.badFieldSource',
      ]);
    }
  });

  it('BR_TAX_013_theStartIsExactlyADateAndALabelIsComparedTrimmed', () => {
    for (const from of ['x2027-04-01', '2027-04-01x']) {
      expect(versionProblems(version({ effectiveFrom: from }), null)).toEqual(['m17.needFrom']);
    }
    expect(
      versionProblems(
        version({ fields: [field('Basic ', 'COMPONENT', '101'), field('Basic', 'COMPONENT', '102')] }),
        null,
      ),
    ).toEqual(['m17.duplicateLabel']);
  });

  it('SRS_7_31_theFilingDateIsExactlyADateAndTheDesignationIsNotBlank', () => {
    for (const filingDate of ['x2026-10-25', '2026-10-25x']) {
      expect(filingProblems(filing({ filingDate }), '2026-10-26')).toEqual(['c11.needFilingDate']);
    }
    expect(filingProblems(filing({ signatoryDesignation: '  ' }), '2026-10-26')).toEqual([
      'c11.needSignatory',
    ]);
  });

  it('SRS_7_8_aReleasedOriginalIsCorrectable', () => {
    expect(correctable([ret({ id: 4, status: 'RELEASED' })]).map((r) => r.id)).toEqual([4]);
  });
});

describe('SRS-7-28 what-if', () => {
  const draft = (over: Partial<WhatIfDraft> = {}): WhatIfDraft => ({
    regime: '',
    claims: [{ sectionCode: '', amount: '' }],
    salaryChange: '',
    otherIncome: '',
    ...over,
  });

  it('SRS_7_28_somethingMustBeVaried', () => {
    expect(whatIfProblems(draft())).toEqual(['c07w.nothingVaried']);
    expect(whatIfProblems(draft({ salaryChange: '0', otherIncome: '0.00' }))).toEqual(['c07w.nothingVaried']);
    expect(whatIfProblems(draft({ regime: 'NEW' }))).toEqual([]);
    expect(whatIfProblems(draft({ salaryChange: '-1' }))).toEqual([]);
    expect(whatIfProblems(draft({ otherIncome: '1' }))).toEqual([]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '24(b)', amount: '0' }] }))).toEqual([]);
  });

  it('SRS_7_28_eachAmountIsRupeesAndPaise', () => {
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '', amount: '5' }] }))).toEqual(
      ['c07w.badClaim', 'c07w.nothingVaried'].slice(0, 1),
    );
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80 C', amount: '5' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '-5' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(whatIfProblems(draft({ claims: [{ sectionCode: '80C', amount: '5.123' }] }))).toEqual([
      'c07w.badClaim',
    ]);
    expect(
      whatIfProblems(
        draft({
          claims: [
            { sectionCode: '80C', amount: '5' },
            { sectionCode: ' 80C ', amount: '6' },
          ],
        }),
      ),
    ).toEqual(['c07w.duplicateClaim']);
    expect(whatIfProblems(draft({ salaryChange: '12345678901' }))).toEqual(['c07w.badSalary']);
    expect(whatIfProblems(draft({ salaryChange: '1-1' }))).toEqual(['c07w.badSalary']);
    expect(whatIfProblems(draft({ salaryChange: '1234567890.99' }))).toEqual([]);
    expect(whatIfProblems(draft({ otherIncome: '-1' }))).toEqual(['c07w.badIncome']);
    expect(whatIfProblems(draft({ otherIncome: '1.5x' }))).toEqual(['c07w.badIncome']);
  });

  it('SRS_7_28_theRequestCarriesOnlyWhatIsVaried', () => {
    expect(whatIfRequest(draft({ regime: 'OLD' }))).toEqual({
      regime: 'OLD',
      claims: [],
      salaryChange: null,
      otherIncome: null,
    });
    expect(
      whatIfRequest(
        draft({
          claims: [{ sectionCode: ' 80D ', amount: ' 2500.50 ' }],
          salaryChange: ' -100 ',
          otherIncome: '7',
        }),
      ),
    ).toEqual({
      regime: null,
      claims: [{ sectionCode: '80D', amount: 2500.5 }],
      salaryChange: -100,
      otherIncome: 7,
    });
  });
});
