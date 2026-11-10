import { describe, expect, it } from 'vitest';
import type { LeArrearRow } from '@/modules/leave/api/leaveArrearApi';
import {
  calculationSheet,
  payOf,
  runReady,
  selectable,
  selectedTotal,
} from '@/modules/leave/business/leaveArrearRules';

/**
 * I-24 leave-encashment arrear rules (plan 13 phase 13d; SRS-8-23, SRS-8-24, SRS-1-43), each with
 * its boundaries: the rules file's own tests, so mutation testing reaches the calculation sheet.
 */

function row(over: Partial<LeArrearRow> = {}): LeArrearRow {
  return {
    id: 1,
    recordNumber: 'LEA/1',
    eis: '104882',
    name: 'Ram Kumar',
    separationDate: '2026-03-31',
    order: 'WR/2026',
    revisionEffective: '2026-01-01',
    month: '202603',
    days: 300,
    oldBasic: 50000,
    oldDa: 2000,
    revisedBasic: 52000,
    revisedDa: 2500,
    paid: 520000,
    due: 545000,
    arrear: 25000,
    tds: 2500,
    status: 'CALCULATED',
    ineligibleReason: null,
    batch: null,
    ...over,
  };
}

describe('I-24 pay and sheet', () => {
  it('SRS-8-23: Basic + DA only when both parts are known', () => {
    expect(payOf(50000, 2000)).toBe(52000);
    expect(payOf(0, 0)).toBe(0);
    expect(payOf(null, 2000)).toBeNull();
    expect(payOf(50000, null)).toBeNull();
    expect(payOf(null, null)).toBeNull();
  });

  it('SRS-1-43: the calculation sheet lists every input, the pays, the arrear and the TDS as CSV', () => {
    const label = (k: string) => `<${k}>`;
    expect(calculationSheet(row(), label)).toBe(
      [
        '<i24.record>,LEA/1',
        '<i24.member>,104882 Ram Kumar',
        '<i24.revisionOrder>,WR/2026',
        '<i24.effective>,2026-01-01',
        '<i24.month>,202603',
        '<i24.days>,300',
        '<i24.sheet.oldBasic>,50000',
        '<i24.sheet.oldDa>,2000',
        '<i24.oldPay>,52000',
        '<i24.sheet.revisedBasic>,52000',
        '<i24.sheet.revisedDa>,2500',
        '<i24.revisedPay>,54500',
        '<i24.paid>,520000',
        '<i24.due>,545000',
        '<i24.arrear>,25000',
        '<i24.tds>,2500',
        '',
      ].join('\r\n'),
    );
  });

  it('SRS-1-43: an unknown value is an empty field; a comma, quote or line break is quoted (RFC 4180)', () => {
    const sheet = calculationSheet(
      row({ order: null, days: null, oldDa: null, revisedBasic: null, name: 'Kumar, "Ram"' }),
      (k) => (k === 'i24.month' ? 'Month\nYYYYMM' : k === 'i24.days' ? 'Days\rcount' : k),
    );
    const lines = sheet.split('\r\n');
    expect(lines[1]).toBe('i24.member,"104882 Kumar, ""Ram"""');
    expect(lines[2]).toBe('i24.revisionOrder,');
    expect(sheet).toContain('"Month\nYYYYMM",202603');
    expect(sheet).toContain('"Days\rcount",\r\n');
    expect(lines).toContain('i24.oldPay,');
    expect(lines).toContain('i24.revisedPay,');
    expect(lines).toContain('i24.sheet.oldDa,');
    expect(sheet.endsWith('i24.tds,2500\r\n')).toBe(true);
  });

  it('a value without a special character stays unquoted', () => {
    const sheet = calculationSheet(row({ name: 'Ram' }), (k) => k);
    expect(sheet).not.toContain('"');
  });
});

describe('I-24 selection and run', () => {
  it('SRS-8-24: only a calculated record is selectable; the total adds the selected arrears', () => {
    expect(selectable(row())).toBe(true);
    expect(selectable(row({ status: 'SUBMITTED' }))).toBe(false);
    const rows = [
      row(),
      row({ id: 2, arrear: 500 }),
      row({ id: 3, arrear: null }),
      row({ id: 4, arrear: 7 }),
    ];
    expect(selectedTotal(rows, new Set([1, 2, 3]))).toBe(25500);
    expect(selectedTotal(rows, new Set())).toBe(0);
    expect(selectedTotal(rows, new Set([3]))).toBe(0);
  });

  it('SRS-8-23: a run needs the order, an ISO effective date and a reason', () => {
    const ready = { circularId: 4, revisionEffective: '2026-01-01', reason: 'Revision' };
    expect(runReady(ready)).toBe(true);
    expect(runReady({ ...ready, circularId: undefined })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: 'x2026-01-01' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '2026-01-01x' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '26-01-01' })).toBe(false);
    expect(runReady({ ...ready, reason: '' })).toBe(false);
    expect(runReady({ ...ready, reason: '  ' })).toBe(false);
    expect(runReady({ ...ready, reason: undefined })).toBe(false);
  });
});
