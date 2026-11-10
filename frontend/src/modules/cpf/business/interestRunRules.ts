import { isAsciiText } from '@/modules/general';
import type { FreezeStatus, InterestRunSummary, NewRun } from '../api/cpfApi';
import { finYearRange } from './interestRateRules';

/**
 * The rules of a C-12 interest run as the screen checks them before sending (SRS-6-45, SRS-6-46,
 * SRS C-12 field table). They mirror the server's (`InterestRunRules`, `InterestRunController`); the
 * server checks again.
 */

/** The freeze status pill. */
export const FREEZE_PILL: Record<FreezeStatus, string> = {
  OPEN: 'p-ok',
  PENDING: 'p-warn',
  FROZEN: 'p-gray',
};

const EIS = /^[A-Za-z0-9]{1,20}$/;

/** The label keys of what stops a run request (empty: it may be sent). */
export function runProblems(draft: NewRun): string[] {
  const out: string[] = [];
  if (finYearRange(draft.finYear) === null) out.push('error.CPF-422-FIN-YEAR');
  const eis = draft.eis?.trim() ?? '';
  if (draft.scope === 'INDIVIDUAL' ? !EIS.test(eis) : eis !== '') out.push('error.CPF-422-RUN-SCOPE');
  if (!isAsciiText(draft.reason, 500)) out.push('c12.problem.reason');
  return out;
}

/** Why a run is disabled for the year shown, or null (SRS-6-46: nothing after the freeze). */
export function runBlock(summary: InterestRunSummary | undefined): string | null {
  return summary?.freezeStatus === 'FROZEN' ? 'error.CPF-409-INTEREST-FROZEN' : null;
}

/** Why the freeze request is disabled, or null: frozen already, or one request awaiting the AGM (CPF). */
export function freezeBlock(summary: InterestRunSummary | undefined): string | null {
  if (summary?.freezeStatus === 'PENDING') return 'c12.freezePending';
  return runBlock(summary);
}
