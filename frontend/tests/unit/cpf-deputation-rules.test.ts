import { describe, expect, it } from 'vitest';
import type { DeputedMember } from '@/modules/cpf/api/cpfApi';
import { emptyEntry, entryProblems, inDeputation, isPaise } from '@/modules/cpf/business/deputationRules';

/** I-09 deputation adjustment checks (plan 07 phase 07g; SRS-6-27 … 6-29, BR-CPF-021, BR-CPF-022). */

const MEMBER: DeputedMember = {
  eis: '871901',
  name: 'Kiran Bose',
  deputationFrom: '2026-07-10',
  deputationTo: '2026-09-30',
  advances: [{ sanctionId: 5, sanctionNumber: 'ADV-5', sanctioned: 60000, outstanding: 5000 }],
  monthsWithoutReceipt: [],
  entries: [],
};

describe('I-09 deputation rules', () => {
  it('SRS_6_27_amountsAreRupeesAndPaise', () => {
    expect(isPaise('')).toBe(true);
    expect(isPaise('1200')).toBe(true);
    expect(isPaise('1200.5')).toBe(true);
    expect(isPaise('1200.55')).toBe(true);
    expect(isPaise('1200.555')).toBe(false);
    expect(isPaise('1.2.3')).toBe(false);
    expect(isPaise('-1')).toBe(false);
    expect(isPaise('1234567890123')).toBe(false);
    expect(isPaise('x1')).toBe(false);
  });

  it('SRS_6_30_theMonthIsAMonthOfTheDeputation', () => {
    expect(inDeputation('202607', MEMBER)).toBe(true);
    expect(inDeputation('202609', MEMBER)).toBe(true);
    expect(inDeputation('202606', MEMBER)).toBe(false);
    expect(inDeputation('202610', MEMBER)).toBe(false);
    expect(inDeputation('202613', MEMBER)).toBe(false);
    expect(inDeputation('2026-7', MEMBER)).toBe(false);
    expect(inDeputation('203001', { ...MEMBER, deputationTo: null })).toBe(true);
  });

  it('SRS_6_28_anInstalmentRepaysAnAdvanceWithinWhatIsOutstanding', () => {
    const base = {
      ...emptyEntry('871901', 'RECOVERY_INSTALMENT'),
      borrowingOrgId: '1',
      payrollYymm: '202608',
    };
    expect(entryProblems({ ...base, sanctionId: '5', instalment: '5000' }, MEMBER, true)).toEqual([]);
    expect(entryProblems({ ...base, sanctionId: '5', instalment: '5000.01' }, MEMBER, true)).toEqual([
      'i09.bad.over',
    ]);
    expect(entryProblems({ ...base, sanctionId: '9', instalment: '10' }, MEMBER, true)).toEqual([
      'i09.bad.advance',
    ]);
    expect(entryProblems({ ...base, sanctionId: '5', instalment: '0' }, MEMBER, true)).toEqual([
      'i09.bad.amount',
    ]);
    expect(entryProblems({ ...base, sanctionId: '5', instalment: 'x' }, MEMBER, true)).toEqual([
      'i09.bad.amount',
    ]);
  });

  it('SRS_6_29_aReceiptHasPartsAndEveryEntryAnOrganisationMonthAndEvidence', () => {
    const receipt = emptyEntry('871901', 'CONTRIBUTION_RECEIPT');
    expect(entryProblems(receipt, MEMBER, false)).toEqual([
      'i09.bad.org',
      'i09.bad.month',
      'i09.bad.amount',
      'i09.bad.evidence',
    ]);
    const ok = { ...receipt, borrowingOrgId: '1', payrollYymm: '202608', vpf: '0.01' };
    expect(entryProblems(ok, MEMBER, true)).toEqual([]);
    expect(entryProblems({ ...ok, employee: '1.234' }, MEMBER, true)).toEqual(['i09.bad.amount']);
    const excess = { ...emptyEntry('871901', 'EXCESS_REFUND'), borrowingOrgId: '1', payrollYymm: '202608' };
    expect(entryProblems({ ...excess, instalment: '100' }, MEMBER, true)).toEqual([]);
    expect(entryProblems(excess, MEMBER, true)).toEqual(['i09.bad.amount']);
  });
});
