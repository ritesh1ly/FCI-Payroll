import { describe, expect, it } from 'vitest';
import type { NewSanction, PurposeOption, SanctionStage } from '@/modules/cpf/api/cpfApi';
import {
  missingDocuments,
  MAX_DOCUMENTS,
  requestProblems,
  resubmittable,
  STAGE_PILL,
  tierAction,
  validAmount,
  waitingTier,
  withdrawable,
} from '@/modules/cpf/business/sanctionRules';

const HOUSE: PurposeOption = {
  code: 'HOUSE',
  name: 'Purchase of a ready-built house',
  category: 'PART_FINAL',
  propertyDetails: true,
  documents: [
    { documentType: 'SALE_DEED', mandatory: true },
    { documentType: 'CERTIFICATE', mandatory: true },
    { documentType: 'OTHER', mandatory: false },
  ],
};

const file = (documentType: string, over: object = {}) => ({
  documentType,
  reference: 'R/1',
  documentDate: '2026-09-01',
  issuingAuthority: 'Registrar',
  file: new File(['%PDF'], 'd.pdf'),
  ...over,
});

const draft = (over: Partial<NewSanction> = {}): NewSanction => ({
  purposeCode: 'HOUSE',
  amount: '90000',
  propertyAddress: 'Plot 4, Sector 9',
  propertyDescription: 'Two rooms',
  documents: [file('SALE_DEED'), file('CERTIFICATE')],
  ...over,
});

const ALL: SanctionStage[] = [
  'UNIT_VERIFICATION',
  'ZONAL_HQ_VALIDATION',
  'COMPETENT_AUTHORITY',
  'SANCTIONED',
  'PAYMENT_BLOCKED',
  'PAID',
  'RETURNED',
  'REJECTED',
  'CANCELLED',
];

describe('CPF withdrawal chain rules (E-01 / I-04)', () => {
  it('SRS_6_4_eachStageWaitsAtItsTierAndOnlyTheTierHolderActsOnI04', () => {
    expect(ALL.map(waitingTier)).toEqual([1, 2, 3, null, null, null, null, null, null]);
    const unit = (a: string) => a === 'EDIT';
    const division = (a: string) => a === 'SUBMIT';
    expect(tierAction('UNIT_VERIFICATION', unit)).toBe('verify');
    expect(tierAction('UNIT_VERIFICATION', division)).toBeNull();
    expect(tierAction('ZONAL_HQ_VALIDATION', division)).toBe('recommend');
    expect(tierAction('ZONAL_HQ_VALIDATION', unit)).toBeNull();
    // Tier 3 is decided in the Approvals Inbox, never on I-04.
    expect(tierAction('COMPETENT_AUTHORITY', () => true)).toBeNull();
    expect(tierAction('SANCTIONED', () => true)).toBeNull();
    // Every stage has its pill.
    expect(ALL.every((s) => STAGE_PILL[s].startsWith('p-'))).toBe(true);
  });

  it('SRS_12_9_theMemberWithdrawsUntilTheSanctionAndResubmitsOnlyAReturnedRequest', () => {
    expect(ALL.filter(withdrawable)).toEqual([
      'UNIT_VERIFICATION',
      'ZONAL_HQ_VALIDATION',
      'COMPETENT_AUTHORITY',
      'RETURNED',
    ]);
    expect(ALL.filter(resubmittable)).toEqual(['RETURNED']);
  });

  it('SRS_6_26_anAmountIsMoreThanZeroWithAtMostTwoDecimals', () => {
    for (const ok of ['1', '0.01', '90000', '123456789012.50', ' 15 ']) expect(validAmount(ok)).toBe(true);
    for (const bad of ['', '0', '0.00', '-5', '1.234', 'abc', '1234567890123', '1.2.3', '12.50.1'])
      expect(validAmount(bad)).toBe(false);
  });

  it('SRS_6_14_theMandatoryDocumentsMissingAreNamedInChecklistOrder', () => {
    expect(missingDocuments(HOUSE, draft({ documents: [] }))).toEqual(['SALE_DEED', 'CERTIFICATE']);
    expect(missingDocuments(HOUSE, draft({ documents: [file('CERTIFICATE'), file('OTHER')] }))).toEqual([
      'SALE_DEED',
    ]);
    expect(missingDocuments(HOUSE, draft())).toEqual([]);
    expect(missingDocuments(undefined, draft())).toEqual([]);
  });

  it('SRS_6_14_aRequestCarriesAtMostFiveDocuments', () => {
    expect(MAX_DOCUMENTS).toBe(5);
    const five = draft({ documents: [...draft().documents, file('OTHER'), file('OTHER'), file('OTHER')] });
    const count = (d: typeof five) => requestProblems(d, HOUSE, '2026-10-24').map((p) => p.key);
    expect(five.documents.length).toBeLessThanOrEqual(5);
    expect(count(five)).not.toContain('error.CPF-422-DOCUMENT-COUNT');
    const six = draft({
      documents: [
        ...five.documents,
        ...Array.from({ length: 6 - five.documents.length }, () => file('OTHER')),
      ],
    });
    expect(six.documents).toHaveLength(6);
    expect(requestProblems(six, HOUSE, '2026-10-24')).toContainEqual({
      key: 'error.CPF-422-DOCUMENT-COUNT',
      args: ['5'],
    });
  });

  it('BR_CPF_039_theFormNamesEachProblemUntilTheRequestCanGo', () => {
    expect(requestProblems(draft(), HOUSE, '2026-10-24')).toEqual([]);
    expect(requestProblems(draft(), undefined, '2026-10-24').map((p) => p.key)).toEqual([
      'e01s.problem.purpose',
    ]);
    expect(requestProblems(draft({ amount: '0' }), HOUSE, '2026-10-24').map((p) => p.key)).toEqual([
      'e01s.problem.amount',
    ]);
    expect(requestProblems(draft({ propertyAddress: ' ' }), HOUSE, '2026-10-24').map((p) => p.key)).toEqual([
      'e01s.problem.property',
    ]);
    expect(
      requestProblems(draft({ propertyDescription: '' }), HOUSE, '2026-10-24').map((p) => p.key),
    ).toEqual(['e01s.problem.property']);
    // Spaces alone are no description either.
    expect(
      requestProblems(draft({ propertyDescription: '   ' }), HOUSE, '2026-10-24').map((p) => p.key),
    ).toEqual(['e01s.problem.property']);
    // A purpose without property details does not ask for them.
    expect(
      requestProblems(draft({ propertyAddress: '' }), { ...HOUSE, propertyDetails: false }, '2026-10-24'),
    ).toEqual([]);
    expect(requestProblems(draft({ documents: [file('CERTIFICATE')] }), HOUSE, '2026-10-24')).toEqual([
      { key: 'e01s.problem.documents', args: ['SALE_DEED'] },
    ]);
    // Every missing document is named, in checklist order, separated for reading.
    expect(requestProblems(draft({ documents: [] }), HOUSE, '2026-10-24')).toContainEqual({
      key: 'e01s.problem.documents',
      args: ['SALE_DEED, CERTIFICATE'],
    });
    // A document dated in the future, without an authority or without a type.
    for (const bad of [
      { documentDate: '2026-10-25' },
      { documentDate: '' },
      { issuingAuthority: ' ' },
      { documentType: '' },
    ]) {
      expect(
        requestProblems(
          draft({ documents: [file('SALE_DEED'), file('CERTIFICATE', bad)] }),
          HOUSE,
          '2026-10-24',
        ).map((p) => p.key),
      ).toContain('e01s.problem.particulars');
    }
    // Today is not in the future.
    expect(
      requestProblems(
        draft({ documents: [file('SALE_DEED'), file('CERTIFICATE', { documentDate: '2026-10-24' })] }),
        HOUSE,
        '2026-10-24',
      ),
    ).toEqual([]);
  });
});
