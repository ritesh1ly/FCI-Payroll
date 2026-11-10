import type { OrderStatus, RevokeInput } from '../api/penaltyApi';

/**
 * The I-06 checks before a request is sent (plan 12; SRS-14-1, SRS-14-2, SRS-14-16, BR-PEN2-002);
 * the server checks again. Each problem is an i18n key.
 */

export const NATURES = ['RECOVERY', 'INCREMENT_STOP', 'BASIC_REDUCTION'] as const;

const PRINTABLE = /^[\x20-\x7E]*$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;

export function isYymm(s: string): boolean {
  return /^\d{4}(0[1-9]|1[0-2])$/.test(s);
}

/** Rupees and paise above zero, at most 12 rupee digits. */
export function isAmount(s: string): boolean {
  const [rupees, paise, ...rest] = s.trim().split('.');
  return (
    rest.length === 0 &&
    /^\d{1,12}$/.test(rupees ?? '') &&
    (paise === undefined || /^\d{1,2}$/.test(paise)) &&
    Number(s) > 0
  );
}

/** A required text of at most {@code max}; a text area's reason passes TEXT_LINES (line breaks kept). */
function text(v: string, max: number, chars: RegExp = PRINTABLE): boolean {
  const s = v.trim();
  return s !== '' && s.length <= max && chars.test(s);
}

/** The order form as typed. */
export interface OrderDraft {
  eis: string;
  orderReference: string;
  effectiveDate: string;
  amount: string;
  instalments: string;
  startYymm: string;
}

/** SRS-14-1, I-06 field table: what keeps an order from its preview. */
export function orderProblems(d: OrderDraft): string[] {
  const out: string[] = [];
  if (!/^[A-Za-z0-9-]{1,20}$/.test(d.eis.trim())) out.push('i06.bad.eis');
  if (!text(d.orderReference, 60)) out.push('i06.bad.reference');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d.effectiveDate)) out.push('i06.bad.effective');
  if (!isAmount(d.amount)) out.push('i06.bad.amount');
  const n = Number(d.instalments);
  if (!/^\d{1,3}$/.test(d.instalments) || n < 1) out.push('i06.bad.instalments');
  if (!isYymm(d.startYymm)) out.push('i06.bad.start');
  return out;
}

/** SRS-14-16: a full revoke has neither amount nor period; a partial one has exactly one. */
export function revokeProblems(
  r: Omit<RevokeInput, 'amount'> & { amount: string },
  orderAmount: number | null,
): string[] {
  const out: string[] = [];
  const byAmount = r.amount.trim() !== '';
  const byPeriod = (r.fromYymm ?? '') !== '' || (r.toYymm ?? '') !== '';
  if (r.type === 'FULL' && (byAmount || byPeriod)) out.push('i06.bad.fullScope');
  if (r.type === 'PARTIAL' && byAmount === byPeriod) out.push('i06.bad.partialScope');
  if (
    r.type === 'PARTIAL' &&
    byAmount &&
    (!isAmount(r.amount) || (orderAmount !== null && Number(r.amount) > orderAmount))
  )
    out.push('i06.bad.amount');
  if (
    r.type === 'PARTIAL' &&
    byPeriod &&
    (!isYymm(r.fromYymm ?? '') || !isYymm(r.toYymm ?? '') || (r.fromYymm ?? '') > (r.toYymm ?? ''))
  )
    out.push('i06.bad.period');
  if (!text(r.orderReference, 100)) out.push('i06.bad.reference');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(r.orderDate)) out.push('i06.bad.orderDate');
  if (!text(r.reason, 1000, TEXT_LINES)) out.push('i06.bad.reason');
  return out;
}

/** The payment form as typed. */
export interface PaymentDraft {
  eis: string;
  payCode: string;
  amount: string;
  payrollYymm: string;
  reason: string;
  applicationDate: string;
  sanctionReference: string;
  sanctionDate: string;
}

/** SRS-14-2, BR-PEN2-002: what keeps an over-and-above payment from being sent. */
export function paymentProblems(p: PaymentDraft): string[] {
  const out: string[] = [];
  if (!/^[A-Za-z0-9-]{1,20}$/.test(p.eis.trim())) out.push('i06.bad.eis');
  if (!/^\d{1,5}$/.test(p.payCode) || Number(p.payCode) < 1) out.push('i06.bad.payCode');
  if (!isAmount(p.amount)) out.push('i06.bad.amount');
  if (!isYymm(p.payrollYymm)) out.push('i06.bad.month');
  if (!text(p.reason, 500, TEXT_LINES)) out.push('i06.bad.reason');
  if (!text(p.sanctionReference, 60)) out.push('i06.bad.sanction');
  if (p.applicationDate !== '' && p.sanctionDate !== '' && p.sanctionDate <= p.applicationDate)
    out.push('i06.bad.sanctionDate');
  return out;
}

/** An order the Maker may still cancel: open, and keyed on I-06 (UI audit S1). */
export function changeable(status: OrderStatus, source?: 'ENT' | 'HRMS'): boolean {
  // R-SRC, SRS-14-1: an order applied from an HRMS penalty through I-21 is HRMS-owned; the server
  // refuses its cancellation (PEN-422-HRMS-ONLY), so the screen does not offer it.
  if (source === 'HRMS') return false;
  return revocable(status);
}

/** SRS-14-16, SRS-14-17: Penalty Revoke applies to an open order of any source, for all payrolls. */
export function revocable(status: OrderStatus): boolean {
  return status === 'ACTIVE' || status === 'PARTLY_REVOKED' || status === 'PAUSED_FNF';
}
