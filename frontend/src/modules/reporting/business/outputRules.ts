import type { PayslipLevel, PayslipPeriod, PayslipRow, RemittanceRun } from '../api/outputsApi';

/** The drawn O-01 levels, in drawn order. */
export const PAYSLIP_LEVELS: readonly PayslipLevel[] = ['STAFF', 'LABOUR_DEPOT', 'LABOUR_GANG'];

const PILL: Record<string, string> = {
  READY: 'p-ok',
  HELD: 'p-bad',
  SUPPRESSED: 'p-bad',
  PROVISIONAL: 'p-warn',
  APPROVED: 'p-ok',
  POSTED_TO_AP: 'p-ok',
  SENT: 'p-ok',
  GENERATED: 'p-info',
  CANCELLED: 'p-bad',
};

/** The pill of a payslip or a remittance status. */
export function statusPill(status: string): string {
  return PILL[status] ?? 'p-gray';
}

/** yyyymm as the session's period text. */
export function monthOf(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4)}`;
}

/** SRS-17-1, drawn C-08 note: a held or suppressed member has no payslip to show; the reason's key. */
export function noSlipKey(row: Pick<PayslipRow, 'status'> | null): string | null {
  if (row === null) return 'o01.why.selectRow';
  if (row.status === 'HELD') return 'o01.why.held';
  return row.status === 'SUPPRESSED' ? 'o01.why.suppressed' : null;
}

/** The row opened first: the first one with a payslip to show, else the first. */
export function firstOpenRow(rows: PayslipRow[]): PayslipRow | null {
  return rows.find((r) => noSlipKey(r) === null) ?? rows[0] ?? null;
}

/** The month O-01 opens on: the newest with results, else the newest; '' with no month at all. */
export function firstPeriod(periods: PayslipPeriod[] | undefined): string {
  const months = periods ?? [];
  return (months.find((p) => p.results) ?? months[0])?.yymm ?? '';
}

/** The earnings and deductions side by side, as the drawn four-column table. */
export function slipRows<L>(earnings: L[], deductions: L[]): [L | undefined, L | undefined][] {
  const rows: [L | undefined, L | undefined][] = [];
  for (let i = 0; i < Math.max(earnings.length, deductions.length); i += 1) {
    rows.push([earnings[i], deductions[i]]);
  }
  return rows;
}

/** The year of the last month of a financial year ("2025-26" closes in March 2026). */
export function closingYear(finYear: string): number {
  return Number(finYear.slice(0, 4)) + 1;
}

/** The drawn invoice range: the second number shortened to its serial when the month prefix repeats. */
export function invoiceRange(run: Pick<RemittanceRun, 'invoiceFrom' | 'invoiceTo'>): string | null {
  const { invoiceFrom: from, invoiceTo: to } = run;
  if (!from) return null;
  if (!to || to === from) return from;
  const serial = to.slice(-6);
  return to.length === from.length && to.slice(0, -6) === from.slice(0, -6)
    ? `${from} – ${serial}`
    : `${from} – ${to}`;
}
