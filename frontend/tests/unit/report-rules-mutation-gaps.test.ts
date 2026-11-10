import { describe, expect, it } from 'vitest';
import { DETAIL_KEY, exportBlockedWhy, reportChoices } from '@/modules/reporting/business/reportRules';

/**
 * Mutation-gap tests for reportRules.ts (SRS-18-8, SRS-18-28, OD-121). Equivalent mutants are listed
 * in docs/tracker/PENDING.md under COV-UNREACH (`whole = ''` default, the trims inside Number() and
 * DATA_TYPE, the `fields ?? []` fallback).
 */
describe('reportRules mutation gaps', () => {
  it('SRS_18_28_theReportSelectNeverOffersAnOutOfScopeReport', () => {
    const entries = [
      { key: 'COMPONENT_WISE_SUMMARY' },
      { key: 'PENSION_SLIP' },
      { key: 'PRMS_MEDICAL' },
      { key: '' },
      { key: 'CPF_OBCB' },
    ];
    expect(reportChoices(entries)).toEqual([
      { key: 'COMPONENT_WISE_SUMMARY' },
      { key: '' },
      { key: 'CPF_OBCB' },
    ]);
    expect(reportChoices([])).toEqual([]);
  });

  it('OD_121_theDefinitionDetailCardDrawsTheLabourIncentiveDetail', () => {
    expect(DETAIL_KEY).toBe('LABOUR_INCENTIVE_DETAIL');
  });

  it('SRS_18_8_beforeGenerateThereIsNothingToWaitFor', () => {
    const pending = (fetchStatus?: string) => ({ isPending: true, isError: false, fetchStatus });
    expect(exportBlockedWhy(true, pending('idle'))).toBe('o04.why.generate');
    expect(exportBlockedWhy(true, pending('fetching'))).toBe('o04.why.loading');
    expect(exportBlockedWhy(true, pending())).toBe('o04.why.loading');
    expect(exportBlockedWhy(true, { isPending: false, isError: false, fetchStatus: 'idle' })).toBe(
      'o04.why.mismatch',
    );
  });
});
