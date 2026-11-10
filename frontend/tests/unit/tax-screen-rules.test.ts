import { describe, expect, it } from 'vitest';
import type { CertificateLine, DeclarationLine, ReturnSummary } from '@/modules/tax/api/screensApi';
import type { OutputVersion } from '@/modules/tax/api/statutoryApi';
import {
  certificateStatus,
  declarationStatus,
  lastCompletedQuarter,
  maskedToken,
  proofLabel,
  returnSteps,
  versionOfYear,
} from '@/modules/tax/business/screenRules';

/**
 * OD-121, SRS-7-1, BR-TAX-010, SRS-7-8, SRS-7-13: what the drawn tax screens (I-07, C-10, C-11, M-17)
 * read from a record. Display only; each decision stays with its service.
 */

const LINE: DeclarationLine = {
  employeeId: 1,
  eis: 'E0000001',
  name: 'Test Member',
  regime: 'O',
  frozen: false,
  frozenAt: null,
  roundId: 7,
  roundStatus: 'OPEN',
  declared80c: null,
  limit80c: null,
  declared80d: null,
  declared24b: null,
  allowed: null,
  capped: false,
  unproved: false,
};

const CERT: CertificateLine = {
  id: 1,
  eis: 'E0000001',
  name: 'Test Member',
  panMasked: 'XXXXX1234X',
  panStatus: 'VALID',
  regime: 'N',
  taxable: 100,
  tds: 10,
  partB: true,
  partA: true,
  signed: false,
  status: 'MERGED',
};

function version(id: number, effectiveFrom: string): OutputVersion {
  return {
    id,
    number: id,
    effectiveFrom,
    effectiveTo: null,
    layoutFormat: 'PRESCRIBED_FILE',
    periodLockRequired: false,
    inForce: false,
    fields: [],
    controls: [],
  };
}

function summary(over: Partial<ReturnSummary>): ReturnSummary {
  return {
    finYear: '2026-27',
    quarter: 'Q1',
    status: null,
    validated: false,
    deductees: 0,
    staff: 0,
    labour: 0,
    tds: null,
    challans: 0,
    challansMapped: 0,
    panExceptions: 0,
    definitionVersion: 1,
    definitionFrom: null,
    units: [],
    ...over,
  };
}

describe('C-11 last completed quarter (SRS-7-8)', () => {
  it.each([
    ['2026-01-15', '2025-26', 'Q3'],
    ['2026-03-31', '2025-26', 'Q3'],
    ['2026-04-01', '2025-26', 'Q4'],
    ['2026-06-30', '2025-26', 'Q4'],
    ['2026-07-01', '2026-27', 'Q1'],
    ['2026-09-30', '2026-27', 'Q1'],
    ['2026-10-01', '2026-27', 'Q2'],
    ['2026-11-08', '2026-27', 'Q2'],
    ['2026-12-31', '2026-27', 'Q2'],
  ])('SRS_7_8_onDay %s theReturnIsFor %s %s', (today, finYear, quarter) => {
    expect(lastCompletedQuarter(today)).toEqual({ finYear, quarter });
  });
});

describe('I-07 declaration status and proof (SRS-7-1, BR-TAX-010)', () => {
  it('SRS_7_1_aMemberWithoutRegimeShowsNoRegimeFirst', () => {
    expect(declarationStatus({ ...LINE, regime: null, capped: true, unproved: true })).toBe(
      'txw.i07.status.noRegime',
    );
  });

  it('BR_TAX_010_aCappedLineShowsCappedBeforeProof', () => {
    expect(declarationStatus({ ...LINE, capped: true, unproved: true })).toBe('txw.i07.status.capped');
  });

  it('SRS_7_1_anUnprovedLineShowsProofPending', () => {
    expect(declarationStatus({ ...LINE, unproved: true, roundId: null })).toBe('txw.i07.status.proofPending');
  });

  it('SRS_7_1_aLineOutsideARoundShowsNoRound', () => {
    expect(declarationStatus({ ...LINE, roundId: null })).toBe('txw.i07.status.noRound');
  });

  it('SRS_7_1_aLineInARoundIsComplete', () => {
    expect(declarationStatus(LINE)).toBe('txw.i07.status.complete');
    expect(declarationStatus({ ...LINE, regime: 'N' })).toBe('txw.i07.status.complete');
  });

  it('SRS_7_1_aMemberWithoutRegimeShowsADash', () => {
    expect(proofLabel({ ...LINE, regime: null, declared80c: 10 })).toBeNull();
  });

  it('SRS_7_1_nothingDeclaredShowsNoProof', () => {
    expect(proofLabel({ ...LINE, unproved: true })).toBe('txw.i07.proof.none');
  });

  it.each([
    [{ declared80c: 1 }],
    [{ declared80d: 1 }],
    [{ declared24b: 1 }],
    [{ declared80c: 0, declared80d: 0, declared24b: 0 }],
  ])('BR_TAX_010_anyDeclaredHeadNeedsProof %o', (over) => {
    expect(proofLabel({ ...LINE, ...over })).toBe('txw.i07.proof.verified');
    expect(proofLabel({ ...LINE, ...over, unproved: true })).toBe('txw.i07.proof.notSubmitted');
  });
});

describe('C-10 certificate status (SRS-7-13)', () => {
  it('SRS_7_13_aReleasedCertificateIsIssuedWhateverThePan', () => {
    expect(certificateStatus({ ...CERT, status: 'RELEASED', panStatus: 'MISSING' })).toBe(
      'txw.c10.status.issued',
    );
  });

  it.each(['INVALID', 'MISSING'] as const)('SRS_7_13_aPan %s stopsTheCertificateFirst', (panStatus) => {
    expect(certificateStatus({ ...CERT, panStatus })).toBe('txw.c10.status.blockedPan');
  });

  it('SRS_7_13_aMergedCertificateWithAValidPanAwaitsSignature', () => {
    expect(certificateStatus(CERT)).toBe('txw.c10.status.unsigned');
  });

  it('SRS_7_13_anyOtherStatusAwaitsCorrection', () => {
    expect(certificateStatus({ ...CERT, status: 'DRAFT' })).toBe('txw.c10.status.correctionPending');
  });
});

describe('M-17 version of a financial year (SRS-7-8)', () => {
  it('SRS_7_8_noVersionGovernsNothing', () => {
    expect(versionOfYear([], '2026-27')).toBeNull();
    expect(versionOfYear([version(1, '2027-04-01')], '2026-27')).toBeNull();
  });

  it('SRS_7_8_theNewestEffectiveByTheLastDayGoverns', () => {
    const versions = [version(1, '2025-04-01'), version(2, '2026-10-01'), version(3, '2027-04-01')];
    expect(versionOfYear(versions, '2026-27')?.id).toBe(2);
    expect(versionOfYear([...versions].reverse(), '2026-27')?.id).toBe(2);
  });

  it('SRS_7_8_aVersionEffectiveOnTheLastDayGoverns', () => {
    const versions = [version(1, '2026-04-01'), version(2, '2027-03-31'), version(3, '2027-04-01')];
    expect(versionOfYear(versions, '2026-27')?.id).toBe(2);
  });

  it('SRS_7_8_anEarlierYearKeepsItsOwnVersion', () => {
    const versions = [version(1, '2025-04-01'), version(2, '2026-10-01')];
    expect(versionOfYear(versions, '2025-26')?.id).toBe(1);
  });
});

describe('C-11 return steps (SRS-7-8)', () => {
  const P = 'txw.c11.pill.pending';
  const D = 'txw.c11.pill.done';

  it('SRS_7_8_noSummaryLeavesEveryStepPending', () => {
    expect(returnSteps(undefined)).toEqual([P, P, '', '', P, P, 'txw.c11.pill.external', P]);
  });

  it('SRS_7_8_aSummaryWithoutStatusIsPending', () => {
    expect(returnSteps(summary({ status: '' }))).toEqual([P, P, '', '', P, P, 'txw.c11.pill.external', P]);
  });

  it('SRS_7_8_aValidatedReturnPassesValidationOnly', () => {
    expect(returnSteps(summary({ validated: true }))).toEqual([
      P,
      P,
      '',
      '',
      'txw.c11.pill.passed',
      P,
      'txw.c11.pill.external',
      P,
    ]);
  });

  it('SRS_7_8_aPreparedReturnIsDoneAndMatched', () => {
    expect(returnSteps(summary({ status: 'PREPARED', validated: false }))).toEqual([
      D,
      'txw.c11.pill.matched',
      '',
      '',
      P,
      P,
      'txw.c11.pill.external',
      P,
    ]);
  });

  it('SRS_7_8_aReleasedReturnIsValidatedAndReleased', () => {
    expect(returnSteps(summary({ status: 'RELEASED' }))).toEqual([
      D,
      'txw.c11.pill.matched',
      '',
      '',
      'txw.c11.pill.passed',
      D,
      'txw.c11.pill.external',
      P,
    ]);
  });

  it('SRS_7_8_aFiledReturnHasEveryStepDone', () => {
    expect(returnSteps(summary({ status: 'FILED' }))).toEqual([
      D,
      'txw.c11.pill.matched',
      '',
      '',
      'txw.c11.pill.passed',
      D,
      'txw.c11.pill.external',
      D,
    ]);
  });
});

describe('Masked token (SRS-7-13, R-PII)', () => {
  it('SRS_7_13_noTokenShowsADash', () => {
    expect(maskedToken(null)).toBe('—');
    expect(maskedToken('')).toBe('—');
  });

  it('SRS_7_13_aTokenKeepsItsPrefixAndLastFour', () => {
    expect(maskedToken('ACK-12345678')).toBe('ACK-••5678');
    expect(maskedToken('TOKEN')).toBe('••OKEN');
    expect(maskedToken('-ABCDE')).toBe('••BCDE');
  });
});
