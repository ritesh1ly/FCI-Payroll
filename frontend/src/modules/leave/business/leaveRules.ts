import type { LeaveParameterVersion } from '../api/leaveApi';

/**
 * Leave screen rules (plan 19; SRS-LV-14, SRS M-20 field table). The server checks every rule again;
 * these only shape the form and disable what cannot be sent.
 */

/** How an M-20 parameter's value is held (backend LeaveParameterRules.Kind). */
export type ParameterKind = 'POSITIVE' | 'DAYS' | 'DIVISOR' | 'PAY_CODES' | 'PAY_EFFECT';

/**
 * The M-20 parameters and their kinds (SRS field table; D19-2). The components subject to LWP are an
 * M-07 attribute (SRS-LV-15, BR-LEAVE-007; US-GAP-298), not an M-20 parameter.
 */
export const LEAVE_PARAMETERS: Readonly<Record<string, ParameterKind>> = {
  LWP_DIVISOR: 'DIVISOR',
  ENCASH_DIVISOR: 'POSITIVE',
  ENCASH_PAY_ELEMENTS: 'PAY_CODES',
  INSERVICE_PER_OCCASION_LIMIT: 'DAYS',
  INSERVICE_PER_YEAR_LIMIT: 'DAYS',
  SEPARATION_AGGREGATE_CEILING: 'DAYS',
  ENCASH_PAY_CODES: 'PAY_CODES',
  PAY_EFFECT: 'PAY_EFFECT',
};

/** The parameters M-20 draws, in the pack's order (SRS M-20 table l.4174-4180). */
export const DRAWN_PARAMETERS = [
  'LWP_DIVISOR',
  'ENCASH_DIVISOR',
  'ENCASH_PAY_ELEMENTS',
  'INSERVICE_PER_OCCASION_LIMIT',
  'INSERVICE_PER_YEAR_LIMIT',
  'SEPARATION_AGGREGATE_CEILING',
  'ENCASH_PAY_CODES',
] as const;

/** One drawn parameter: the version in force and the proposal waiting, either may be absent. */
export interface ParameterRow {
  code: string;
  current: LeaveParameterVersion | null;
  pending: LeaveParameterVersion | null;
}

/** The versions of a parameter across every type are read for the code; the common one (no type) shows. */
export function drawnParameters(versions: LeaveParameterVersion[]): ParameterRow[] {
  return DRAWN_PARAMETERS.map((code) => {
    const own = versions.filter((v) => v.code === code && v.leaveType === null);
    return {
      code,
      current: own.find((v) => v.status === 'ACTIVE') ?? null,
      pending: own.find((v) => v.status === 'PENDING_APPROVAL') ?? null,
    };
  });
}

/** The DDL wage effects a leave type may be given (HELD_UNDEFINED is never a configured value). */
export const PAY_EFFECTS = [
  'NO_EFFECT',
  'FULL_DEDUCTION',
  'HALF_PAY',
  'INCREMENT_DELAY',
  'ENCASHMENT_TRIGGER',
  'ALLOWANCE_RECOVERY',
] as const;

/** The I-11 statuses (SRS I-11 field table). */
export const LEAVE_EVENT_STATUSES = ['PENDING', 'APPLIED', 'BACK_DATED', 'REJECTED'] as const;

const PAY_CODE = /^\d{1,4}$/;
const REASON = /^[\x20-\x7E\t\r\n]{1,500}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Pay codes of M-07 separated by "," or "+": one to twenty codes of up to four digits. */
export function payCodesOk(value: string): boolean {
  const codes = value.split(/[,+]/);
  return codes.length <= 20 && codes.every((c) => PAY_CODE.test(c));
}

/** The parameters that may be set for one leave type (the limits and the pay effect). */
export function perType(kind: ParameterKind): boolean {
  return kind === 'DAYS' || kind === 'PAY_EFFECT';
}

/** Whether the value takes a number (else a text value). */
export function numeric(kind: ParameterKind, text: string): boolean {
  return kind === 'POSITIVE' || kind === 'DAYS' || (kind === 'DIVISOR' && text !== 'CALENDAR_DAYS');
}

export interface ParameterDraft {
  code: string;
  leaveType: string;
  value: string;
  effectiveFrom: string;
  reason: string;
}

/** Why the draft cannot be sent, or null (the server is the authority). */
export function draftProblem(d: ParameterDraft): string | null {
  const kind = LEAVE_PARAMETERS[d.code];
  if (!kind) return 'm20.problem.parameter';
  if (kind === 'PAY_EFFECT' && !d.leaveType) return 'm20.problem.leaveType';
  if (!DATE.test(d.effectiveFrom)) return 'm20.problem.date';
  if (!REASON.test(d.reason.trim())) return 'm20.problem.reason';
  const v = d.value.trim();
  switch (kind) {
    case 'POSITIVE':
      return Number(v) > 0 ? null : 'm20.problem.value';
    case 'DAYS':
      return v !== '' && Number(v) >= 0 ? null : 'm20.problem.value';
    case 'DIVISOR':
      return v === 'CALENDAR_DAYS' || Number(v) > 0 ? null : 'm20.problem.value';
    case 'PAY_CODES':
      return payCodesOk(v) ? null : 'm20.problem.value';
    case 'PAY_EFFECT':
      return (PAY_EFFECTS as readonly string[]).includes(v) ? null : 'm20.problem.value';
  }
}

/** SRS I-11 "Applied in" (US-GAP-59): a YYYY-MM cycle shown as MM-YYYY; a dash until applied. */
export function appliedInLabel(yyyyMm: string | null): string {
  const m = yyyyMm ? /^(\d{4})-(\d{2})$/.exec(yyyyMm) : null;
  return m ? `${m[2]}-${m[1]}` : '—';
}
