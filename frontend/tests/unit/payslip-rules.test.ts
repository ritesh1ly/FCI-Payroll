import { describe, expect, it } from 'vitest';
import { hasLines, periodLabel } from '@/modules/employee/business/payslipRules';

/** Payslip display rules (plan 16 phase 16e-3; SRS-17-7). */
describe('Payslip rules', () => {
  it('SRS-17-7: the period reads as month and year; anything else is shown as it is', () => {
    expect(periodLabel('202610', 'en')).toMatch(/Oct.*2026/);
    expect(periodLabel('202601', 'hi')).toMatch(/2026/);
    expect(periodLabel('2026-10', 'en')).toBe('2026-10');
    expect(periodLabel('2026101', 'en')).toBe('2026101');
    expect(periodLabel('x202610', 'en')).toBe('x202610');
    expect(periodLabel('202609', 'en')).toMatch(/^Sep\S* 2026$/);
    expect(periodLabel('202609', 'hi')).not.toBe(periodLabel('202609', 'en'));
  });

  it('SRS-17-7: a side without lines shows a dash', () => {
    expect(hasLines([])).toBe(false);
    expect(hasLines([1])).toBe(true);
  });
});
