/**
 * M-01 allowance flag screen rules (plan 16 phase 16d-2; SRS-1-11, BR-EMPM-005 / 006). The server
 * decides; these only say whether to offer the send.
 */

const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;

/**
 * BR-EMPM-005 / 006: a proposal names the flag, Y or N, a date not before the joining date and a
 * reason of 1 to 500 plain characters.
 */
export function proposalComplete(p: {
  flagCode: string;
  value: string;
  effectiveFrom: string;
  reason: string;
  joiningDate: string | null;
}): boolean {
  if (p.flagCode === '' || (p.value !== 'Y' && p.value !== 'N') || p.effectiveFrom === '') return false;
  if (p.joiningDate !== null && p.effectiveFrom < p.joiningDate) return false;
  const reason = p.reason.trim();
  return reason !== '' && reason.length <= 500 && PLAIN_TEXT.test(p.reason);
}

/** SRS-1-11: a flag with a change already waiting for the Checker is not proposed again. */
export function proposable(pending: boolean): boolean {
  return !pending;
}

/**
 * US-GAP-39 (M-01 Payroll Flags row, BR-EMPM-004): an allowance flag may carry an end date; when
 * given it is not before the effective date.
 */
export function endDateValid(effectiveFrom: string, effectiveTo: string): boolean {
  return effectiveTo === '' || (effectiveFrom !== '' && effectiveTo >= effectiveFrom);
}

/**
 * SRS-1-7 / FRS §7.8.1 Scenario 4 (Hold, Stop-Payment): as {@link proposalComplete}, and the date is
 * not later than today: the flag has no date of its own, so a later date waits for FCI (CLR-17).
 */
export function controlProposalComplete(p: {
  flagCode: string;
  value: string;
  effectiveFrom: string;
  reason: string;
  joiningDate: string | null;
  today: string;
}): boolean {
  return proposalComplete(p) && p.effectiveFrom <= p.today;
}
