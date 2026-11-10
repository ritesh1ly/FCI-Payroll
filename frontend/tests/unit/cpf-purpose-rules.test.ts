import { describe, expect, it } from 'vitest';
import type { PurposeProposal, WithdrawalPurpose } from '@/modules/cpf/api/cpfApi';
import { CATEGORIES, LIMIT_KEYS, TIERS, latest, purposeProblems } from '@/modules/cpf/business/purposeRules';

/** M-19 withdrawal purposes (SRS-6-5, SRS-6-14, SRS-6-39, BR-CPF-028): the screen's own checks. */

const MARRIAGE: WithdrawalPurpose = {
  id: 1,
  code: 'MARRIAGE',
  name: 'Marriage',
  category: 'ADVANCE',
  limitRuleKey: 'CPF.LIMIT.MGW.MARRIAGE',
  maxOccurrences: null,
  noCasteDispute: false,
  propertyDetails: false,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  confirmed: false,
  inForce: true,
  documents: [],
};

function draft(over: Partial<PurposeProposal & { reason: string }> = {}) {
  return {
    code: 'MARRIAGE',
    name: 'Marriage',
    category: 'ADVANCE' as const,
    limitRuleKey: 'CPF.LIMIT.MGW.MARRIAGE',
    maxOccurrences: null,
    noCasteDispute: false,
    propertyDetails: false,
    effectiveFrom: '2027-04-01',
    confirmed: true,
    documents: [],
    reason: 'Checklist supplied by FCI',
    ...over,
  };
}

describe('M-19 withdrawal purposes', () => {
  it('SRS_6_5_aWellFormedVersionAfterTheOneInForcePasses', () => {
    expect(purposeProblems(draft(), [MARRIAGE])).toEqual([]);
    expect(purposeProblems(draft({ code: 'NEW_ONE', effectiveFrom: '2026-01-01' }), [MARRIAGE])).toEqual([]);
    expect(purposeProblems(draft({ maxOccurrences: 1 }), [MARRIAGE])).toEqual([]);
    expect(purposeProblems(draft({ maxOccurrences: 999, limitRuleKey: null }), [MARRIAGE])).toEqual([]);
  });

  it('SRS_6_5_eachProblemIsNamed', () => {
    expect(purposeProblems(draft({ code: 'marriage' }), [])).toEqual(['error.CPF-422-PURPOSE-CODE']);
    // The code is the whole field: nothing before or after it.
    for (const code of ['xMARRIAGE', 'MARRIAGE-x', ' MARRIAGE']) {
      expect(purposeProblems(draft({ code }), []), code).toEqual(['error.CPF-422-PURPOSE-CODE']);
    }
    expect(purposeProblems(draft({ code: 'COVID_19' }), [])).toEqual(['error.CPF-422-PURPOSE-WITHDRAWN']);
    expect(purposeProblems(draft({ name: ' ' }), [])).toEqual(['m19p.problem.name']);
    expect(purposeProblems(draft({ limitRuleKey: 'CPF.VPF.MAX' }), [])).toEqual([
      'error.CPF-422-PURPOSE-LIMIT',
    ]);
    expect(purposeProblems(draft({ category: 'FINAL' }), [])).toEqual(['error.CPF-422-PURPOSE-LIMIT']);
    expect(purposeProblems(draft({ category: 'FINAL', limitRuleKey: null }), [])).toEqual([]);
    for (const q of [0, 1000, 1.5]) {
      expect(purposeProblems(draft({ maxOccurrences: q }), []), String(q)).toEqual([
        'error.CPF-422-PURPOSE-QUOTA',
      ]);
    }
    const doc = { documentType: 'CERTIFICATE', mandatory: true, tier: 'UNIT_CPF_ADMIN' as const };
    expect(purposeProblems(draft({ documents: [doc] }), [])).toEqual([]);
    expect(purposeProblems(draft({ documents: [doc, doc] }), [])).toEqual(['error.CPF-422-PURPOSE-DOCUMENT']);
    expect(purposeProblems(draft({ documents: [{ ...doc, documentType: '' }] }), [])).toEqual([
      'error.CPF-422-PURPOSE-DOCUMENT',
    ]);
    expect(purposeProblems(draft({ effectiveFrom: '2026-04-01' }), [MARRIAGE])).toEqual([
      'error.CPF-422-PURPOSE-DATE',
    ]);
    expect(purposeProblems(draft({ effectiveFrom: '' }), [])).toEqual(['error.CPF-422-PURPOSE-DATE']);
    expect(purposeProblems(draft({ reason: 'x'.repeat(501) }), [])).toEqual(['m19p.problem.reason']);
    expect(purposeProblems(draft({ reason: 'x'.repeat(500) }), [])).toEqual([]);
  });

  it('SRS_6_23_theLatestVersionIsTheOneThatStartsLast', () => {
    const later = { ...MARRIAGE, id: 2, effectiveFrom: '2027-04-01' };
    expect(latest([MARRIAGE, later], 'MARRIAGE')).toBe(later);
    expect(latest([later, MARRIAGE], 'MARRIAGE')).toBe(later);
    expect(latest([MARRIAGE], 'PLOT')).toBeUndefined();
  });

  it('SRS_6_2_theListsMirrorTheServer', () => {
    expect(CATEGORIES).toEqual(['ADVANCE', 'PART_FINAL', 'FINAL']);
    expect(TIERS).toEqual(['UNIT_CPF_ADMIN', 'ZONAL_HQ_CPF_DIVISION', 'COMPETENT_AUTHORITY']);
    expect(LIMIT_KEYS).toEqual([
      'CPF.LIMIT.MGW.MARRIAGE',
      'CPF.LIMIT.MGW.RELIGIOUS',
      'CPF.LIMIT.MGW.HIGHER_EDUCATION',
      'CPF.LIMIT.MGW.HOUSE',
      'CPF.LIMIT.MGW.PLOT',
      'CPF.LIMIT.PAY.RENOVATION',
      'CPF.LIMIT.BALANCE.BEFORE_RETIREMENT',
    ]);
  });
});
