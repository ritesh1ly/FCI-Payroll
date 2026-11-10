/**
 * The business calendar: FCI's dates are India's. The server applies effective dates, payroll
 * months and "not in the past" checks on the date in Asia/Kolkata, so every screen takes "today"
 * from here, whatever the browser's time zone (a browser set to UTC would otherwise default a
 * proposal to yesterday between 00:00 and 05:30 IST, a date the server refuses as past).
 *
 * Pure functions with no React or I/O, so the business/ layer of every module may use them.
 */

/** The time zone of the business calendar. */
export const BUSINESS_TIME_ZONE = 'Asia/Kolkata';

/** Today's business date, YYYY-MM-DD (the en-CA calendar format is ISO 8601). */
export function businessToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_TIME_ZONE }).format(now);
}

/** This business month, YYYY-MM (the value of an `<input type="month">`), on India's date. */
export function businessMonth(now: Date = new Date()): string {
  return businessToday(now).slice(0, 7);
}

const YYMM = /^(\d{4})(0[1-9]|1[0-2])$/;
const ISO_MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;

/** A payroll month YYYYMM as a month picker value YYYY-MM; anything else is no month (''). */
export function yymmToMonth(yymm: string): string {
  const m = YYMM.exec(yymm.trim());
  return m ? `${m[1]}-${m[2]}` : '';
}

/** A month picker value YYYY-MM as the payroll month YYYYMM; anything else is no month (''). */
export function monthToYymm(month: string): string {
  const m = ISO_MONTH.exec(month.trim());
  return m ? `${m[1]}${m[2]}` : '';
}

/** The financial year (April to March) of a day YYYY-MM-DD or a month YYYY-MM: 2026-27. */
export function finYearOfDay(day: string): string {
  const year = Number(day.slice(0, 4));
  const start = Number(day.slice(5, 7)) >= 4 ? year : year - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/**
 * The financial years a year selector offers, newest first: the business year (India's date) and
 * the {@code count - 1} before it (the window the C-13 job form already offers, jobRules.finYears).
 */
export function recentFinYears(count = 5, now: Date = new Date()): string[] {
  const start = Number(finYearOfDay(businessToday(now)).slice(0, 4));
  return Array.from({ length: Math.max(count, 1) }, (_, i) => {
    const s = start - i;
    return `${s}-${String((s + 1) % 100).padStart(2, '0')}`;
  });
}

/** This business month as the payroll month YYYYMM (payroll_result.yymm), on India's date. */
export function businessYymm(now: Date = new Date()): string {
  return monthToYymm(businessMonth(now));
}
