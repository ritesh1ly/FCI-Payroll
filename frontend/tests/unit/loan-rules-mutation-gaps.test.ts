import { describe, expect, it } from 'vitest';
import { isYymm } from '@/modules/loans/business/loanRules';

/**
 * Mutation-gap tests for loanRules.ts (SRS-12-1, SRS-12-21).
 * Equivalent mutants, not tested: L53, L54, L57, L85, L90 (ConditionalExpression, trim and
 * StringLiteral on the "blank or zero" checks): Number('') and Number('   ') are both 0, so a
 * blank text fails the numeric check with or without the trim guard.
 */
describe('loanRules mutation gaps', () => {
  it('SRS-12-21: a month is exactly YYYYMM, anchored at both ends', () => {
    expect(isYymm('202607')).toBe(true);
    expect(isYymm('12202607')).toBe(false);
    expect(isYymm('2026071')).toBe(false);
    expect(isYymm('x202607')).toBe(false);
    expect(isYymm('202607x')).toBe(false);
    expect(isYymm('202613')).toBe(false);
  });
});
