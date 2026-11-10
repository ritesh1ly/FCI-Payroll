import { describe, expect, it } from 'vitest';
import type { LeaveParameterVersion } from '@/modules/leave/api/leaveApi';
import type { LeArrearRow } from '@/modules/leave/api/leaveArrearApi';
import {
  LE_STATUSES,
  cancellable,
  daPercent,
  invoiceCode,
  isPayment,
  selectedTds,
  statusTone,
} from '@/modules/leave/business/leaveArrearRules';
import { DRAWN_PARAMETERS, drawnParameters } from '@/modules/leave/business/leaveRules';

/** I-24 leave encashment arrear statuses (SRS-8-23, SRS-8-24, SRS-G-37, BR-AUDIT-010) and M-20 parameters (SRS-LV-14). */
describe('I-24 arrear record status rules', () => {
  const rec = (status: LeArrearRow['status'], tds = 0): LeArrearRow => ({ status, tds }) as LeArrearRow;

  it('SRS-8-24: the TDS of the selected records adds up', () => {
    expect(selectedTds([])).toBe(0);
    expect(selectedTds([rec('CALCULATED', 120), rec('CALCULATED', 30)])).toBe(150);
  });

  it('SRS-8-23: DA as a whole percentage of Basic; none when a part is unknown or Basic is not positive', () => {
    expect(daPercent(1000, 530)).toBe(53);
    expect(daPercent(3000, 1001)).toBe(33);
    expect(daPercent(200, 1)).toBe(1);
    expect(daPercent(1000, 0)).toBe(0);
    expect(daPercent(null, 100)).toBeNull();
    expect(daPercent(1000, null)).toBeNull();
    expect(daPercent(null, null)).toBeNull();
    expect(daPercent(0, 100)).toBeNull();
    expect(daPercent(-10, 100)).toBeNull();
    expect(daPercent(1, 1)).toBe(100);
  });

  it('SRS-G-37: cancellable while calculated or approved, never once submitted, invoiced or paid', () => {
    expect(cancellable(rec('CALCULATED'))).toBe(true);
    expect(cancellable(rec('APPROVED'))).toBe(true);
    for (const s of LE_STATUSES.filter((x) => x !== 'CALCULATED' && x !== 'APPROVED'))
      expect(cancellable(rec(s))).toBe(false);
  });

  it('SRS-8-24: a not eligible record is no payment', () => {
    expect(isPayment(rec('NOT_ELIGIBLE'))).toBe(false);
    for (const s of LE_STATUSES.filter((x) => x !== 'NOT_ELIGIBLE')) expect(isPayment(rec(s))).toBe(true);
  });

  it('BR-AUDIT-010: invoiced and paid records read as sent to APPS, the others not raised', () => {
    expect(invoiceCode(rec('INVOICED'))).toBe('SENT_TO_APPS');
    expect(invoiceCode(rec('PAID'))).toBe('SENT_TO_APPS');
    for (const s of ['CALCULATED', 'SUBMITTED', 'APPROVED', 'CANCELLED', 'NOT_ELIGIBLE'] as const)
      expect(invoiceCode(rec(s))).toBe('NOT_RAISED');
  });

  it('SRS-8-24: the invoice status pill of every status', () => {
    expect(LE_STATUSES.map((s) => [s, statusTone(s)])).toEqual([
      ['CALCULATED', 'p-warn'],
      ['SUBMITTED', 'p-warn'],
      ['APPROVED', 'p-ok'],
      ['INVOICED', 'p-info'],
      ['PAID', 'p-ok'],
      ['CANCELLED', 'p-gray'],
      ['NOT_ELIGIBLE', 'p-gray'],
    ]);
  });
});

describe('M-20 drawn parameters (SRS-LV-14)', () => {
  let id = 0;
  const v = (code: string, status: string, leaveType: string | null = null): LeaveParameterVersion =>
    ({ id: ++id, code, leaveType, status }) as LeaveParameterVersion;

  it('SRS-LV-14: each drawn parameter shows its common version in force and the proposal waiting', () => {
    const active = v('LWP_DIVISOR', 'ACTIVE');
    const pending = v('LWP_DIVISOR', 'PENDING_APPROVAL');
    const typed = v('LWP_DIVISOR', 'ACTIVE', 'EL');
    const typedPending = v('LWP_DIVISOR', 'PENDING_APPROVAL', 'EL');
    const old = v('LWP_DIVISOR', 'SUPERSEDED');
    const other = v('ENCASH_DIVISOR', 'ACTIVE');
    const rows = drawnParameters([typed, typedPending, old, pending, active, other]);
    expect(rows.map((r) => r.code)).toEqual([...DRAWN_PARAMETERS]);
    expect(rows[0]).toEqual({ code: 'LWP_DIVISOR', current: active, pending });
    expect(rows[1]).toEqual({ code: 'ENCASH_DIVISOR', current: other, pending: null });
    expect(rows[2]).toEqual({ code: 'ENCASH_PAY_ELEMENTS', current: null, pending: null });
  });

  it('SRS-LV-14: a version of one leave type only is not the common value', () => {
    const rows = drawnParameters([
      v('ENCASH_PAY_CODES', 'ACTIVE', 'HPL'),
      v('ENCASH_PAY_CODES', 'PENDING_APPROVAL', 'HPL'),
    ]);
    expect(rows[6]).toEqual({ code: 'ENCASH_PAY_CODES', current: null, pending: null });
  });
});
