import type { FundType, InterestRate, NewRate, RateSource } from '../api/cpfApi';

/**
 * The rules of an M-19 PF interest rate as the screen checks them before submission (SRS-6-11,
 * BR-CPF-033, SRS M-19 field table). They mirror the server's (`InterestRateRules`), on the same
 * trimmed values; the server checks again.
 */

export const RATE_SOURCES: readonly RateSource[] = ['FCI_TRUST', 'RPFC'];
export const FUNDS: readonly FundType[] = ['CPF', 'PF', 'VPF', 'GPF'];

/** The status pill of an approved rate. */
export const RATE_STATUS_PILL: Record<InterestRate['status'], string> = {
  ACTIVE: 'p-ok',
  SUPERSEDED: 'p-gray',
};

/** A percentage from 0 to 100 with at most two decimals (M-19 Rate %, Numeric 5,2). */
const PERCENT = /^(?:\d{1,3}|\d{1,3}\.\d{1,2})$/;
const FIN_YEAR = /^(\d{4})-(\d{2})$/;

/** The first and last day (ISO) of a financial year written YYYY-YY, or null if it is not one. */
export function finYearRange(code: string): { start: string; end: string } | null {
  const m = FIN_YEAR.exec(code.trim());
  if (!m) return null;
  const first = Number(m[1]);
  if (Number(m[2]) !== (first + 1) % 100) return null;
  return { start: `${first}-04-01`, end: `${first + 1}-03-31` };
}

/** The financial year (April to March) a business date (ISO) falls in. */
export function finYearOf(day: string): string {
  const year = Number(day.slice(0, 4));
  const first = day.slice(5) >= '04-01' ? year : year - 1;
  return `${first}-${String((first + 1) % 100).padStart(2, '0')}`;
}

export function validPercent(text: string): boolean {
  const v = text.trim();
  return PERCENT.test(v) && Number(v) <= 100;
}

/** What stops a new rate from being submitted, as message keys (empty when it may be submitted). */
export function rateProblems(draft: NewRate): string[] {
  const problems: string[] = [];
  const year = finYearRange(draft.finYear);
  if (!year) problems.push('error.CPF-422-FIN-YEAR');
  if (!validPercent(draft.ratePercent)) problems.push('error.CPF-422-RATE');
  const from = draft.appliesFrom;
  const to = draft.appliesTo;
  if (
    year &&
    (!from || from < year.start || from > year.end || (to !== null && (to < from || to > year.end)))
  ) {
    problems.push('error.CPF-422-PERIOD');
  }
  if (draft.bonusInterestPct !== null) {
    if (draft.rateSource !== 'FCI_TRUST') problems.push('error.CPF-422-BONUS');
    else if (!validPercent(draft.bonusInterestPct)) problems.push('m19.bonusInvalid');
  }
  if (!draft.reason.trim()) problems.push('m19.reasonRequired');
  return problems;
}

/** A rate the Rule Administrator may still declare: approved, not declared, no change pending on its series. */
export function mayDeclare(rate: InterestRate, pendingSeries: ReadonlySet<string>): boolean {
  return !rate.declared && !pendingSeries.has(seriesKey(rate));
}

/** The series one rate belongs to: financial year, fund and source (the server's approval key). */
export function seriesKey(r: { finYear: string; fundType: string; rateSource: string }): string {
  return `${r.finYear}|${r.fundType}|${r.rateSource}`;
}
