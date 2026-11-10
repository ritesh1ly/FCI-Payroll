import { describe, expect, it } from 'vitest';
import type { InterestRate, NewRate } from '@/modules/cpf/api/cpfApi';
import {
  FUNDS,
  RATE_SOURCES,
  RATE_STATUS_PILL,
  finYearOf,
  finYearRange,
  mayDeclare,
  rateProblems,
  seriesKey,
  validPercent,
} from '@/modules/cpf/business/interestRateRules';

/** M-19 PF interest rates (SRS-6-11, BR-CPF-033, SRS M-19 field table): the screen's own checks. */

const draft = (over: Partial<NewRate> = {}): NewRate => ({
  finYear: '2026-27',
  fundType: 'CPF',
  rateSource: 'FCI_TRUST',
  ratePercent: '8.25',
  appliesFrom: '2026-04-01',
  appliesTo: null,
  applicabilityNote: null,
  bonusInterestPct: null,
  declared: true,
  reason: 'Board resolution',
  ...over,
});

const rate = (over: Partial<InterestRate> = {}): InterestRate => ({
  id: 1,
  finYear: '2026-27',
  fundType: 'CPF',
  rateSource: 'RPFC',
  memberPopulation: 'INACTIVE',
  ratePercent: 8.25,
  appliesFrom: '2026-04-01',
  appliesTo: null,
  applicabilityNote: null,
  bonusInterestPct: null,
  declared: false,
  status: 'ACTIVE',
  approvalId: 3,
  ...over,
});

describe('M-19 rules', () => {
  it('BR-CPF-033: a financial year YYYY-YY runs from 1 April to 31 March, also across the century', () => {
    expect(finYearRange('2026-27')).toEqual({ start: '2026-04-01', end: '2027-03-31' });
    expect(finYearRange(' 2026-27 ')).toEqual({ start: '2026-04-01', end: '2027-03-31' });
    expect(finYearRange('2099-00')).toEqual({ start: '2099-04-01', end: '2100-03-31' });
    for (const bad of ['2026-28', '2026-26', '2026/27', '26-27', '2026-2027', '', 'x2026-27', '2026-27x']) {
      expect(finYearRange(bad)).toBeNull();
    }
  });

  it('BR-CPF-033: the financial year of a day changes on 1 April', () => {
    expect(finYearOf('2026-03-31')).toBe('2025-26');
    expect(finYearOf('2026-04-01')).toBe('2026-27');
    expect(finYearOf('2026-12-31')).toBe('2026-27');
    expect(finYearOf('2099-05-01')).toBe('2099-00');
    expect(finYearOf('2100-01-15')).toBe('2099-00');
  });

  it('SRS-6-11: a rate is a percentage from 0 to 100 with at most two decimals', () => {
    for (const ok of ['0', '9.5', '9.50', '100', '100.00', ' 8.65 ', '0.01']) {
      expect(validPercent(ok)).toBe(true);
    }
    for (const bad of ['', '100.01', '101', '9.505', '-1', '1,5', '.5', '9.', 'abc', '1000']) {
      expect(validPercent(bad)).toBe(false);
    }
  });

  it('SRS-6-11: a complete rate inside its year has no problem', () => {
    expect(rateProblems(draft())).toEqual([]);
    expect(rateProblems(draft({ appliesFrom: '2027-03-31', appliesTo: '2027-03-31' }))).toEqual([]);
    expect(rateProblems(draft({ bonusInterestPct: '2.50' }))).toEqual([]);
  });

  it('BR-CPF-033: each problem is named: year, rate, period, bonus and reason', () => {
    expect(rateProblems(draft({ finYear: '2026-28' }))).toEqual(['error.CPF-422-FIN-YEAR']);
    expect(rateProblems(draft({ ratePercent: '100.01' }))).toEqual(['error.CPF-422-RATE']);
    expect(rateProblems(draft({ appliesFrom: '' }))).toEqual(['error.CPF-422-PERIOD']);
    expect(rateProblems(draft({ appliesFrom: '2026-03-31' }))).toEqual(['error.CPF-422-PERIOD']);
    expect(rateProblems(draft({ appliesFrom: '2027-04-01' }))).toEqual(['error.CPF-422-PERIOD']);
    expect(rateProblems(draft({ appliesFrom: '2026-06-01', appliesTo: '2026-05-31' }))).toEqual([
      'error.CPF-422-PERIOD',
    ]);
    expect(rateProblems(draft({ appliesTo: '2027-04-01' }))).toEqual(['error.CPF-422-PERIOD']);
    expect(rateProblems(draft({ rateSource: 'RPFC', bonusInterestPct: '2.5' }))).toEqual([
      'error.CPF-422-BONUS',
    ]);
    expect(rateProblems(draft({ bonusInterestPct: '101' }))).toEqual(['m19.bonusInvalid']);
    expect(rateProblems(draft({ reason: '   ' }))).toEqual(['m19.reasonRequired']);
    // Without a valid year the period cannot be judged: only the year is named.
    expect(rateProblems(draft({ finYear: 'x', appliesFrom: '' }))).toEqual(['error.CPF-422-FIN-YEAR']);
  });

  it('SRS-6-11: only an undeclared rate with no change pending on its series may be declared', () => {
    expect(mayDeclare(rate(), new Set())).toBe(true);
    expect(mayDeclare(rate({ declared: true }), new Set())).toBe(false);
    expect(mayDeclare(rate(), new Set(['2026-27|CPF|RPFC']))).toBe(false);
    expect(mayDeclare(rate(), new Set(['2026-27|CPF|FCI_TRUST']))).toBe(true);
  });

  it('BR-CPF-033: a series is one year, fund and source; the lists and pills follow the DDL', () => {
    expect(seriesKey(rate())).toBe('2026-27|CPF|RPFC');
    expect(FUNDS).toEqual(['CPF', 'PF', 'VPF', 'GPF']);
    expect(RATE_SOURCES).toEqual(['FCI_TRUST', 'RPFC']);
    expect(RATE_STATUS_PILL).toEqual({ ACTIVE: 'p-ok', SUPERSEDED: 'p-gray' });
  });
});
