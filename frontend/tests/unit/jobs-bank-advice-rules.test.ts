import { describe, expect, it } from 'vitest';
import type { BankRow, GlRow } from '@/modules/jobs/api/bankAdviceApi';
import { adviceTone, releaseStatus, sumOf } from '@/modules/jobs/business/bankAdviceRules';

/**
 * O-02 Bank Advice (SRS-15-34, SRS l.1709): the month's Release Status (BR-SEC-006 suspension first), the
 * pill of each bank's advice (DDL bank_advice.advice_status) and the foot totals of the GL Posting Preview.
 */

function bank(status: string, name = 'SBI'): BankRow {
  return { bank: name, members: 10, net: 1000, blocked: 0, fileFormat: null, status };
}

describe('O-02 release status', () => {
  it('BR-SEC-006: a suspended month reads Suspended even when an advice is released', () => {
    expect(releaseStatus({ suspended: true, bankRows: [bank('RELEASED')] })).toBe('SUSPENDED');
    expect(releaseStatus({ suspended: true, bankRows: [] })).toBe('SUSPENDED');
  });

  it('SRS-15-34: any advice released, sent or acknowledged makes the month Released', () => {
    for (const s of ['RELEASED', 'SENT', 'ACKNOWLEDGED']) {
      expect(releaseStatus({ suspended: false, bankRows: [bank('GENERATED', 'A'), bank(s, 'B')] })).toBe(
        'RELEASED',
      );
    }
  });

  it('SRS-15-34: generated advices only read Generated; none or only cancelled read Not generated', () => {
    expect(
      releaseStatus({ suspended: false, bankRows: [bank('CANCELLED', 'A'), bank('GENERATED', 'B')] }),
    ).toBe('GENERATED');
    expect(releaseStatus({ suspended: false, bankRows: [] })).toBe('NOT_GENERATED');
    expect(releaseStatus({ suspended: false, bankRows: [bank('CANCELLED')] })).toBe('NOT_GENERATED');
    expect(releaseStatus({ suspended: false, bankRows: [bank('released')] })).toBe('NOT_GENERATED');
  });
});

describe('O-02 advice pill', () => {
  it('SRS-15-34: released states green, generated blue, cancelled red, anything else grey', () => {
    expect(adviceTone('RELEASED')).toBe('p-ok');
    expect(adviceTone('SENT')).toBe('p-ok');
    expect(adviceTone('ACKNOWLEDGED')).toBe('p-ok');
    expect(adviceTone('GENERATED')).toBe('p-info');
    expect(adviceTone('CANCELLED')).toBe('p-bad');
    expect(adviceTone('DRAFT')).toBe('p-gray');
    expect(adviceTone('')).toBe('p-gray');
  });
});

describe('O-02 GL posting preview', () => {
  const rows: GlRow[] = [
    { head: 'Salary', account: '1001', debit: 1500.5, credit: null },
    { head: 'Bank', account: '2001', debit: null, credit: 1200 },
    { head: 'CPF', account: '3001', debit: 0, credit: 300.5 },
  ];

  it('SRS-15-41: each foot totals its side, a missing figure counting nothing', () => {
    expect(sumOf(rows, 'debit')).toBe(1500.5);
    expect(sumOf(rows, 'credit')).toBe(1500.5);
    expect(sumOf([], 'debit')).toBe(0);
    expect(sumOf([{ head: 'x', account: 'y', debit: null, credit: null }], 'credit')).toBe(0);
  });
});
