import { describe, expect, it } from 'vitest';
import type { Balances, NewCorrection } from '@/modules/cpf/api/cpfApi';
import { changes } from '@/modules/cpf/business/openingBalanceRules';
import { sanitize } from '@/shared/log/mask';
import { periodLabel } from '@/shared/session';

/**
 * Small rule edges: a short list is logged whole (R-PII), a period whose month is out of range is
 * shown as given, and a current balance that cannot be read counts as zero when a part is
 * corrected (SRS-6-43).
 */
describe('small rule edges', () => {
  it('R_PII_aListOfTenOrFewerIsLoggedWhole', () => {
    expect(sanitize([1, 2, 3])).toEqual([1, 2, 3]);
  });

  it('aPeriodWithAMonthOutOfRangeIsShownAsGiven', () => {
    expect(periodLabel('2026-13')).toBe('2026-13');
    expect(periodLabel('2026-07')).toBe('JUL 2026');
  });

  it('SRS_6_43_anUnreadableCurrentBalanceCountsAsZero', () => {
    const current: Balances = { employee: Number.NaN, employer: 0, vpf: 0 };
    const draft = { employee: '100', employer: null, vpf: null } as unknown as NewCorrection;
    expect(changes(current, draft)).toEqual([
      { part: 'EMPLOYEE', old: 0n, corrected: 10000n, difference: 10000n },
    ]);
  });
});
