import type {
  Enablement,
  ExceptionItem,
  FallbackUpload,
  OffCycleCase,
  PayrollQuery,
  SupplementaryPayment,
} from '../api/generalApi';
import { businessToday } from '@/shared/time';

/**
 * Module 01 screen rules: what a form accepts and which action a status allows. They mirror the
 * backend so a user sees a disabled button with its reason instead of an error; the backend
 * enforces every rule again (never trust the screen).
 */

/** Staff number (EIS) as the API accepts it. */
export function isEis(value: string): boolean {
  return /^[A-Za-z0-9]{1,20}$/.test(value.trim());
}

/** A payroll month YYYYMM (2000-2099). */
export function isYymm(value: string): boolean {
  return /^20\d{2}(0[1-9]|1[0-2])$/.test(value);
}

/**
 * R-ASCII: the database holds printable ASCII only; Hindi or symbols are refused server side, so
 * the form says so before sending. Line breaks are allowed where {@code multiline}.
 */
export function isAsciiText(value: string, max: number, multiline = false): boolean {
  const v = value.trim();
  if (v.length === 0 || v.length > max) return false;
  for (const ch of v) {
    const c = ch.charCodeAt(0);
    const lineBreak = multiline && (c === 0x0a || c === 0x0d || c === 0x09);
    if (!lineBreak && (c < 0x20 || c > 0x7e)) return false;
  }
  return true;
}

/** The payroll month (YYYYMM) of an instant: the month of its business date in India. */
export function yymmOf(date: Date): string {
  return businessToday(date).slice(0, 7).replace('-', '');
}

/** The {@code count} months up to and including {@code from}, newest first (YYYYMM). */
export function monthsBack(from: string, count: number): string[] {
  let year = Number(from.slice(0, 4));
  let month = Number(from.slice(4, 6));
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    out.push(`${year}${String(month).padStart(2, '0')}`);
    month -= 1;
    if (month === 0) {
      month = 12;
      year -= 1;
    }
  }
  return out;
}

/** YYYYMM shown as MM-YYYY (the SRS field format). */
export function monthLabel(yymm: string | null | undefined): string {
  return yymm && isYymm(yymm) ? `${yymm.slice(4)}-${yymm.slice(0, 4)}` : '—';
}

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

/** YYYYMM as the screens show a payroll month, "JUN 2026" (a data value: not translated). */
export function monthName(yymm: string | null | undefined): string {
  return yymm && isYymm(yymm) ? `${MONTHS[Number(yymm.slice(4)) - 1]} ${yymm.slice(0, 4)}` : '—';
}

/** The month of a payroll month alone, as X-07 draws it ("JUL"). */
export function monthShort(yymm: string | null | undefined): string {
  return monthName(yymm).split(' ')[0] ?? '—';
}

/** Pill colour of a Module 01 status. */
export function tone(status: string): string {
  switch (status) {
    case 'APPROVED':
    case 'PASSED':
    case 'PASS':
    case 'ACCEPTED':
    case 'CLOSED':
    case 'RESOLVED':
    case 'RELEASED':
      return 'p-ok';
    case 'PENDING_CHECKER':
    case 'PENDING_APPROVAL':
    case 'SUBMITTED':
    case 'IN_PROGRESS':
    case 'OPEN':
    case 'UPLOADED':
      return 'p-warn';
    case 'BLOCKED':
    case 'REJECTED':
    case 'ROUTED_UPSTREAM':
      return 'p-bad';
    case 'RETURNED':
      return 'p-pur';
    case 'DRAFT':
    case 'VALIDATED':
      return 'p-info';
    default:
      return 'p-gray';
  }
}

// ---------------------------------------------------------------- X-06

/** The Maker answers an Open query (Open -> In Progress). */
export function canRespond(q: PayrollQuery): boolean {
  return q.status === 'OPEN';
}

/**
 * Resolution after the Maker's response. A computed-amount query is confirmed by the Payroll
 * Checker, who must not be the responder (chk_query_checker); any other by the Maker.
 */
export function resolveRole(q: PayrollQuery): 'CHECKER' | 'MAKER' | null {
  if (q.status !== 'IN_PROGRESS') return null;
  return q.concernsComputedAmount ? 'CHECKER' : 'MAKER';
}

// ---------------------------------------------------------------- X-05

/** SRS-G-9: only a non-blocking item holding gate G2 can be accepted instead of corrected. */
export function canAccept(e: ExceptionItem): boolean {
  return unsettled(e) && e.blocksGate === 'G2' && e.severity !== 'BLOCKING' && e.resolvableInPayroll;
}

/**
 * Still on the worklist as something open: OPEN, IN_PROGRESS, and ROUTED_UPSTREAM, which "stays open
 * until the corrected value arrives" (SRS X-05 alternate flow). The same set as the server
 * (ExceptionEntry.UNSETTLED) and gate G2, which counts a blocking item routed upstream.
 */
export function unsettled(e: ExceptionItem): boolean {
  return e.status === 'OPEN' || e.status === 'IN_PROGRESS' || e.status === 'ROUTED_UPSTREAM';
}

/**
 * The key of a condition (module + exception code) as the worklist opens it; the server groups and
 * counts the conditions (SRS l.634), the browser never groups one page of rows.
 */
export function conditionKey(c: { moduleCode: string | null; exceptionCode: string }): string {
  return `${c.moduleCode ?? ''}|${c.exceptionCode}`;
}

/** Owners an exception may be assigned to (SRS X-05 Owner list). */
export const EXCEPTION_OWNERS = [
  'PAYROLL_MAKER',
  'LABOUR_DEALING_HAND',
  'ESTABLISHMENT_HR',
  'RULE_ADMINISTRATOR',
  'SYSTEM_ADMINISTRATOR',
] as const;

// ---------------------------------------------------------------- X-07

/** A validated or returned upload takes an order copy and goes to the Checker. */
export function uploadEditable(u: FallbackUpload): boolean {
  return u.status === 'VALIDATED' || u.status === 'RETURNED';
}

/**
 * Why an upload cannot be submitted, as a message key, or null: nothing accepted, or fallback (b)
 * without its order copy.
 */
export function uploadSubmitBlock(u: FallbackUpload): string | null {
  if (!uploadEditable(u)) return 'x07.block.status';
  if (u.acceptedCount === 0) return 'x07.block.nothingAccepted';
  if (u.type === 'SUSPENSION_ABSCONDING' && u.orderCopies === 0) return 'x07.block.order';
  return null;
}

/**
 * The upload the "Upload" card shows when none is picked: the first awaiting the Checker (what the
 * screen is opened for), else the newest that is still in force, else the newest of all.
 */
export function defaultUpload(rows: FallbackUpload[]): FallbackUpload | null {
  return (
    rows.find((u) => u.status === 'PENDING_APPROVAL') ??
    rows.find((u) => u.status !== 'SUPERSEDED' && u.status !== 'CANCELLED') ??
    rows[0] ??
    null
  );
}

/**
 * The upload the X-07 order card reads (SRS-G-30, X-07 field table: only a fallback (b) upload holds an order): the
 * one the user opened; with none opened, the selected default when it is a suspension upload, else the newest live
 * suspension upload of the page, so the card shows a stored order rather than none.
 */
export function orderUpload(
  upload: FallbackUpload | null,
  opened: boolean,
  rows: FallbackUpload[],
): FallbackUpload | null {
  if (opened || upload?.type === 'SUSPENSION_ABSCONDING') return upload;
  return (
    rows.find(
      (u) => u.type === 'SUSPENSION_ABSCONDING' && u.status !== 'SUPERSEDED' && u.status !== 'CANCELLED',
    ) ?? upload
  );
}

/**
 * Why the Payroll Checker cannot decide this upload here, as a message key, or null: it must be
 * pending with its approval item, and the Checker differs from the Maker (SRS-G-21, SRS X-07 field
 * table; the backend refuses the same).
 */
export function approveBlock(u: FallbackUpload | null, loginId: string | undefined): string | null {
  if (u === null || u.status !== 'PENDING_APPROVAL' || u.approvalId === null) return 'x07.w.why.nonePending';
  return u.uploadedBy === loginId ? 'x07.w.why.ownUpload' : null;
}

/** The status of an enablement as drawn: message key, pill colour and whether it names the HRMS restore date. */
export function enablementPill(e: Enablement): { key: string; pill: string; restored: boolean } {
  const live = e.status === 'OPEN' || e.status === 'UPLOADED';
  if (live) {
    return e.type === 'SUSPENSION_ABSCONDING'
      ? { key: 'x07.w.enablement.INTERIM', pill: 'p-info', restored: false }
      : { key: 'x07.w.enablement.OPEN', pill: 'p-warn', restored: false };
  }
  if (e.status === 'APPROVED') return { key: 'x07.w.enablement.APPROVED', pill: 'p-ok', restored: false };
  if (e.status === 'RECONCILED') {
    return { key: 'x07.w.enablement.RECONCILED', pill: 'p-ok', restored: e.hrmsRestoredAt !== null };
  }
  return { key: `x07.enablement.${e.status}`, pill: 'p-gray', restored: false };
}

/** The unit a difference of this reconciliation item is told in. */
export function reconUnit(item: string): 'half' | 'day' | 'event' {
  if (item === 'HALF_PAY_DAYS') return 'half';
  return item.endsWith('_DAYS') ? 'day' : 'event';
}

/** X-07: Office codes as typed: comma or space separated, upper case, de-duplicated. */
export function officeCodes(text: string): string[] {
  return [...new Set(text.toUpperCase().match(/[^\s,]+/g) ?? [])];
}

// ---------------------------------------------------------------- I-20

/** Draft, returned (or withdrawn) cases take orders and go to the Checker. */
export function offCycleEditable(c: OffCycleCase): boolean {
  return c.status === 'DRAFT' || c.status === 'RETURNED';
}

export function offCycleSubmitBlock(c: OffCycleCase): string | null {
  if (c.status === 'BLOCKED') return 'i20.block.duplicate';
  if (!offCycleEditable(c)) return 'i20.block.status';
  if (c.orders === 0) return 'i20.block.order';
  return null;
}

/**
 * Invoice statuses after which a cancellation needs APPS's confirmation (SRS-G-37; the server's
 * chk_off_cycle_sent / chk_supp_cancel_apps sets).
 */
const INVOICE_SENT = ['SENT_TO_APPS', 'CANCEL_REQUESTED', 'CANCELLED_IN_APPS'];

/**
 * SRS-G-37 Cancel: never while the Checker decides, never twice; once the invoice is sent only
 * after APPS confirms the cancellation (status CANCELLED_IN_APPS), the same rule as the server.
 */
export function offCycleCancellable(c: OffCycleCase): boolean {
  return (
    c.status !== 'CANCELLED' &&
    c.status !== 'PENDING_CHECKER' &&
    (!INVOICE_SENT.includes(c.invoiceStatus) || c.invoiceStatus === 'CANCELLED_IN_APPS')
  );
}

// ---------------------------------------------------------------- I-23

/** The screen captures only a pending salary; other types come from their originating module. */
export const SCREEN_PAYMENT_TYPES = ['SALARY_WAGE'] as const;

export function supplementarySubmitBlock(p: SupplementaryPayment): string | null {
  if (p.status === 'BLOCKED') return 'i23.block.duplicate';
  if (p.status !== 'DRAFT' && p.status !== 'RETURNED') return 'i23.block.status';
  return null;
}

/** SRS-G-37: as the server, a sent invoice may be cancelled only once APPS confirms (appsCancelStatus CONFIRMED). */
export function supplementaryCancellable(p: SupplementaryPayment): boolean {
  return (
    p.status !== 'CANCELLED' &&
    p.status !== 'PENDING_CHECKER' &&
    p.status !== 'RELEASED' &&
    (!INVOICE_SENT.includes(p.invoiceStatus) || p.appsCancelStatus === 'CONFIRMED')
  );
}

/** chk_supp_sanction_after_appl: a sanction date, where given, is later than the application. */
export function sanctionAfterApplication(applied: string, sanctioned: string): boolean {
  return sanctioned === '' || (applied !== '' && sanctioned > applied);
}
