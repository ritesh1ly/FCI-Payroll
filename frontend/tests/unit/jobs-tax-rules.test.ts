import { describe, expect, it } from 'vitest';
import type { JobDef } from '@/modules/jobs/api/types';
import { finYearOf, finYears, validateParams } from '@/modules/jobs/business/jobRules';

/**
 * C-13 parameters of the Module 08 background jobs (SRS-NF-7), as the server's JobCatalog checks
 * them: a financial year (April to March, not after the current one) and a quarter.
 */

const partB: JobDef = {
  type: 'TAX_FORM16_PART_B',
  params: [
    { name: 'locationId', kind: 'LOCATION', required: true },
    { name: 'finYear', kind: 'FIN_YEAR', required: true },
  ],
};
const quarterly: JobDef = {
  type: 'TAX_24Q_RETURN',
  params: [...partB.params, { name: 'quarter', kind: 'QUARTER', required: true }],
};

describe('financial year and quarter parameters', () => {
  it('SRS_NF_7_theFinancialYearRunsAprilToMarch', () => {
    expect(finYearOf('2026-04')).toBe('2026-27');
    expect(finYearOf('2027-03')).toBe('2026-27');
    expect(finYearOf('2099-12')).toBe('2099-00');
    expect(finYears('2026-10', 3)).toEqual(['2026-27', '2025-26', '2024-25']);
    expect(finYears('2026-01')).toHaveLength(5);
  });

  it('SRS_NF_7_aYearAndAQuarterAreCheckedAsTheServerChecksThem', () => {
    const ok = { locationId: '3', finYear: '2025-26', quarter: 'Q4' };
    expect(validateParams(quarterly, ok, '2026-10')).toEqual({});
    expect(validateParams(quarterly, { ...ok, finYear: '2025-27' }, '2026-10')).toEqual({
      finYear: 'c13.error.finYear',
    });
    expect(validateParams(quarterly, { ...ok, finYear: '25-26' }, '2026-10').finYear).toBe(
      'c13.error.finYear',
    );
    expect(validateParams(quarterly, { ...ok, finYear: '2027-28' }, '2026-10').finYear).toBe(
      'c13.error.futureYear',
    );
    expect(validateParams(quarterly, { ...ok, finYear: '2026-27' }, '2026-10')).toEqual({});
    // Anchored: nothing before or after the year or the quarter.
    for (const finYear of ['x2025-26', '2025-26x']) {
      expect(validateParams(quarterly, { ...ok, finYear }, '2026-10').finYear).toBe('c13.error.finYear');
    }
    for (const quarter of ['xQ1', 'Q1x']) {
      expect(validateParams(quarterly, { ...ok, quarter }, '2026-10').quarter).toBe('c13.error.quarter');
    }
    expect(validateParams(quarterly, { ...ok, quarter: 'Q5' }, '2026-10')).toEqual({
      quarter: 'c13.error.quarter',
    });
    expect(validateParams(quarterly, { locationId: '3' }, '2026-10')).toEqual({
      finYear: 'c13.error.required',
      quarter: 'c13.error.required',
    });
  });
});
