import { describe, expect, it } from 'vitest';
import {
  changeable,
  isAmount,
  isYymm,
  orderProblems,
  paymentProblems,
  revokeProblems,
} from '@/modules/penalty/business/penaltyRules';

/** I-06 checks before a request is sent (plan 12; SRS-14-1, SRS-14-2, SRS-14-16, BR-PEN2-002). */

const ORDER = {
  eis: '104882',
  orderReference: 'CH/REC/1',
  effectiveDate: '2026-10-01',
  amount: '1000.50',
  instalments: '3',
  startYymm: '202611',
};

const REVOKE = {
  type: 'FULL' as const,
  amount: '',
  fromYymm: null,
  toYymm: null,
  orderReference: 'REV/1',
  orderDate: '2026-10-01',
  reason: 'On appeal',
};

const PAYMENT = {
  eis: '104882',
  payCode: '101',
  amount: '2500',
  payrollYymm: '202611',
  reason: 'Special duty',
  applicationDate: '2026-10-01',
  sanctionReference: 'SAN/1',
  sanctionDate: '2026-10-05',
};

describe('I-06 rules', () => {
  it('SRS_14_1_amountsAndMonths', () => {
    expect(isAmount('1')).toBe(true);
    expect(isAmount('1.5')).toBe(true);
    expect(isAmount('1.55')).toBe(true);
    expect(isAmount('0')).toBe(false);
    expect(isAmount('1.555')).toBe(false);
    expect(isAmount('1.2.3')).toBe(false);
    expect(isAmount('x')).toBe(false);
    expect(isAmount('1234567890123')).toBe(false);
    expect(isYymm('202612')).toBe(true);
    expect(isYymm('202613')).toBe(false);
    expect(isYymm('202600')).toBe(false);
    expect(isYymm('2026-1')).toBe(false);
  });

  it('SRS_14_1_anOrderNeedsEveryParticular', () => {
    expect(orderProblems(ORDER)).toEqual([]);
    expect(
      orderProblems({
        eis: ' ',
        orderReference: '',
        effectiveDate: '',
        amount: '0',
        instalments: '0',
        startYymm: '2026',
      }),
    ).toEqual([
      'i06.bad.eis',
      'i06.bad.reference',
      'i06.bad.effective',
      'i06.bad.amount',
      'i06.bad.instalments',
      'i06.bad.start',
    ]);
    expect(orderProblems({ ...ORDER, orderReference: 'x'.repeat(61) })).toEqual(['i06.bad.reference']);
    expect(orderProblems({ ...ORDER, orderReference: 'x'.repeat(60) })).toEqual([]);
    expect(orderProblems({ ...ORDER, instalments: '1000' })).toEqual(['i06.bad.instalments']);
    expect(orderProblems({ ...ORDER, instalments: '999' })).toEqual([]);
    expect(orderProblems({ ...ORDER, eis: 'A-1' })).toEqual([]);
  });

  it('SRS_14_16_aRevokeWaivesTheWholeOrderOrAnAmountOrAPeriod', () => {
    expect(revokeProblems(REVOKE, 3000)).toEqual([]);
    expect(revokeProblems({ ...REVOKE, amount: '100' }, 3000)).toEqual(['i06.bad.fullScope']);
    expect(revokeProblems({ ...REVOKE, fromYymm: '202611' }, 3000)).toEqual(['i06.bad.fullScope']);
    const partial = { ...REVOKE, type: 'PARTIAL' as const };
    expect(revokeProblems(partial, 3000)).toEqual(['i06.bad.partialScope']);
    expect(revokeProblems({ ...partial, amount: '100', fromYymm: '202611', toYymm: '202612' }, 3000)).toEqual(
      ['i06.bad.partialScope'],
    );
    expect(revokeProblems({ ...partial, amount: '3000' }, 3000)).toEqual([]);
    expect(revokeProblems({ ...partial, amount: '3000.01' }, 3000)).toEqual(['i06.bad.amount']);
    expect(revokeProblems({ ...partial, amount: '5' }, null)).toEqual([]);
    expect(revokeProblems({ ...partial, amount: 'x' }, null)).toEqual(['i06.bad.amount']);
    expect(revokeProblems({ ...partial, fromYymm: '202611', toYymm: '202611' }, 3000)).toEqual([]);
    expect(revokeProblems({ ...partial, fromYymm: '202612', toYymm: '202611' }, 3000)).toEqual([
      'i06.bad.period',
    ]);
    expect(revokeProblems({ ...partial, fromYymm: '202611', toYymm: null }, 3000)).toEqual([
      'i06.bad.period',
    ]);
    expect(revokeProblems({ ...partial, fromYymm: null, toYymm: '202611' }, 3000)).toEqual([
      'i06.bad.period',
    ]);
    expect(revokeProblems({ ...REVOKE, orderReference: '', orderDate: '', reason: ' ' }, 3000)).toEqual([
      'i06.bad.reference',
      'i06.bad.orderDate',
      'i06.bad.reason',
    ]);
  });

  it('BR_PEN2_002_aPaymentIsSanctionedAfterItsApplication', () => {
    expect(paymentProblems(PAYMENT)).toEqual([]);
    expect(paymentProblems({ ...PAYMENT, sanctionDate: '2026-10-01' })).toEqual(['i06.bad.sanctionDate']);
    expect(paymentProblems({ ...PAYMENT, sanctionDate: '' })).toEqual([]);
    expect(paymentProblems({ ...PAYMENT, applicationDate: '' })).toEqual([]);
    expect(
      paymentProblems({
        eis: '',
        payCode: '0',
        amount: '',
        payrollYymm: '1',
        reason: '',
        applicationDate: '',
        sanctionReference: '',
        sanctionDate: '',
      }),
    ).toEqual([
      'i06.bad.eis',
      'i06.bad.payCode',
      'i06.bad.amount',
      'i06.bad.month',
      'i06.bad.reason',
      'i06.bad.sanction',
    ]);
    expect(paymentProblems({ ...PAYMENT, payCode: '123456' })).toEqual(['i06.bad.payCode']);
  });

  it('SRS_14_20_onlyAnOpenOrderChanges', () => {
    expect(changeable('ACTIVE')).toBe(true);
    expect(changeable('PARTLY_REVOKED')).toBe(true);
    expect(changeable('PAUSED_FNF')).toBe(true);
    expect(changeable('REVOKED')).toBe(false);
    expect(changeable('COMPLETED')).toBe(false);
    expect(changeable('CANCELLED')).toBe(false);
    // UI audit S1 / R-SRC: an order from HRMS is never cancelled in Payroll; its revoke is `revocable`.
    expect(changeable('ACTIVE', 'HRMS')).toBe(false);
    expect(changeable('PAUSED_FNF', 'HRMS')).toBe(false);
    expect(changeable('ACTIVE', 'ENT')).toBe(true);
    expect(changeable('REVOKED', 'ENT')).toBe(false);
  });
});

describe('I-06 reasons, R-ASCII', () => {
  it('SRS-14-16 / SRS-14-2: a reason keeps its line breaks but refuses any non-ASCII character', () => {
    expect(revokeProblems({ ...REVOKE, reason: 'On appeal\r\n\tline 2' }, 3000)).toEqual([]);
    expect(revokeProblems({ ...REVOKE, reason: 'अपील' }, 3000)).toEqual(['i06.bad.reason']);
    expect(revokeProblems({ ...REVOKE, reason: 'On appeal é' }, 3000)).toEqual(['i06.bad.reason']);
    expect(paymentProblems({ ...PAYMENT, reason: 'é Special duty' })).toEqual(['i06.bad.reason']);
    expect(paymentProblems({ ...PAYMENT, reason: 'Special\nduty' })).toEqual([]);
  });
});
