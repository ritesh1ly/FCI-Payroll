import { describe, expect, it } from 'vitest';
import type { DeputedMember } from '@/modules/cpf/api/cpfApi';
import {
  ENTRY_TYPES,
  emptyEntry,
  entryProblems,
  inDeputation,
  isPaise,
} from '@/modules/cpf/business/deputationRules';

/** Mutation-gap tests for the I-09 checks (SRS-6-27 ... 6-30, BR-CPF-021 ... 023). */

const member = (over: Partial<DeputedMember> = {}): DeputedMember => ({
  eis: 'E1001',
  name: 'A Kumar',
  deputationFrom: '2026-04-15',
  deputationTo: null,
  advances: [],
  monthsWithoutReceipt: [],
  entries: [],
  ...over,
});

describe('constants and empty entry', () => {
  it('SRS-6-27_entryTypesAreTheThreeRemittanceKindsInOrder', () => {
    expect(ENTRY_TYPES).toEqual(['RECOVERY_INSTALMENT', 'CONTRIBUTION_RECEIPT', 'EXCESS_REFUND']);
  });

  it('SRS-6-27_anEmptyEntryHasEveryFieldBlank', () => {
    expect(emptyEntry('E1001', 'EXCESS_REFUND')).toStrictEqual({
      eis: 'E1001',
      borrowingOrgId: '',
      type: 'EXCESS_REFUND',
      sanctionId: '',
      payrollYymm: '',
      instalment: '',
      employee: '',
      employer: '',
      vpf: '',
    });
  });
});

describe('isPaise', () => {
  it('SRS-6-28_blankAndPaddedAmounts', () => {
    expect(isPaise('   ')).toBe(true);
    expect(isPaise('')).toBe(true);
    expect(isPaise(' 5.25 ')).toBe(true);
    expect(isPaise(' 5 ')).toBe(true);
    expect(isPaise('5.2.1')).toBe(false);
    expect(isPaise('1234567890123')).toBe(false);
    expect(isPaise('5.123')).toBe(false);
    expect(isPaise('x5')).toBe(false);
  });
});

describe('inDeputation', () => {
  it('SRS-6-29_theMonthIsAnchoredAndMonthsRunOneToTwelve', () => {
    const m = member();
    for (const ok of ['202610', '202611', '202612', '202604', '202609'])
      expect(inDeputation(ok, m)).toBe(true);
    for (const bad of ['202613', '202600', '202610x', 'x202610', '2026101', '']) {
      expect(inDeputation(bad, m)).toBe(false);
    }
  });

  it('SRS-6-29_theFirstAndLastMonthsAreInsideAndTheNeighboursOutside', () => {
    const m = member({ deputationTo: '2026-09-30' });
    expect(inDeputation('202603', m)).toBe(false);
    expect(inDeputation('202604', m)).toBe(true);
    expect(inDeputation('202609', m)).toBe(true);
    expect(inDeputation('202610', m)).toBe(false);
  });
});

describe('entryProblems amounts', () => {
  it('SRS-6-28_aBlankOrPaddedInstalmentIsNotAnAmount', () => {
    const e = { ...emptyEntry('E1001', 'EXCESS_REFUND'), borrowingOrgId: '7', payrollYymm: '202606' };
    expect(entryProblems({ ...e, instalment: '   ' }, member(), true)).toEqual(['i09.bad.amount']);
    expect(entryProblems({ ...e, instalment: ' 10.50 ' }, member(), true)).toEqual([]);
  });

  it('SRS-6-28_aReceiptNeedsAPositiveSumOfPaddedParts', () => {
    const e = { ...emptyEntry('E1001', 'CONTRIBUTION_RECEIPT'), borrowingOrgId: '7', payrollYymm: '202606' };
    expect(entryProblems({ ...e, employee: '  ', employer: ' ', vpf: '' }, member(), true)).toEqual([
      'i09.bad.amount',
    ]);
    expect(entryProblems({ ...e, employee: ' 100 ', employer: ' 50.5', vpf: '' }, member(), true)).toEqual(
      [],
    );
    expect(entryProblems({ ...e, employee: '0', employer: '0.00', vpf: ' ' }, member(), true)).toEqual([
      'i09.bad.amount',
    ]);
  });

  it('BR-CPF-022_aRecoveryAboveTheOutstandingAdvanceIsOver', () => {
    const m = member({ advances: [{ sanctionId: 9, outstanding: 100 } as never] });
    const e = {
      ...emptyEntry('E1001', 'RECOVERY_INSTALMENT'),
      borrowingOrgId: '7',
      payrollYymm: '202606',
      sanctionId: '9',
    };
    expect(entryProblems({ ...e, instalment: ' 100 ' }, m, true)).toEqual([]);
    expect(entryProblems({ ...e, instalment: ' 100.01 ' }, m, true)).toEqual(['i09.bad.over']);
  });
});
