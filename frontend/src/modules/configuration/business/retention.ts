/** SRS-20-5 retention period rules on A-07 (pure functions, mirrored by the server). */

/** The largest period retention_months NUMBER(5) holds (a column limit, not a policy). */
export const MAX_RETENTION_MONTHS = 99_999;

/** Printable ASCII only (R-ASCII: the database character set is US7ASCII). */
export function isPrintableAscii(value: string): boolean {
  return [...value].every((c) => {
    const code = c.charCodeAt(0);
    return code >= 0x20 && code <= 0x7e;
  });
}

/**
 * A proposal the server will take: a whole number of months (1 to the column limit) or indefinite
 * (not both, not neither), the instrument that sets it (printable ASCII, at most 200) and a reason.
 */
export function validRetention(input: {
  months: string;
  indefinite: boolean;
  basis: string;
  reason: string;
}): boolean {
  const months = input.months.trim();
  const hasMonths = months !== '';
  if (hasMonths === input.indefinite) return false;
  if (hasMonths) {
    if (!/^\d+$/.test(months)) return false;
    const n = Number(months);
    if (n < 1 || n > MAX_RETENTION_MONTHS) return false;
  }
  const basis = input.basis.trim();
  return basis.length > 0 && basis.length <= 200 && isPrintableAscii(basis) && input.reason.trim().length > 0;
}
