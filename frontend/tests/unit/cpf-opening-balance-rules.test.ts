import { describe, expect, it } from 'vitest';
import type { Balances, NewCorrection } from '@/modules/cpf/api/cpfApi';
import {
  CORRECTION_STATUS_PILL,
  FIELD,
  PARTS,
  changes,
  correctionProblems,
  isOpen,
  paise,
  signed,
} from '@/modules/cpf/business/openingBalanceRules';

/**
 * I-22 opening-balance correction (SRS-6-43, SRS-6-44, BR-CPF-025, SRS I-22 field table): the
 * screen's own checks, mirroring the server's CorrectionRules.
 */

const CURRENT: Balances = { employee: 1000, employer: 500.5, vpf: 0 };

function draft(over: Partial<NewCorrection> = {}): NewCorrection {
  return {
    eis: '104882',
    asAtDate: '2026-04-01',
    employee: null,
    employer: null,
    vpf: null,
    reasonCode: 'CLERICAL_ERROR',
    interestEffectiveDate: '2026-04-01',
    remarks: 'Clerical error in the migrated balance',
    ...over,
  };
}

describe('I-22 amounts in paise', () => {
  it('SRS_6_43_anAmountOfTwelveDigitsAndTwoDecimalsIsExact', () => {
    expect(paise('1000')).toBe(100000n);
    expect(paise(' 0.1 ')).toBe(10n);
    expect(paise('0.05')).toBe(5n);
    expect(paise('999999999999.99')).toBe(99999999999999n);
    expect(paise('-0.01')).toBe(-1n);
    expect(paise('-5')).toBe(-500n);
    expect(paise(500.5)).toBe(50050n);
    expect(paise(0.1 + 0.2)).toBe(30n);
  });

  it('SRS_6_43_anythingElseIsNotAnAmount', () => {
    for (const bad of [
      '',
      '1.2.3',
      '1.234',
      '1,000',
      '1e3',
      '.5',
      '5.',
      '+5',
      '1000000000000',
      'abc',
      '1 0',
    ]) {
      expect(paise(bad), bad).toBeNull();
    }
  });

  it('SRS_6_43_theDifferenceIsSignedWithTwoDecimalsAndIndianGrouping', () => {
    expect(signed(10000050n)).toBe('+1,00,000.50');
    expect(signed(-1n)).toBe('−0.01');
    expect(signed(0n)).toBe('0.00');
    expect(signed(-12345678n)).toBe('−1,23,456.78');
  });
});

describe('I-22 corrected parts', () => {
  it('SRS_6_43_onlyATypedPartThatDiffersIsAChange', () => {
    expect(changes(CURRENT, draft())).toEqual([]);
    expect(changes(CURRENT, draft({ employee: '1000.00', employer: '500.50', vpf: ' ' }))).toEqual([]);
    expect(changes(CURRENT, draft({ employee: '1100', vpf: '0.01' }))).toEqual([
      { part: 'EMPLOYEE', old: 100000n, corrected: 110000n, difference: 10000n },
      { part: 'VPF', old: 0n, corrected: 1n, difference: 1n },
    ]);
    // A part may be corrected to zero; an unparseable part is not a change.
    expect(changes(CURRENT, draft({ employer: '0', employee: 'x' }))).toEqual([
      { part: 'EMPLOYER', old: 50050n, corrected: 0n, difference: -50050n },
    ]);
  });

  it('SRS_6_44_whatStopsADraftIsNamed', () => {
    expect(correctionProblems(CURRENT, draft({ employee: '1100' }), true)).toEqual([]);
    // A blank part is not corrected, and a part may be corrected to zero.
    expect(correctionProblems(CURRENT, draft({ employee: '  ', employer: '0' }), true)).toEqual([]);
    expect(correctionProblems(CURRENT, draft(), true)).toEqual(['error.CPF-422-NO-CHANGE']);
    expect(correctionProblems(CURRENT, draft({ vpf: '-0.01' }), true)).toEqual(['i22.problem.negative']);
    expect(correctionProblems(CURRENT, draft({ employee: '1.234', vpf: '5' }), true)).toEqual([
      'i22.problem.amount',
    ]);
    expect(
      correctionProblems(
        CURRENT,
        draft({ employee: '1100', reasonCode: '', interestEffectiveDate: '', asAtDate: '', remarks: ' ' }),
        false,
      ),
    ).toEqual([
      'i22.problem.reason',
      'i22.problem.interestDate',
      'i22.problem.asAt',
      'i22.problem.remarks',
      'error.CPF-422-EVIDENCE',
    ]);
  });

  it('SRS_6_43_remarksAreUpTo1000PrintableAsciiCharactersOnAnyNumberOfLines', () => {
    expect(
      correctionProblems(CURRENT, draft({ employee: '1', remarks: 'line one\nline two' }), true),
    ).toEqual([]);
    expect(correctionProblems(CURRENT, draft({ employee: '1', remarks: 'x'.repeat(1000) }), true)).toEqual(
      [],
    );
    expect(correctionProblems(CURRENT, draft({ employee: '1', remarks: 'x'.repeat(1001) }), true)).toEqual([
      'i22.problem.remarks',
    ]);
    expect(correctionProblems(CURRENT, draft({ employee: '1', remarks: 'त्रुटि' }), true)).toEqual([
      'i22.problem.remarks',
    ]);
  });

  it('SRS_6_44_aPendingOrBlockedCorrectionIsOpen', () => {
    expect(
      ['PENDING_AGM_CPF', 'PENDING_HQ_CPF', 'BLOCKED_CPF_DIVISION'].every((s) => isOpen(s as never)),
    ).toBe(true);
    for (const s of ['DRAFT', 'RETURNED', 'APPROVED', 'REJECTED', 'APPLIED', 'CANCELLED'] as const) {
      expect(isOpen(s), s).toBe(false);
    }
  });

  it('SRS_6_43_thePartsAndPillsAreTheLedgers', () => {
    expect(PARTS).toEqual(['EMPLOYEE', 'EMPLOYER', 'VPF']);
    expect(FIELD).toEqual({ EMPLOYEE: 'employee', EMPLOYER: 'employer', VPF: 'vpf' });
    expect(CORRECTION_STATUS_PILL).toEqual({
      DRAFT: 'p-gray',
      PENDING_AGM_CPF: 'p-warn',
      PENDING_HQ_CPF: 'p-warn',
      RETURNED: 'p-info',
      APPROVED: 'p-info',
      REJECTED: 'p-bad',
      BLOCKED_CPF_DIVISION: 'p-bad',
      APPLIED: 'p-ok',
      CANCELLED: 'p-gray',
    });
  });
});
