import type { CalendarPeriod } from '../api/calendarApi';

/**
 * M-10 pure helpers (plan 05 phase 05f). The server checks the calendar (CalendarRules); these only
 * lay out the screen: the twelve months of a financial year and a blank period for a missing one.
 */

/** The months of financial year "2026-27": April 2026 ... March 2027, as YYYY-MM. */
export function monthsOf(finYear: string): string[] {
  const start = Number(finYear.slice(0, 4));
  if (!Number.isInteger(start) || finYear.length !== 7) return [];
  return Array.from({ length: 12 }, (_, i) => {
    const index = start * 12 + 3 + i;
    return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
  });
}

/**
 * UI audit S4: the year M-10 opens on - the current financial year of India's business date when
 * the server offers it (it also offers next year, empty until set up), else the newest offered.
 */
export function defaultFinYear(years: readonly string[], today: string): string | undefined {
  const year = Number(today.slice(0, 4));
  const start = Number(today.slice(5, 7)) >= 4 ? year : year - 1;
  const current = `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
  return years.includes(current) ? current : years[0];
}

/** A period with only its calendar month's first and last day, for the Administrator to complete. */
export function blankPeriod(month: string): CalendarPeriod {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return {
    month,
    start: `${month}-01`,
    end: `${month}-${String(last).padStart(2, '0')}`,
    freezeTarget: '',
    computeBy: null,
    verifyBy: null,
    lockTarget: '',
    disbursement: null,
    supplementaryFrom: '',
    supplementaryTo: '',
    mdpFrom: '',
    mdpTo: '',
  };
}

/** Moves the step at {@code index} one place up (-1) or down (+1); out of range leaves the order. */
export function move<T>(items: readonly T[], index: number, by: -1 | 1): T[] {
  const to = index + by;
  if (index < 0 || index >= items.length || to < 0 || to >= items.length) return [...items];
  const next = [...items];
  [next[index], next[to]] = [next[to] as T, next[index] as T];
  return next;
}
