import { describe, expect, it } from 'vitest';
import type { DeclarationLine, NewDeclarationLine, ProofFile, SectionOption } from '@/modules/tax/api/taxApi';
import {
  allowedOf,
  declarationProblems,
  isAmount,
  sectionsFor,
  verificationProblems,
} from '@/modules/tax/business/declarationRules';

/** SRS-7-1, SRS-7-16, SRS-7-20, SRS-7-24, BR-TAX-002: the declaration checks before submission. */

const SECTIONS: SectionOption[] = [
  { code: '80C', description: 'Life insurance', limit: 150000, regime: 'OLD' },
  { code: '80D', description: 'Health insurance', limit: 25000, regime: 'BOTH' },
  { code: '24B', description: 'NEW only', limit: null, regime: 'NEW' },
];
const TODAY = '2026-10-25';

const PROOF: ProofFile = {
  documentType: 'CERTIFICATE',
  reference: '',
  documentDate: '2026-10-01',
  issuingAuthority: 'Insurer',
  file: new File(['x'], 'p.pdf'),
};

function line(over: Partial<NewDeclarationLine> = {}): NewDeclarationLine {
  return { kind: 'INVESTMENT', sectionCode: '80C', declared: '1000', proof: null, ...over };
}

describe('declaration rules', () => {
  it('SRS_7_16_anAmountIsRupeesAndPaise', () => {
    for (const ok of ['0', '1', '1000.5', '999999999999.99', ' 12 ']) expect(isAmount(ok), ok).toBe(true);
    for (const bad of ['', '-1', '1.005', '1.2.3', '.5', '1.', 'abc', '1234567890123'])
      expect(isAmount(bad), bad).toBe(false);
  });

  it('SRS_7_20_theSectionsOfferedAreThoseOfTheRegime', () => {
    expect(sectionsFor(SECTIONS, null)).toEqual([]);
    expect(sectionsFor(SECTIONS, 'OLD').map((s) => s.code)).toEqual(['80C', '80D']);
    expect(sectionsFor(SECTIONS, 'NEW').map((s) => s.code)).toEqual(['80D', '24B']);
  });

  it('BR_TAX_002_theAllowedAmountIsTheLowerOfDeclaredAndLimit', () => {
    expect(allowedOf(160000, 150000)).toBe(150000);
    expect(allowedOf(100, 150000)).toBe(100);
    expect(allowedOf(9_999_999, null)).toBe(9_999_999);
  });

  it('SRS_7_1_aRegimeIsRequiredAndEachLineIsChecked', () => {
    expect(declarationProblems({ regime: 'OLD', lines: [] }, SECTIONS, TODAY)).toEqual([]);
    expect(declarationProblems({ regime: null, lines: [] }, SECTIONS, TODAY)).toEqual([
      'error.TAX-422-REGIME-REQUIRED',
    ]);
    // A regime-less draft does not also name every section as unavailable.
    expect(declarationProblems({ regime: null, lines: [line()] }, SECTIONS, TODAY)).toEqual([
      'error.TAX-422-REGIME-REQUIRED',
    ]);
    const check = (l: NewDeclarationLine, regime: 'OLD' | 'NEW' = 'OLD') =>
      declarationProblems({ regime, lines: [l] }, SECTIONS, TODAY);
    expect(check(line({ declared: '0' }))).toContain('error.TAX-422-DECLARATION-AMOUNT');
    expect(check(line({ declared: '1.005' }))).toContain('error.TAX-422-DECLARATION-AMOUNT');
    expect(check(line(), 'NEW')).toContain('e01t.problem.section');
    expect(check(line({ sectionCode: '' }))).toContain('e01t.problem.section');
    expect(check(line({ kind: 'HRA_RENT', sectionCode: '80D' }))).toEqual([]);
  });

  it('SRS_7_24_eachSectionOncePerKindAndEachProofComplete', () => {
    const twice = declarationProblems(
      { regime: 'OLD', lines: [line(), line({ declared: '2' })] },
      SECTIONS,
      TODAY,
    );
    expect(twice).toEqual(['e01t.problem.duplicate']);
    // The same section under another kind is a different line.
    expect(
      declarationProblems({ regime: 'OLD', lines: [line(), line({ kind: 'HRA_RENT' })] }, SECTIONS, TODAY),
    ).toEqual([]);
    const proof = (p: Partial<ProofFile>) =>
      declarationProblems({ regime: 'OLD', lines: [line({ proof: { ...PROOF, ...p } })] }, SECTIONS, TODAY);
    expect(proof({})).toEqual([]);
    expect(proof({ reference: 'POL-1' })).toEqual([]);
    // A proof dated today is accepted; a blank reference is no reference.
    expect(proof({ documentDate: TODAY })).toEqual([]);
    expect(proof({ reference: '   ' })).toEqual([]);
    for (const bad of [
      { documentType: '' },
      { documentDate: '' },
      { documentDate: '2026-10-26' },
      { issuingAuthority: ' ' },
      { issuingAuthority: 'बीमा' },
      { reference: 'संदर्भ' },
    ]) {
      expect(proof(bad), JSON.stringify(bad)).toEqual(['e01t.problem.proof']);
    }
  });

  it('SRS_7_24_theMakerVerifiesEveryOpenLineUpToItsAllowedAmount', () => {
    const lines: DeclarationLine[] = [
      {
        id: 1,
        kind: 'INVESTMENT',
        sectionCode: '80C',
        declared: 160000,
        allowed: 150000,
        capped: true,
        proofStatus: 'PROOF_SUBMITTED',
        verified: null,
        frozen: false,
        proofId: 3,
      },
      {
        id: 2,
        kind: 'HRA_RENT',
        sectionCode: '80D',
        declared: 1,
        allowed: 1,
        capped: false,
        proofStatus: 'VERIFIED',
        verified: 1,
        frozen: true,
        proofId: null,
      },
    ];
    expect(verificationProblems(lines, { 1: '150000' })).toEqual([]);
    expect(verificationProblems(lines, { 1: '0' })).toEqual([]);
    expect(verificationProblems(lines, { 1: '150000.01' })).toEqual(['error.TAX-422-VERIFIED-AMOUNT']);
    expect(verificationProblems(lines, { 1: '' })).toEqual(['error.TAX-422-VERIFIED-AMOUNT']);
    expect(verificationProblems(lines, {})).toEqual(['error.TAX-422-VERIFIED-AMOUNT']);
    // Two open lines: one wrong amount is enough to stop the verification.
    const two = [lines[0] as DeclarationLine, { ...(lines[0] as DeclarationLine), id: 3 }];
    expect(verificationProblems(two, { 1: '1', 3: '1' })).toEqual([]);
    expect(verificationProblems(two, { 1: '1', 3: '999999' })).toEqual(['error.TAX-422-VERIFIED-AMOUNT']);
  });
});
