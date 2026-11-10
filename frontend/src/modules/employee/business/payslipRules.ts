/** Payslip display rules (plan 16 phase 16e-3; SRS-17-7). */

/** YYYYMM as "Mon YYYY" in the user's language (the payslip's period). */
export function periodLabel(yymm: string, locale: string): string {
  if (!/^\d{6}$/.test(yymm)) return yymm;
  const date = new Date(Date.UTC(Number(yymm.slice(0, 4)), Number(yymm.slice(4)) - 1, 1));
  return new Intl.DateTimeFormat(locale === 'hi' ? 'hi-IN' : 'en-IN', {
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(date);
}

/** SRS-17-7: the staff payslip lists every line of its side; a side without lines shows a dash. */
export function hasLines(lines: readonly unknown[]): boolean {
  return lines.length > 0;
}
