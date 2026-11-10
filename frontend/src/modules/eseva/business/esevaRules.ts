/**
 * eSeva screen rules (plan 14; SRS-17-6, SRS-17-8, SRS-17-18). The server checks each again;
 * these only decide what the screen offers.
 */
import type { SubmissionStatus, SubmissionType } from '../api/esevaApi';

/** The submission types the log filters by (DDL order). */
export const SUBMISSION_TYPES: readonly SubmissionType[] = [
  'REIMBURSEMENT_CLAIM',
  'LOAN_REQUEST',
  'CPF_ADVANCE_REQUEST',
  'CPF_WITHDRAWAL_REQUEST',
  'CPF_FINAL_PAYMENT_REQUEST',
  'TAX_DECLARATION',
  'REGIME_CHOICE',
  'VPF_SUBSCRIPTION',
  'HRMS_UPDATE_REQUEST',
  'PAYROLL_QUERY',
];

/** The log's statuses in their lifecycle order. */
export const SUBMISSION_STATUSES: readonly SubmissionStatus[] = [
  'SUBMITTED',
  'ROUTED',
  'ACTIONED',
  'REJECTED',
];

/** DDL ess_session.access_reason: required, printable, at most 500 characters (SRS-17-8). */
export const REASON_MAX = 500;

/** SRS-17-8: a preview needs the member's number and a printable reason. */
export function previewReady(eis: string, reason: string): boolean {
  const r = reason.trim();
  return eis.trim().length > 0 && r.length > 0 && r.length <= REASON_MAX && /^[\x20-\x7E\t\r\n]*$/.test(r);
}

/** SRS-17-18: ACTIONED and REJECTED are final; the others are still with the owning module. */
export function isOpen(status: SubmissionStatus): boolean {
  return status === 'SUBMITTED' || status === 'ROUTED';
}

/** The initials of the identity band. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter((p) => p.length > 0)
    .slice(0, 2)
    .map((p) => p.charAt(0).toUpperCase())
    .join('');
}

/** Pairs the two payslip sides row by row, as the drawn table lays them side by side (SRS-17-7). */
export function slipRows<L>(earnings: readonly L[], deductions: readonly L[]): [L | null, L | null][] {
  const rows: [L | null, L | null][] = [];
  for (let i = 0; i < Math.max(earnings.length, deductions.length); i += 1) {
    rows.push([earnings[i] ?? null, deductions[i] ?? null]);
  }
  return rows;
}

/** The pill of a submission's status (SRS-17-17). */
export function requestTone(status: SubmissionStatus): string {
  if (status === 'ACTIONED') return 'p-ok';
  if (status === 'REJECTED') return 'p-bad';
  return 'p-warn';
}

/** The glyph of a row of My Requests, by the submission's family. */
export function requestIcon(type: SubmissionType): string {
  if (type === 'HRMS_UPDATE_REQUEST') return '🔄';
  if (type.startsWith('CPF_') || type === 'VPF_SUBSCRIPTION') return '🏦';
  if (type === 'TAX_DECLARATION' || type === 'REGIME_CHOICE') return '📝';
  return '📨';
}

const ONES = [
  '',
  'One',
  'Two',
  'Three',
  'Four',
  'Five',
  'Six',
  'Seven',
  'Eight',
  'Nine',
  'Ten',
  'Eleven',
  'Twelve',
  'Thirteen',
  'Fourteen',
  'Fifteen',
  'Sixteen',
  'Seventeen',
  'Eighteen',
  'Nineteen',
];
const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];

function below100(n: number): string {
  return n < 20 ? (ONES[n] ?? '') : `${TENS[Math.floor(n / 10)] ?? ''} ${ONES[n % 10] ?? ''}`.trim();
}

function below1000(n: number): string {
  const hundreds = Math.floor(n / 100);
  const rest = below100(n % 100);
  if (hundreds === 0) return rest;
  return `${ONES[hundreds] ?? ''} Hundred${rest ? ` and ${rest}` : ''}`;
}

/** The Indian number groups of a whole number in words: crore, lakh, thousand, the rest. */
function wholeInWords(n: number): string {
  if (n === 0) return 'Zero';
  const crore = Math.floor(n / 10_000_000);
  const lakh = Math.floor(n / 100_000) % 100;
  const thousand = Math.floor(n / 1000) % 100;
  return [
    crore > 0 ? `${wholeInWords(crore)} Crore` : '',
    lakh > 0 ? `${below100(lakh)} Lakh` : '',
    thousand > 0 ? `${below100(thousand)} Thousand` : '',
    below1000(n % 1000),
  ]
    .filter((p) => p.length > 0)
    .join(' ');
}

/** The net pay in words on the Pay Slip document: "Ninety One Thousand ... and Forty Paise" (English only). */
export function amountInWords(value: number): string {
  const paiseTotal = Math.round(Math.abs(value) * 100);
  const rupees = Math.floor(paiseTotal / 100);
  const paise = paiseTotal % 100;
  return `${wholeInWords(rupees)}${paise > 0 ? ` and ${below100(paise)} Paise` : ''}`;
}

/**
 * OD-144: the server PDF of a My Documents row, or null when it has none: a locked month's payslip (the
 * member's own, or the support preview's month), a released Form 16 of the member (Part A merged first).
 * A staff support preview never reads a certificate (its C-10 rights are not E-01's), so it has no path.
 */
export function documentPdfPath(
  doc: { kind: string; period: string; state: string; id: number | null },
  sessionId: number | null,
): string | null {
  if (doc.kind === 'PAY_SLIP' && doc.state === 'FINAL') {
    return sessionId === null
      ? `/v1/payslips/mine/${doc.period}/pdf`
      : `/v1/payslips/previews/${sessionId}/${doc.period}/pdf`;
  }
  if (doc.kind === 'FORM_16' && doc.state === 'ISSUED' && doc.id !== null && sessionId === null) {
    return `/v1/tax/form16-certificates/${doc.id}/part-b`;
  }
  return null;
}

/** The payslip PDF of a locked month: the member's own, or the support preview's. */
export function payslipPdfPath(yymm: string, sessionId: number | null): string {
  return sessionId === null
    ? `/v1/payslips/mine/${yymm}/pdf`
    : `/v1/payslips/previews/${sessionId}/${yymm}/pdf`;
}
