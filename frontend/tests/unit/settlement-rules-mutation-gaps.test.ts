import { describe, expect, it } from 'vitest';
import type { CaseDetail } from '@/modules/settlement/api/settlementApi';
import { awaitsNoDues, STAGES, stageNumber } from '@/modules/settlement/business/settlementRules';

/** Mutation-gap tests for settlementRules.ts (SRS-10-8, SRS-10-14). */
describe('settlementRules mutation gaps', () => {
  it('SRS-10-8: the eight UC-06 stages keep their names and numbers', () => {
    expect(STAGES).toEqual([
      'SEPARATION_RECEIVED',
      'CLEARANCES_RAISED',
      'CLEARANCES_RECEIVED',
      'COMPUTED',
      'CPF_LINKED',
      'APPROVED',
      'POSTED',
      'CLOSED',
    ]);
    expect(stageNumber('CLEARANCES_RAISED')).toBe(2);
    expect(stageNumber('CPF_LINKED')).toBe(5);
    expect(stageNumber('APPROVED')).toBe(6);
    expect(stageNumber('POSTED')).toBe(7);
  });

  it('SRS-10-14: the no-dues is awaited if any one clearance is outstanding', () => {
    const detail = (clearances: Array<{ type: string; status: string }>) =>
      ({ clearances }) as unknown as CaseDetail;
    expect(
      awaitsNoDues(
        detail([
          { type: 'ESTABLISHMENT_NO_DUES', status: 'PENDING' },
          { type: 'VIGILANCE', status: 'RECEIVED' },
        ]),
      ),
    ).toBe(true);
    expect(awaitsNoDues(detail([{ type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED' }]))).toBe(false);
  });
});
