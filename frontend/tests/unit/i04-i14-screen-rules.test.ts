import { describe, expect, it } from 'vitest';
import type { LoanRow } from '@/modules/loans/api/loansApi';
import { limitCheck, limitRuleKey, statusKey, tierCell } from '@/modules/cpf/business/sanctionRules';
import { changeTarget, crore, ledgerPill, ledgerStatus } from '@/modules/loans/business/loanRules';
import { memberLine, periodOf, statusPill } from '@/modules/nps/business/npsRules';

/** The drawn cells of I-04 and I-14 (OD-121; SRS-12-1, SRS-12-5, SRS-6-4, BR-CPF-029, SRS-9-1a). */
describe('I-04 ledger rules', () => {
  it('SRS-12-1: instalments that miss the principal win over any status', () => {
    expect(ledgerStatus({ status: 'STOPPED', separated: true, scheduleReconciled: false })).toBe(
      'SCHEDULE_OFF',
    );
  });

  it('SRS-12-5: a stopped loan is halted for separation only when the member separated', () => {
    expect(ledgerStatus({ status: 'STOPPED', separated: true, scheduleReconciled: true })).toBe(
      'HALTED_SEPARATION',
    );
    expect(ledgerStatus({ status: 'STOPPED', separated: false, scheduleReconciled: true })).toBe('STOPPED');
    expect(ledgerStatus({ status: 'ACTIVE', separated: true })).toBe('ACTIVE');
    expect(ledgerStatus({ status: 'ACTIVE' })).toBe('ACTIVE');
  });

  it.each([
    ['SCHEDULE_OFF', 'p-bad'],
    ['STOPPED', 'p-warn'],
    ['HALTED_SEPARATION', 'p-warn'],
    ['ACTIVE', 'p-ok'],
    ['RESCHEDULED', 'p-ok'],
    ['CLOSED', 'p-gray'],
    ['FORECLOSED', 'p-gray'],
  ] as const)('%s reads as %s', (status, pill) => {
    expect(ledgerPill(status)).toBe(pill);
  });

  type Loan = Pick<LoanRow, 'status' | 'source'>;
  const hrms: Loan = { status: 'ACTIVE', source: 'HRMS' };
  const labour: Loan = { status: 'ACTIVE', source: 'LABOUR_ENT' };
  const closed: Loan = { status: 'CLOSED', source: 'LABOUR_ENT' };

  it('SRS-12-17: the change opens the chosen loan set up here, else the first on the page', () => {
    expect(changeTarget(labour, [hrms])).toBe(labour);
    expect(changeTarget(hrms, [hrms, labour])).toBe(labour);
    expect(changeTarget(null, [closed, labour])).toBe(labour);
    expect(changeTarget(hrms, [hrms, closed])).toBeNull();
    expect(changeTarget(null, [])).toBeNull();
  });

  it('reads rupees as crore with two decimals', () => {
    expect(crore(67100000)).toBe('6.71');
    expect(crore(0)).toBe('0.00');
  });
});

describe('I-04 CPF card rules', () => {
  it.each([
    ['UNIT_VERIFICATION', 'ADVANCE', 'i04c.tier.unit', 'p-gray'],
    ['ZONAL_HQ_VALIDATION', 'PART_FINAL', 'i04c.tier.division', 'p-warn'],
    ['COMPETENT_AUTHORITY', 'ADVANCE', 'i04c.tier.authority', 'p-info'],
    ['COMPETENT_AUTHORITY', 'FINAL', 'i04c.tier.authorityFinal', 'p-warn'],
    ['SANCTIONED', 'ADVANCE', 'i04c.tier.authority', 'p-ok'],
    ['PAID', 'FINAL', 'i04c.tier.authorityFinal', 'p-ok'],
    ['PAYMENT_BLOCKED', 'ADVANCE', 'i04c.tier.authority', 'p-ok'],
  ] as const)('SRS-6-4: %s of %s draws its tier', (stage, type, key, pill) => {
    expect(tierCell({ stage, type })).toEqual({ key, pill });
  });

  it.each(['RETURNED', 'REJECTED', 'CANCELLED'] as const)('%s waits at no tier', (stage) => {
    expect(tierCell({ stage, type: 'ADVANCE' })).toBeNull();
  });

  it('draws what the tier is checking; a capped amount says so first', () => {
    const of = (
      stage: 'UNIT_VERIFICATION' | 'ZONAL_HQ_VALIDATION' | 'COMPETENT_AUTHORITY' | 'PAID',
      type: 'ADVANCE' | 'FINAL' = 'ADVANCE',
      capped = false,
    ) => statusKey({ stage, type, capped });
    expect(of('UNIT_VERIFICATION')).toBe('i04c.status.kyc');
    expect(of('ZONAL_HQ_VALIDATION')).toBe('i04c.status.limit');
    expect(of('COMPETENT_AUTHORITY')).toBe('i04c.status.authority');
    expect(of('COMPETENT_AUTHORITY', 'FINAL')).toBe('i04c.status.final');
    expect(of('PAID')).toBe('i04.stage.PAID');
    expect(of('PAID', 'ADVANCE', true)).toBe('i04c.status.capped');
  });

  it('BR-CPF-029: final payment has its basis, a rule its name, none reads as configured', () => {
    expect(limitRuleKey({ type: 'FINAL', limitRule: 'x' })).toBe('i04c.rule.final');
    expect(limitRuleKey({ type: 'ADVANCE', limitRule: 'x' })).toBeNull();
    expect(limitRuleKey({ type: 'ADVANCE', limitRule: null })).toBe('i04c.rule.configured');
    expect(limitRuleKey({ type: 'PART_FINAL' })).toBe('i04c.rule.configured');
  });

  it('BR-CPF-029: compares the stored ceiling, the amount at the ceiling is within', () => {
    expect(limitCheck({ requested: 100, ceiling: 100 })).toBe('within');
    expect(limitCheck({ requested: 101, ceiling: 100 })).toBe('over');
    expect(limitCheck({ requested: 100, ceiling: null })).toBe('pending');
    expect(limitCheck({ requested: null, ceiling: 100 })).toBe('pending');
  });
});

describe('I-14 rules', () => {
  it('turns the month into the session period', () => {
    expect(periodOf('202607')).toBe('2026-07');
  });

  it('BR-PEN-001: a mismatch reads bad, applied ok, the rest info', () => {
    expect(statusPill('FLAG_WITHOUT_AMOUNT')).toBe('p-bad');
    expect(statusPill('AMOUNT_WITHOUT_FLAG')).toBe('p-bad');
    expect(statusPill('APPLIED')).toBe('p-ok');
    expect(statusPill('RECEIVED')).toBe('p-info');
  });

  it('draws the pay pattern and a fund that is not NPS', () => {
    expect(memberLine('CDA', 'NPS')).toBe('CDA');
    expect(memberLine('CDA', 'GPF')).toBe('CDA, GPF');
    expect(memberLine(null, null)).toBe('');
    expect(memberLine(undefined, 'GPF')).toBe('GPF');
  });
});
