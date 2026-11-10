import { describe, expect, it } from 'vitest';
import { blankPeriod, defaultFinYear, monthsOf, move } from '@/modules/jobs/business/calendarRules';

/**
 * M-10 helpers (plan 05 phase 05f; SRS-15-33, SRS-15-1): the twelve months of a financial year,
 * a blank period of a calendar month, and a run-order step moved one place.
 */
describe('M-10 calendar rules', () => {
  it('SRS-15-33: a financial year is April to March; a malformed year has no months', () => {
    expect(monthsOf('2026-27')).toEqual([
      '2026-04',
      '2026-05',
      '2026-06',
      '2026-07',
      '2026-08',
      '2026-09',
      '2026-10',
      '2026-11',
      '2026-12',
      '2027-01',
      '2027-02',
      '2027-03',
    ]);
    expect(monthsOf('2026-2')).toEqual([]);
    expect(monthsOf('2026-277')).toEqual([]);
    expect(monthsOf('20x6-27')).toEqual([]);
    expect(monthsOf('')).toEqual([]);
  });

  it('SRS-15-33: a blank period runs from the first to the last day of its month, the rest empty', () => {
    expect(blankPeriod('2027-02')).toEqual({
      month: '2027-02',
      start: '2027-02-01',
      end: '2027-02-28',
      freezeTarget: '',
      computeBy: null,
      verifyBy: null,
      lockTarget: '',
      disbursement: null,
      supplementaryFrom: '',
      supplementaryTo: '',
      mdpFrom: '',
      mdpTo: '',
    });
    expect(blankPeriod('2028-02').end).toBe('2028-02-29');
    expect(blankPeriod('2027-04').end).toBe('2027-04-30');
    expect(blankPeriod('2027-12').end).toBe('2027-12-31');
  });

  it('SRS-15-1: a step moves one place up or down; at an end or out of range the order stays', () => {
    const steps = ['A', 'B', 'C'] as const;
    expect(move(steps, 1, -1)).toEqual(['B', 'A', 'C']);
    expect(move(steps, 1, 1)).toEqual(['A', 'C', 'B']);
    expect(move(steps, 0, 1)).toEqual(['B', 'A', 'C']);
    expect(move(steps, 2, -1)).toEqual(['A', 'C', 'B']);
    expect(move(steps, 0, -1)).toEqual(['A', 'B', 'C']);
    expect(move(steps, 2, 1)).toEqual(['A', 'B', 'C']);
    expect(move(steps, -1, 1)).toEqual(['A', 'B', 'C']);
    expect(move(steps, 3, -1)).toEqual(['A', 'B', 'C']);
    const copy = move(steps, 5, 1);
    expect(copy).not.toBe(steps);
  });
});

describe('M-10 default year (UI audit S4)', () => {
  const offered = ['2027-28', '2026-27', '2025-26'];
  it('opens on the current financial year of the IST date, the boundaries included', () => {
    expect(defaultFinYear(offered, '2026-11-02')).toBe('2026-27');
    expect(defaultFinYear(offered, '2026-04-01')).toBe('2026-27');
    expect(defaultFinYear(offered, '2026-03-31')).toBe('2025-26');
    expect(defaultFinYear(offered, '2027-03-31')).toBe('2026-27');
  });
  it('falls back to the newest offered year, and to none for an empty list', () => {
    expect(defaultFinYear(['2030-31'], '2026-11-02')).toBe('2030-31');
    expect(defaultFinYear([], '2026-11-02')).toBeUndefined();
  });
});
