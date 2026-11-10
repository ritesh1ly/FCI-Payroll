import { describe, expect, it } from 'vitest';
import type { CaseDetail, CaseRow, CaseStage } from '@/modules/settlement/api/settlementApi';
import {
  payrollRecovered,
  recoveryReference,
  statementRows,
  stepOf,
} from '@/modules/settlement/business/settlementRules';

/** O-05 worklist step and settlement statement (SRS-10-6, SRS-10-8, SRS-10-12, SRS-10-13, SRS-10-20, SRS-10-24). */
describe('O-05 step pill and statement rows', () => {
  const row = (stage: CaseStage, over: Partial<CaseRow> = {}): CaseRow =>
    ({
      stage,
      separationType: 'SUPERANNUATION',
      awaitingChecker: false,
      hqApprovalPending: false,
      netPayable: 1000,
      ...over,
    }) as CaseRow;

  it('SRS-10-8: a posted or closed case shows its own stage as done, even while flagged for a decision', () => {
    expect(stepOf(row('POSTED'))).toEqual({ number: 7, text: 'POSTED', tone: 'p-ok' });
    expect(stepOf(row('CLOSED', { awaitingChecker: true, netPayable: -5 }))).toEqual({
      number: 8,
      text: 'CLOSED',
      tone: 'p-ok',
    });
    expect(stepOf(row('APPROVED'))).toEqual({ number: 6, text: 'APPROVED', tone: 'p-warn' });
  });

  it('SRS-10-8: below stage 7 the case shows its DDL stage, pending', () => {
    expect(stepOf(row('CLEARANCES_RAISED'))).toEqual({
      number: 2,
      text: 'CLEARANCES_RAISED',
      tone: 'p-warn',
    });
    expect(stepOf(row('COMPUTED', { netPayable: -5 }))).toEqual({
      number: 4,
      text: 'COMPUTED',
      tone: 'p-warn',
    });
  });

  it('SRS-10-14: a settlement with the Checker reads as step 6', () => {
    expect(stepOf(row('COMPUTED', { awaitingChecker: true }))).toEqual({
      number: 6,
      text: 'CHECKER',
      tone: 'p-warn',
    });
    expect(stepOf(row('COMPUTED', { awaitingChecker: true, netPayable: 0 }))).toEqual({
      number: 6,
      text: 'CHECKER',
      tone: 'p-warn',
    });
    expect(stepOf(row('COMPUTED', { awaitingChecker: true, netPayable: null }))).toEqual({
      number: 6,
      text: 'CHECKER',
      tone: 'p-warn',
    });
  });

  it('SRS-10-6: a net recovery is held at step 6, naming Headquarters when its approval is pending (SRS-10-22)', () => {
    expect(stepOf(row('COMPUTED', { awaitingChecker: true, netPayable: -5 }))).toEqual({
      number: 6,
      text: 'NET_RECOVERY',
      tone: 'p-bad',
    });
    expect(stepOf(row('COMPUTED', { hqApprovalPending: true, netPayable: -1 }))).toEqual({
      number: 6,
      text: 'NET_RECOVERY_HQ',
      tone: 'p-bad',
    });
    expect(stepOf(row('COMPUTED', { hqApprovalPending: true }))).toEqual({
      number: 6,
      text: 'COMPUTED',
      tone: 'p-warn',
    });
    expect(stepOf(row('APPROVED', { netPayable: -1 }))).toEqual({
      number: 6,
      text: 'NET_RECOVERY',
      tone: 'p-bad',
    });
  });

  it('SRS-10-20: a death in service at the clearances names the nominee payee as step 3', () => {
    expect(stepOf(row('CLEARANCES_RECEIVED', { separationType: 'DEATH' }))).toEqual({
      number: 3,
      text: 'NOMINEE',
      tone: 'p-warn',
    });
    expect(stepOf(row('SEPARATION_RECEIVED', { separationType: 'DEATH' }))).toEqual({
      number: 3,
      text: 'NOMINEE',
      tone: 'p-warn',
    });
    expect(stepOf(row('COMPUTED', { separationType: 'DEATH' }))).toEqual({
      number: 4,
      text: 'COMPUTED',
      tone: 'p-warn',
    });
    expect(stepOf(row('CLEARANCES_RAISED', { separationType: 'DEATH', awaitingChecker: true }))).toEqual({
      number: 6,
      text: 'CHECKER',
      tone: 'p-warn',
    });
  });

  it('SRS-10-13: the payroll dues carry the recoveries not taken from gratuity', () => {
    expect(payrollRecovered(300, 100)).toBe(200);
    expect(payrollRecovered(300, null)).toBe(300);
    expect(payrollRecovered(null, 100)).toBe(-100);
    expect(payrollRecovered(null, null)).toBe(0);
  });

  it('SRS-10-13: a recovery names its account without the ledger id prefix', () => {
    expect(recoveryReference('12 ABC ACC')).toBe('ABC ACC');
    expect(recoveryReference('123 REF')).toBe('REF');
    expect(recoveryReference('AB 12 CD')).toBe('AB 12 CD');
    expect(recoveryReference('LOAN')).toBe('LOAN');
    expect(recoveryReference(null)).toBeNull();
  });

  const detail = (over: Partial<CaseDetail> = {}): CaseDetail =>
    ({
      summary: { payrollDues: 5000, netPayable: 4200 },
      pendingSalary: 1000,
      arrears: 500,
      leaveEncashment: 2000,
      exGratia: 1500,
      gratuity: 9000,
      cpfAmount: 70000,
      totalRecoveries: 1100,
      recoveredFromGratuity: 300,
      ...over,
    }) as CaseDetail;

  it('SRS-10-12, SRS-10-24: the seven statement rows; gratuity apart with its recovery, CPF for information', () => {
    expect(statementRows(detail())).toEqual([
      { key: 'PENDING', amount: 1500, recovery: 0, strong: false },
      { key: 'LEAVE', amount: 2000, recovery: 0, strong: false },
      { key: 'EX_GRATIA', amount: 1500, recovery: 0, strong: false },
      { key: 'DUES', amount: 5000, recovery: 800, strong: true },
      { key: 'NET', amount: 4200, recovery: 0, strong: true },
      { key: 'GRATUITY', amount: 9000, recovery: 300, strong: false },
      { key: 'CPF_FINAL', amount: 70000, recovery: 0, strong: false },
    ]);
  });

  it('SRS-10-12: pending salary and arrears add up; not computed when both are missing', () => {
    const pending = (over: Partial<CaseDetail>) => statementRows(detail(over))[0]?.amount;
    expect(pending({ pendingSalary: null, arrears: null })).toBeNull();
    expect(pending({ pendingSalary: 1000, arrears: null })).toBe(1000);
    expect(pending({ pendingSalary: null, arrears: 500 })).toBe(500);
    expect(pending({ pendingSalary: 0, arrears: 0 })).toBe(0);
  });

  it('SRS-10-13: no recovery from gratuity leaves its row at zero and the dues carry all', () => {
    const rows = statementRows(detail({ recoveredFromGratuity: null }));
    expect(rows[5]).toEqual({ key: 'GRATUITY', amount: 9000, recovery: 0, strong: false });
    expect(rows[3]?.recovery).toBe(1100);
  });
});
