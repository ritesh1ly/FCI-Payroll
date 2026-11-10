import { describe, expect, it } from 'vitest';
import type { CaseRequest, VersionRequest } from '@/modules/increment/api/incrementApi';
import { badStageRow, caseReady, versionReady } from '@/modules/increment/business/incrementRules';

/**
 * Mutation-gap tests for incrementRules.ts (SRS-13-18, SRS-13-24, M-03).
 * Equivalent mutants, not tested: L55 StringLiteral ('' after filled() already proved the
 * reference present); L62 Regex and L63 trim (the empty parts are filtered out right after);
 * L82 trim inside Number(...) (Number() trims whitespace itself); L97 ArrayDeclaration (stages
 * are proved non-empty on L96); L109 x3 (the ASCII regex on L111 needs at least one character).
 */
describe('incrementRules mutation gaps', () => {
  const base: Partial<CaseRequest> = {
    eis: '1000123',
    caseType: 'PROMOTION_FIXATION',
    effectiveDate: '2026-07-01',
    orderNumber: 'ORD/26/77',
    orderDate: '2026-06-20',
    issuingAuthority: 'Manager (Pay)',
    reason: 'Promotion',
  };

  it('SRS-13-18: a case that enters the Basic is not ready without a positive Basic', () => {
    const revision: Partial<CaseRequest> = { ...base, caseType: 'INDIVIDUAL_REVISION' };
    expect(caseReady(revision)).toBe(false);
    expect(caseReady({ ...revision, basic: 0 })).toBe(false);
    expect(caseReady({ ...revision, basic: 61200 })).toBe(true);
    expect(caseReady(base)).toBe(true);
  });

  it('M-03: a row of spaces in the stage grid is an empty row, not a bad row', () => {
    expect(badStageRow(['60000', '   ', '62400'])).toBeNull();
    expect(badStageRow(['60000', ' x ', '62400'])).toBe(2);
  });

  describe('versionReady bounds', () => {
    const ok = {
      min: 60000,
      max: 90000,
      effectiveFrom: '2026-01-01',
      circularId: 7,
      wageRevision: 'WR-2026',
      stages: [60000, 62400],
      reason: 'Revision',
    } as unknown as Partial<VersionRequest>;
    const bad = (over: Record<string, unknown>) =>
      versionReady({ ...ok, ...over } as Partial<VersionRequest>);

    it('M-03: the bounds must both be numbers', () => {
      expect(versionReady(ok)).toBe(true);
      expect(bad({ min: '60000' })).toBe(false);
      expect(bad({ max: '90000' })).toBe(false);
      expect(bad({ min: '60000', max: '90000' })).toBe(false);
      expect(bad({ min: undefined })).toBe(false);
      expect(bad({ max: undefined })).toBe(false);
    });

    it('M-03: the minimum must be strictly below the maximum', () => {
      expect(bad({ min: 60000, max: 60000, stages: [60000] })).toBe(false);
      expect(bad({ min: 90000, max: 60000, stages: [60000] })).toBe(false);
      expect(bad({ min: 60000, max: 60001, stages: [60000] })).toBe(true);
    });
  });
});
