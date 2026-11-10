import { describe, expect, it } from 'vitest';
import { stateProblems } from '@/modules/tax/business/taxConfigRules';
import type { StateProposal } from '@/modules/tax/api/taxApi';

/**
 * Mutation-gap tests for taxConfigRules.ts (SRS-7-20, BR-TAX-006).
 * Equivalent mutants, not tested: L23 `open <= 0` (a code starting with a bracket fails the head
 * pattern either way); L29 `rupees ?? ''` (split always yields a first part); L65 `?? ''` for the
 * registration (a non-empty replacement text is plain ASCII, so it raises no problem).
 */
describe('taxConfigRules mutation gaps', () => {
  const draft = (over: Partial<StateProposal> = {}) =>
    ({
      code: 'MH',
      name: 'Maharashtra',
      ptApplicable: true,
      registrationNumber: null,
      returnPeriodicity: 'MONTHLY',
      effectiveFrom: '2026-04-01',
      effectiveTo: null,
      reason: 'Notified',
      ...over,
    }) as StateProposal & { reason: string };

  it('SRS-7-20: an absent end date is open-ended, never compared as text', () => {
    expect(stateProblems(draft())).toEqual([]);
    // "Z" sorts after the text a mutated fallback would hold, so an open end must not be compared.
    expect(stateProblems(draft({ effectiveFrom: 'Z', effectiveTo: undefined as unknown as null }))).toEqual(
      [],
    );
    expect(stateProblems(draft({ effectiveTo: '2026-03-31' }))).toEqual(['error.TAX-422-PT-STATE']);
  });
});
