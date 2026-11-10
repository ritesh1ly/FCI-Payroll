import { describe, expect, it } from 'vitest';
import type { DeputedMember } from '@/modules/cpf/api/cpfApi';
import {
  ENTRY_TYPES,
  emptyEntry,
  entryProblems,
  inDeputation,
  isPaise,
} from '@/modules/cpf/business/deputationRules';
import { LEAVE_PARAMETERS, draftProblem } from '@/modules/leave/business/leaveRules';
import { isYymm as loanYymm, sanctionReady } from '@/modules/loans/business/loanRules';
import {
  type OrderDraft,
  type PaymentDraft,
  isAmount,
  isYymm,
  orderProblems,
  paymentProblems,
  revokeProblems,
} from '@/modules/penalty/business/penaltyRules';

/**
 * Field-level edge cases of the I-06, I-04, I-09 and M-20 field tables (SRS-14-1, SRS-14-2,
 * SRS-14-16, SRS-12-21, SRS-6-27 … 6-29, SRS-LV-14): a value padded with spaces is read without them;
 * a value with anything before or after its format is refused; each bound is exact.
 */

const ORDER: OrderDraft = {
  eis: '104882',
  orderReference: 'PEN/2026/7',
  effectiveDate: '2026-10-01',
  amount: '1200',
  instalments: '1',
  startYymm: '202611',
};

const PAYMENT: PaymentDraft = {
  eis: '104882',
  payCode: '1',
  amount: '500',
  payrollYymm: '202610',
  reason: 'Honorarium',
  applicationDate: '2026-10-01',
  sanctionReference: 'SAN/1',
  sanctionDate: '2026-10-02',
};

describe('I-06 penalty field edges', () => {
  it('SRS-14-1: a month is exactly YYYYMM and an amount is rupees and up to two paise above zero', () => {
    expect(isYymm('202601')).toBe(true);
    expect(isYymm('x202601')).toBe(false);
    expect(isYymm('2026011')).toBe(false);
    expect(isAmount(' 10.5 ')).toBe(true);
    expect(isAmount('1.2.3')).toBe(false);
    expect(isAmount('x1')).toBe(false);
    expect(isAmount('1x')).toBe(false);
    expect(isAmount('123456789012')).toBe(true);
    expect(isAmount('1234567890123')).toBe(false);
  });

  it('SRS-14-1: the member, dates and text are read without padding; anything around the format is refused; one instalment is enough', () => {
    expect(orderProblems({ ...ORDER, eis: ' 104882 ' })).toEqual([]);
    expect(orderProblems({ ...ORDER, eis: '$104882' })).toContain('i06.bad.eis');
    expect(orderProblems({ ...ORDER, eis: '104882$' })).toContain('i06.bad.eis');
    expect(orderProblems({ ...ORDER, effectiveDate: 'x2026-10-01' })).toContain('i06.bad.effective');
    expect(orderProblems({ ...ORDER, effectiveDate: '2026-10-01x' })).toContain('i06.bad.effective');
    expect(orderProblems({ ...ORDER, orderReference: 'éPEN/1' })).toContain('i06.bad.reference');
    expect(orderProblems({ ...ORDER, orderReference: 'PEN/1é' })).toContain('i06.bad.reference');
    expect(orderProblems({ ...ORDER, instalments: '0' })).toContain('i06.bad.instalments');
  });

  it('SRS-14-16: a full revoke takes no scope; a partial one exactly one, its period in order and its order dated', () => {
    const revoke = {
      type: 'FULL' as const,
      amount: '  ',
      fromYymm: null,
      toYymm: null,
      orderReference: 'REV/1',
      orderDate: '2026-10-05',
      reason: 'Appeal allowed',
    };
    expect(revokeProblems(revoke, 1000)).toEqual([]);
    expect(revokeProblems({ ...revoke, amount: '5000' }, 1000)).toEqual(['i06.bad.fullScope']);
    expect(revokeProblems({ ...revoke, type: 'PARTIAL', amount: '', toYymm: '202611' }, 1000)).toEqual([
      'i06.bad.period',
    ]);
    expect(
      revokeProblems({ ...revoke, type: 'PARTIAL', amount: '', fromYymm: '202611', toYymm: 'x' }, 1000),
    ).toEqual(['i06.bad.period']);
    expect(
      revokeProblems({ ...revoke, type: 'PARTIAL', amount: '', fromYymm: '202611', toYymm: '202612' }, 1000),
    ).toEqual([]);
    expect(revokeProblems({ ...revoke, orderDate: 'x2026-10-05' }, 1000)).toEqual(['i06.bad.orderDate']);
    expect(revokeProblems({ ...revoke, orderDate: '2026-10-05x' }, 1000)).toEqual(['i06.bad.orderDate']);
  });

  it('SRS-14-2: a payment needs a pay code from one, the member without padding and a sanction after the application', () => {
    expect(paymentProblems(PAYMENT)).toEqual([]);
    expect(paymentProblems({ ...PAYMENT, eis: ' 104882 ' })).toEqual([]);
    expect(paymentProblems({ ...PAYMENT, eis: '$104882' })).toEqual(['i06.bad.eis']);
    expect(paymentProblems({ ...PAYMENT, eis: '104882$' })).toEqual(['i06.bad.eis']);
    expect(paymentProblems({ ...PAYMENT, payCode: '0' })).toEqual(['i06.bad.payCode']);
    expect(paymentProblems({ ...PAYMENT, sanctionDate: '2026-10-01' })).toEqual(['i06.bad.sanctionDate']);
  });
});

describe('I-04 loan field edges', () => {
  it('SRS-12-21: the recovery month is exactly YYYYMM; interest needs its instalments', () => {
    expect(loanYymm('x202601')).toBe(false);
    expect(loanYymm('2026011')).toBe(false);
    const form = {
      eis: '104882',
      loanType: 'HBA',
      sanctioned: '100000',
      principalInstalments: '10',
      instalment: '10000',
      interestAmount: '5000',
      interestInstalments: '',
      interestInstalment: '',
      startYymm: '202611',
    };
    expect(sanctionReady(form)).toBe(false);
    expect(sanctionReady({ ...form, interestInstalments: '5', interestInstalment: '1000' })).toBe(true);
    expect(sanctionReady({ ...form, interestInstalments: '5', interestInstalment: '0' })).toBe(false);
    expect(sanctionReady({ ...form, interestAmount: '', interestInstalments: '3' })).toBe(false);
    expect(sanctionReady({ ...form, interestAmount: '0', interestInstalments: '0' })).toBe(true);
  });
});

describe('I-09 deputation field edges', () => {
  const member: DeputedMember = {
    eis: '104882',
    name: 'Asha Rao',
    deputationFrom: '2026-04-01',
    deputationTo: null,
    advances: [],
    monthsWithoutReceipt: [],
    entries: [],
  };

  it('SRS-6-27: the entry types, an empty entry and a month within the deputation in exact YYYYMM', () => {
    expect(ENTRY_TYPES).toEqual(['RECOVERY_INSTALMENT', 'CONTRIBUTION_RECEIPT', 'EXCESS_REFUND']);
    expect(emptyEntry('104882', 'EXCESS_REFUND')).toEqual({
      eis: '104882',
      borrowingOrgId: '',
      type: 'EXCESS_REFUND',
      sanctionId: '',
      payrollYymm: '',
      instalment: '',
      employee: '',
      employer: '',
      vpf: '',
    });
    expect(inDeputation('202605', member)).toBe(true);
    expect(inDeputation('x202605', member)).toBe(false);
    expect(inDeputation('2026051', member)).toBe(false);
    expect(inDeputation('202613', member)).toBe(false);
    expect(inDeputation('202610', { ...member, deputationTo: '2026-09-30' })).toBe(false);
  });

  it('SRS-6-28: amounts are read without padding, a blank amount is nil and one share is enough', () => {
    expect(isPaise('  ')).toBe(true);
    expect(isPaise(' 12.5 ')).toBe(true);
    expect(isPaise('1.2.3')).toBe(false);
    expect(isPaise('x12')).toBe(false);
    const receipt = {
      ...emptyEntry('104882', 'CONTRIBUTION_RECEIPT'),
      borrowingOrgId: '3',
      payrollYymm: '202605',
      employee: ' 100 ',
    };
    expect(entryProblems(receipt, member, true)).toEqual([]);
    expect(entryProblems({ ...receipt, employee: '  ' }, member, true)).toEqual(['i09.bad.amount']);
    const refund = {
      ...emptyEntry('104882', 'EXCESS_REFUND'),
      borrowingOrgId: '3',
      payrollYymm: '202605',
      instalment: ' 50 ',
    };
    expect(entryProblems(refund, member, true)).toEqual([]);
    expect(entryProblems({ ...refund, instalment: ' ' }, member, true)).toEqual(['i09.bad.amount']);
  });
});

describe('M-20 leave parameter edges', () => {
  const draft = {
    code: 'ENCASH_PAY_ELEMENTS',
    leaveType: '',
    value: ' 101+111 ',
    effectiveFrom: '2026-11-01',
    reason: 'Order',
  };

  it('SRS-LV-14: each parameter has its kind; pay codes are read without padding; a divisor is above zero', () => {
    expect(LEAVE_PARAMETERS).toEqual({
      LWP_DIVISOR: 'DIVISOR',
      ENCASH_DIVISOR: 'POSITIVE',
      ENCASH_PAY_ELEMENTS: 'PAY_CODES',
      INSERVICE_PER_OCCASION_LIMIT: 'DAYS',
      INSERVICE_PER_YEAR_LIMIT: 'DAYS',
      SEPARATION_AGGREGATE_CEILING: 'DAYS',
      ENCASH_PAY_CODES: 'PAY_CODES',
      PAY_EFFECT: 'PAY_EFFECT',
    });
    expect(draftProblem(draft)).toBeNull();
    expect(draftProblem({ ...draft, code: 'LWP_DIVISOR', value: '0' })).toBe('m20.problem.value');
    expect(draftProblem({ ...draft, code: 'LWP_DIVISOR', value: 'CALENDAR_DAYS' })).toBeNull();
  });

  it('SRS-LV-14: the date and the reason are exact: nothing around the date, printable characters only', () => {
    expect(draftProblem({ ...draft, effectiveFrom: 'x2026-11-01' })).toBe('m20.problem.date');
    expect(draftProblem({ ...draft, effectiveFrom: '2026-11-01x' })).toBe('m20.problem.date');
    expect(draftProblem({ ...draft, reason: 'éOrder' })).toBe('m20.problem.reason');
    expect(draftProblem({ ...draft, reason: 'Orderé' })).toBe('m20.problem.reason');
  });
});
