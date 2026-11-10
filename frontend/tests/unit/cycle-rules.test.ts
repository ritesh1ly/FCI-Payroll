import { describe, expect, it } from 'vitest';
import {
  correctionBlocked,
  crore,
  MAX_RETURN_MEMBERS,
  pliCancelled,
  pliSent,
  rerunnable,
  resultEditWhy,
  resultTone,
  searchText,
  traceRows,
  memberList,
  returnable,
  unfreezable,
} from '@/modules/jobs/business/cycleRules';

/** SRS-15-30 / SRS-15-31 / SRS-15-10 (plan 05 phase 05d): the screen's mirror of the server's rules. */
describe('C-02 correction rules', () => {
  it.each([
    [null, false, false],
    ['INITIATED', false, false],
    ['FROZEN', false, true],
    ['COMPUTED', true, true],
    ['VERIFIED', true, true],
    ['LOCKED', false, false],
  ])('SRS-15-10: %s returnable %s, un-freezable %s', (status, canReturn, canUnfreeze) => {
    expect(returnable(status)).toBe(canReturn);
    expect(unfreezable(status)).toBe(canUnfreeze);
  });

  it('SRS-15-30: members are split on spaces, commas, semicolons and lines, each once, at most the limit', () => {
    expect(memberList('')).toEqual([]);
    expect(memberList(' 100001,100002;\n100001  100003 ')).toEqual(['100001', '100002', '100003']);
    const many = Array.from({ length: MAX_RETURN_MEMBERS + 5 }, (_, i) => String(100000 + i)).join(' ');
    expect(memberList(many)).toHaveLength(MAX_RETURN_MEMBERS);
  });

  it('SRS-15-28 / SRS-15-31: a held lease first, then a waiting lock, then a waiting un-freeze', () => {
    expect(correctionBlocked({ held: false, lockPending: false, unfreezePending: false })).toBeNull();
    expect(correctionBlocked({ held: true, lockPending: true, unfreezePending: true })).toBe('held');
    expect(correctionBlocked({ held: false, lockPending: true, unfreezePending: true })).toBe(
      'cyc.blocked.lockPending',
    );
    expect(correctionBlocked({ held: false, lockPending: false, unfreezePending: true })).toBe(
      'cyc.blocked.unfreezePending',
    );
  });
});

describe('C-02 as drawn (Wireframes v6.1, OD-121)', () => {
  const line = (head: string, payCode = 1) => ({
    payCode,
    name: head,
    head,
    amount: 10,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  });

  it('BR-CYC-008: a re-run needs a frozen or computed cycle', () => {
    expect(rerunnable('FROZEN')).toBe(true);
    expect(rerunnable('COMPUTED')).toBe(true);
    expect(rerunnable('VERIFIED')).toBe(false);
    expect(rerunnable('INITIATED')).toBe(false);
    expect(rerunnable(null)).toBe(false);
  });

  it('list search keeps an EIS number or a name, no wildcard, 60 characters at most', () => {
    expect(searchText("O'Brien-K. 12%_*")).toBe("O'Brien-K. 12");
    expect(searchText('x'.repeat(70))).toHaveLength(60);
    expect(searchText('')).toBe('');
  });

  it('rupees read as crore with two decimals', () => {
    expect(crore(0)).toBe('0.00');
    expect(crore(3124000000)).toBe('312.40');
    expect(crore(-10000000)).toBe('-1.00');
  });

  it('the pill: computed, a fault, or a warning', () => {
    expect(resultTone('COMPUTED')).toBe('p-ok');
    for (const s of ['NEGATIVE_NET', 'ZERO_NET', 'FLOOR_BREACH', 'DEDUCTION_CEILING', 'QUARANTINED']) {
      expect(resultTone(s)).toBe('p-bad');
    }
    expect(resultTone('MISSING_BANK')).toBe('p-bad');
    expect(resultTone('BANK_DETAILS')).toBe('p-bad');
    expect(resultTone('HRA_LEASE_CONFLICT')).toBe('p-warn');
    expect(resultTone('PAYMENT_HELD')).toBe('p-warn');
  });

  it('BR-AUDIT-005: Edit is off with the reason of the row state', () => {
    const why = (rowStatus: string, locked = false) => resultEditWhy({ rowStatus, locked });
    expect(why('MISSING_BANK')).toBe('c02.why.bank');
    expect(why('BANK_DETAILS', true)).toBe('c02.why.bank');
    expect(why('HRA_LEASE_CONFLICT', true)).toBe('c02.why.lease');
    expect(why('COMPUTED', true)).toBe('c02.why.locked');
    expect(why('COMPUTED')).toBe('c02.why.computed');
    expect(why('PAYMENT_HELD', true)).toBe('c02.why.locked');
    expect(why('PAYMENT_HELD')).toBe('c02.why.computed');
    expect(why('NEGATIVE_NET', true)).toBe('c02.why.exceptions');
  });

  it('BR-AUDIT-009: a PLI invoice sent to APPS, and what counts as cancelled', () => {
    expect(pliSent({ invoiceStatus: 'SENT_TO_APPS' })).toBe(true);
    expect(pliSent({ invoiceStatus: 'CANCEL_REQUESTED' })).toBe(true);
    expect(pliSent({ invoiceStatus: 'NOT_RAISED' })).toBe(false);
    expect(pliSent({ invoiceStatus: 'CANCELLED_IN_APPS' })).toBe(false);
    expect(pliCancelled({ invoiceStatus: 'CANCELLED_IN_APPS', type: 'PAYMENT' })).toBe(true);
    expect(pliCancelled({ invoiceStatus: 'NOT_RAISED', type: 'CANCELLATION' })).toBe(true);
    expect(pliCancelled({ invoiceStatus: 'SENT_TO_APPS', type: 'PAYMENT' })).toBe(false);
  });

  it('FRS 7.4: the trace lines go to the drawn rows', () => {
    const rows = traceRows({
      lines: [
        line('BASIC'),
        line('DA'),
        line('HRA'),
        line('CPF'),
        line('VPF'),
        line('TDS'),
        line('PT'),
        line('EARNING', 131),
        line('EARNING', 163),
        line('DEDUCTION', 910),
      ],
    });
    expect(rows.basic?.head).toBe('BASIC');
    expect(rows.da?.head).toBe('DA');
    expect(rows.hra?.head).toBe('HRA');
    expect(rows.cpf?.head).toBe('CPF');
    expect(rows.vpf?.head).toBe('VPF');
    expect(rows.tds?.head).toBe('TDS');
    expect(rows.pt?.head).toBe('PT');
    expect(rows.earnings.map((l) => l.payCode)).toEqual([131, 163]);
    expect(rows.recoveries.map((l) => l.payCode)).toEqual([910]);
    expect(traceRows({ lines: [] }).basic).toBeUndefined();
  });
});
