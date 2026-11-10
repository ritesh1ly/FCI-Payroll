/**
 * I-24 screen rules (plan 13 phase 13d; SRS-8-23, SRS-8-24). The server checks each again; these
 * only decide what the screen offers.
 */
import type { LeArrearRow, RunRequest } from '../api/leaveArrearApi';

export const LE_STATUSES = [
  'CALCULATED',
  'SUBMITTED',
  'APPROVED',
  'INVOICED',
  'PAID',
  'CANCELLED',
  'NOT_ELIGIBLE',
] as const;

/** SRS-8-24: only a calculated record is selected for submission. */
export function selectable(r: LeArrearRow): boolean {
  return r.status === 'CALCULATED';
}

/** SRS-8-23: a run needs the revision order, its effective date and a reason. */
export function runReady(r: Partial<RunRequest>): boolean {
  return (
    typeof r.circularId === 'number' &&
    !!r.revisionEffective &&
    /^\d{4}-\d{2}-\d{2}$/.test(r.revisionEffective) &&
    !!r.reason &&
    r.reason.trim().length > 0
  );
}

/**
 * I-24 Old Pay / Revised Pay (SRS l.3138): Basic + DA, shown only when both parts are known - a
 * partial sum would read as the pay (UI review I-24 #6).
 */
export function payOf(basic: number | null, da: number | null): number | null {
  return basic === null || da === null ? null : basic + da;
}

/**
 * I-24 Download Calculation Sheet (SRS-1-43 "separate, month-wise, downloadable calculation
 * sheet"; SRS l.3129): one record's working as CSV rows of label and value - the inputs as
 * received, old and revised pay (Basic + DA), the amount paid and due, the arrear (due less paid,
 * SRS l.3139) and the TDS. Values are the server's; nothing is recomputed here.
 */
export function calculationSheet(r: LeArrearRow, label: (key: string) => string): string {
  const value = (v: number | string | null) => (v === null ? '' : String(v));
  const rows: [string, number | string | null][] = [
    [label('i24.record'), r.recordNumber],
    [label('i24.member'), `${r.eis} ${r.name}`],
    [label('i24.revisionOrder'), r.order],
    [label('i24.effective'), r.revisionEffective],
    [label('i24.month'), r.month],
    [label('i24.days'), r.days],
    [label('i24.sheet.oldBasic'), r.oldBasic],
    [label('i24.sheet.oldDa'), r.oldDa],
    [label('i24.oldPay'), payOf(r.oldBasic, r.oldDa)],
    [label('i24.sheet.revisedBasic'), r.revisedBasic],
    [label('i24.sheet.revisedDa'), r.revisedDa],
    [label('i24.revisedPay'), payOf(r.revisedBasic, r.revisedDa)],
    [label('i24.paid'), r.paid],
    [label('i24.due'), r.due],
    [label('i24.arrear'), r.arrear],
    [label('i24.tds'), r.tds],
  ];
  // RFC 4180: a field with a comma, quote or line break is quoted, quotes doubled.
  const field = (s: string) => (/[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  return rows.map(([k, v]) => `${field(k)},${field(value(v))}`).join('\r\n') + '\r\n';
}

/** The total arrear of the selected records. */
export function selectedTotal(rows: LeArrearRow[], ids: Set<number>): number {
  return rows.filter((r) => ids.has(r.id)).reduce((sum, r) => sum + (r.arrear ?? 0), 0);
}

/** The total TDS of the selected records (the sub-line of the tile "Arrear (selected)"). */
export function selectedTds(rows: LeArrearRow[]): number {
  return rows.reduce((sum, r) => sum + r.tds, 0);
}

/** DA as a percentage of Basic, for the sheet's "Basic + DA n%"; null when a part is not known. */
export function daPercent(basic: number | null, da: number | null): number | null {
  return basic === null || da === null || basic <= 0 ? null : Math.round((da / basic) * 100);
}

/** SRS-G-37: free until the invoice is sent; submitted records wait for the Payroll Checker. */
export function cancellable(r: LeArrearRow): boolean {
  return r.status === 'CALCULATED' || r.status === 'APPROVED';
}

/** A not eligible record is no payment: the invoice card lists the others. */
export function isPayment(r: LeArrearRow): boolean {
  return r.status !== 'NOT_ELIGIBLE';
}

/** BR-AUDIT-010: the record's status read as the invoice's (the DDL has no invoice column of its own). */
export function invoiceCode(r: LeArrearRow): 'NOT_RAISED' | 'SENT_TO_APPS' {
  return r.status === 'INVOICED' || r.status === 'PAID' ? 'SENT_TO_APPS' : 'NOT_RAISED';
}

/** The pill of the column Invoice status, as drawn: Ready, Sent to APPS, Not eligible. */
export function statusTone(status: LeArrearRow['status']): string {
  if (status === 'INVOICED') return 'p-info';
  if (status === 'PAID' || status === 'APPROVED') return 'p-ok';
  if (status === 'NOT_ELIGIBLE' || status === 'CANCELLED') return 'p-gray';
  return 'p-warn';
}
