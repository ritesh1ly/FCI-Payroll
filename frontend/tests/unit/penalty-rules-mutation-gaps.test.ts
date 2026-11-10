import { describe, expect, it } from 'vitest';
import {
  isAmount,
  isYymm,
  orderProblems,
  paymentProblems,
  revokeProblems,
  type OrderDraft,
  type PaymentDraft,
} from '@/modules/penalty/business/penaltyRules';

/** Mutation-gap tests for the I-06 checks (SRS-14-1, SRS-14-2, SRS-14-16, BR-PEN2-002). */

const order = (over: Partial<OrderDraft> = {}): OrderDraft => ({
  eis: 'E1001',
  orderReference: 'PEN/2026/17',
  effectiveDate: '2026-11-01',
  amount: '1500.50',
  instalments: '3',
  startYymm: '202611',
  ...over,
});

const pay = (over: Partial<PaymentDraft> = {}): PaymentDraft => ({
  eis: 'E1001',
  payCode: '12',
  amount: '250.00',
  payrollYymm: '202611',
  reason: 'Arrears of the revised scale',
  applicationDate: '2026-10-01',
  sanctionReference: 'SAN/17',
  sanctionDate: '2026-10-20',
  ...over,
});

type Revoke = Parameters<typeof revokeProblems>[0];
const revoke = (over: Partial<Revoke> = {}): Revoke => ({
  type: 'PARTIAL',
  amount: '',
  fromYymm: null,
  toYymm: null,
  orderReference: 'REV/2026/3',
  orderDate: '2026-11-02',
  reason: 'Revoked on appeal',
  ...over,
});

describe('isYymm and isAmount', () => {
  it('SRS-14-1_aMonthIsAnchoredAtBothEnds', () => {
    expect(isYymm('202611')).toBe(true);
    expect(isYymm('202611x')).toBe(false);
    expect(isYymm('x202611')).toBe(false);
  });

  it('SRS-14-1_anAmountIsTrimmedBeforeItsPartsAreChecked', () => {
    expect(isAmount(' 5.00 ')).toBe(true);
    expect(isAmount(' 5 ')).toBe(true);
    expect(isAmount('0')).toBe(false);
    expect(isAmount('5.123')).toBe(false);
  });
});

describe('orderProblems gaps', () => {
  it('SRS-14-1_eisIsTrimmedAndAnchoredWithAtMostTwentyCharacters', () => {
    expect(orderProblems(order({ eis: ' AB-12 ' }))).toEqual([]);
    expect(orderProblems(order({ eis: 'A'.repeat(20) }))).toEqual([]);
    expect(orderProblems(order({ eis: 'A'.repeat(21) }))).toEqual(['i06.bad.eis']);
    expect(orderProblems(order({ eis: 'AB12!' }))).toEqual(['i06.bad.eis']);
    expect(orderProblems(order({ eis: '!AB12' }))).toEqual(['i06.bad.eis']);
  });

  it('SRS-14-1_effectiveDateIsAnchoredAtBothEnds', () => {
    expect(orderProblems(order({ effectiveDate: '2026-11-01x' }))).toEqual(['i06.bad.effective']);
    expect(orderProblems(order({ effectiveDate: 'x2026-11-01' }))).toEqual(['i06.bad.effective']);
  });

  it('SRS-14-1_oneInstalmentIsAllowedAndZeroIsNot', () => {
    expect(orderProblems(order({ instalments: '1' }))).toEqual([]);
    expect(orderProblems(order({ instalments: '0' }))).toEqual(['i06.bad.instalments']);
  });

  it('SRS-14-1_startMonthIsAnchoredAtBothEnds', () => {
    expect(orderProblems(order({ startYymm: '202611x' }))).toEqual(['i06.bad.start']);
    expect(orderProblems(order({ startYymm: 'x202611' }))).toEqual(['i06.bad.start']);
  });

  it('R-ASCII_aReferenceWithANonAsciiCharacterAtEitherEndIsRefused', () => {
    expect(orderProblems(order({ orderReference: 'PENé' }))).toEqual(['i06.bad.reference']);
    expect(orderProblems(order({ orderReference: 'éPEN' }))).toEqual(['i06.bad.reference']);
    expect(orderProblems(order({ orderReference: 'PEN/17' }))).toEqual([]);
  });
});

describe('revokeProblems gaps', () => {
  it('SRS-14-16_aFullRevokeWithOnlyPaddedAmountIsClean', () => {
    expect(revokeProblems(revoke({ type: 'FULL', amount: '   ' }), 1000)).toEqual([]);
  });

  it('SRS-14-16_aFullRevokeWithOnlyAToMonthHasAScope', () => {
    expect(revokeProblems(revoke({ type: 'FULL', toYymm: '202612' }), 1000)).toEqual(['i06.bad.fullScope']);
    expect(revokeProblems(revoke({ type: 'FULL', fromYymm: '202612' }), 1000)).toEqual(['i06.bad.fullScope']);
  });

  it('SRS-14-16_theAmountCheckBelongsToAPartialRevokeOnly', () => {
    expect(revokeProblems(revoke({ type: 'FULL', amount: 'abc' }), 1000)).toEqual(['i06.bad.fullScope']);
    expect(revokeProblems(revoke({ amount: 'abc' }), 1000)).toEqual(['i06.bad.amount']);
  });

  it('SRS-14-16_aPartialPeriodWithOneBadOrReversedMonthIsRefused', () => {
    const p = (fromYymm: string | null, toYymm: string | null) =>
      revokeProblems(revoke({ fromYymm, toYymm }), 1000);
    expect(p('202601', '202603')).toEqual([]);
    expect(p('202603', '202603')).toEqual([]);
    expect(p('202603', '202601')).toEqual(['i06.bad.period']);
    expect(p('202601', 'abc')).toEqual(['i06.bad.period']);
    expect(p('abc', '202601')).toEqual(['i06.bad.period']);
    expect(p('202601', null)).toEqual(['i06.bad.period']);
    expect(p(null, '202601')).toEqual(['i06.bad.period']);
  });

  it('SRS-14-16_orderDateIsAnchoredAtBothEnds', () => {
    expect(revokeProblems(revoke({ type: 'FULL', orderDate: '2026-11-02x' }), 1000)).toEqual([
      'i06.bad.orderDate',
    ]);
    expect(revokeProblems(revoke({ type: 'FULL', orderDate: 'x2026-11-02' }), 1000)).toEqual([
      'i06.bad.orderDate',
    ]);
  });
});

describe('paymentProblems gaps', () => {
  it('BR-PEN2-002_eisIsTrimmedAndAnchoredWithAtMostTwentyCharacters', () => {
    expect(paymentProblems(pay({ eis: ' AB-12 ' }))).toEqual([]);
    expect(paymentProblems(pay({ eis: 'A'.repeat(21) }))).toEqual(['i06.bad.eis']);
    expect(paymentProblems(pay({ eis: 'AB12!' }))).toEqual(['i06.bad.eis']);
    expect(paymentProblems(pay({ eis: '!AB12' }))).toEqual(['i06.bad.eis']);
  });

  it('BR-PEN2-002_payCodeOneIsTheLowestValid', () => {
    expect(paymentProblems(pay({ payCode: '1' }))).toEqual([]);
    expect(paymentProblems(pay({ payCode: '0' }))).toEqual(['i06.bad.payCode']);
  });

  it('BR-PEN2-002_sanctionDateIsCheckedOnlyWhenBothDatesAreGiven', () => {
    expect(paymentProblems(pay({ applicationDate: '', sanctionDate: '2026-10-20' }))).toEqual([]);
    expect(paymentProblems(pay({ applicationDate: '2026-10-01', sanctionDate: '' }))).toEqual([]);
    expect(paymentProblems(pay({ sanctionDate: '2026-10-01' }))).toEqual(['i06.bad.sanctionDate']);
    expect(paymentProblems(pay({ sanctionDate: '2026-09-30' }))).toEqual(['i06.bad.sanctionDate']);
  });
});
