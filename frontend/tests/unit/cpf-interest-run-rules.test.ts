import { describe, expect, it } from 'vitest';
import type { InterestRunSummary, NewRun } from '@/modules/cpf/api/cpfApi';
import { FREEZE_PILL, freezeBlock, runBlock, runProblems } from '@/modules/cpf/business/interestRunRules';

/** C-12 CPF interest run (SRS-6-45, SRS-6-46, SRS C-12 field table): the screen's own checks. */

function run(over: Partial<NewRun> = {}): NewRun {
  return { finYear: '2026-27', scope: 'ALL_MEMBERS', eis: null, reason: 'Year-end calculation', ...over };
}

function summary(freezeStatus: InterestRunSummary['freezeStatus']): InterestRunSummary {
  return {
    finYear: '2026-27',
    zone: 'NA11',
    members: 2,
    calculated: 0,
    notYetCalculated: 2,
    reRuns: 0,
    freezeStatus,
    frozenBy: null,
    frozenAt: null,
    runs: [],
  };
}

describe('C-12 interest run', () => {
  it('SRS_6_45_aRunNamesAValidYearItsScopeAndAReason', () => {
    expect(runProblems(run())).toEqual([]);
    expect(runProblems(run({ scope: 'INDIVIDUAL', eis: ' 104882 ' }))).toEqual([]);
    expect(runProblems(run({ eis: '' }))).toEqual([]);
    expect(runProblems(run({ finYear: '2026-28' }))).toEqual(['error.CPF-422-FIN-YEAR']);
    expect(runProblems(run({ scope: 'INDIVIDUAL', eis: null }))).toEqual(['error.CPF-422-RUN-SCOPE']);
    expect(runProblems(run({ scope: 'INDIVIDUAL', eis: '10-48' }))).toEqual(['error.CPF-422-RUN-SCOPE']);
    expect(runProblems(run({ eis: '104882' }))).toEqual(['error.CPF-422-RUN-SCOPE']);
    expect(runProblems(run({ reason: ' ' }))).toEqual(['c12.problem.reason']);
    expect(runProblems(run({ reason: 'x'.repeat(500) }))).toEqual([]);
    expect(runProblems(run({ reason: 'x'.repeat(501) }))).toEqual(['c12.problem.reason']);
    expect(runProblems(run({ reason: 'पुनः' }))).toEqual(['c12.problem.reason']);
  });

  it('SRS_6_46_nothingRunsAfterTheFreezeAndOneFreezeRequestAtATime', () => {
    expect(runBlock(undefined)).toBeNull();
    expect(runBlock(summary('OPEN'))).toBeNull();
    expect(runBlock(summary('PENDING'))).toBeNull();
    expect(runBlock(summary('FROZEN'))).toBe('error.CPF-409-INTEREST-FROZEN');
    expect(freezeBlock(undefined)).toBeNull();
    expect(freezeBlock(summary('OPEN'))).toBeNull();
    expect(freezeBlock(summary('PENDING'))).toBe('c12.freezePending');
    expect(freezeBlock(summary('FROZEN'))).toBe('error.CPF-409-INTEREST-FROZEN');
    expect(FREEZE_PILL).toEqual({ OPEN: 'p-ok', PENDING: 'p-warn', FROZEN: 'p-gray' });
  });
});
