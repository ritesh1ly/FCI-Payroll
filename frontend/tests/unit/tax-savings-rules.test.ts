import { describe, expect, it } from 'vitest';
import type { NewSavingsEntry } from '@/modules/tax/api/taxApi';
import { entryProblems, INCOME_HEADS, monthsOf, rebateProblems } from '@/modules/tax/business/savingsRules';

/** SRS-7-11, SRS-7-20, SRS-7-25, BR-TAX-015: the I-18 entry and transport rebate checks. */

const TODAY = '2026-10-25';
const SECTIONS = [{ code: '80C', description: 'Life insurance', limit: 150000, regime: 'OLD' as const }];

function entry(over: Partial<NewSavingsEntry> = {}): NewSavingsEntry {
  return {
    type: 'OTHER_INCOME',
    sectionCode: '',
    incomeHead: 'Honorarium',
    description: '',
    amount: '12000',
    tdsOtherSource: '',
    document: {
      documentType: 'CERTIFICATE',
      reference: '',
      documentDate: '2026-10-01',
      issuingAuthority: 'Issuer',
    },
    file: new File(['%PDF'], 'proof.pdf'),
    ...over,
  };
}

describe('I-18 rules', () => {
  it('SRS_7_20_anEntryNamesASectionOrAHeadAnAmountAndADocument', () => {
    expect(entryProblems(entry(), SECTIONS, TODAY)).toEqual([]);
    expect(entryProblems(entry({ type: 'EXTERNAL_SAVINGS', sectionCode: '80C' }), SECTIONS, TODAY)).toEqual(
      [],
    );
    expect(entryProblems(entry({ type: 'EXTERNAL_SAVINGS', sectionCode: '80D' }), SECTIONS, TODAY)).toEqual([
      'error.TAX-422-ENTRY-KIND',
    ]);
    expect(entryProblems(entry({ incomeHead: ' ' }), SECTIONS, TODAY)).toEqual(['error.TAX-422-ENTRY-KIND']);
    for (const amount of ['0', '', '1.005', '-1']) {
      expect(entryProblems(entry({ amount }), SECTIONS, TODAY), amount).toEqual([
        'error.TAX-422-ENTRY-AMOUNT',
      ]);
    }
    expect(entryProblems(entry({ tdsOtherSource: '-1' }), SECTIONS, TODAY)).toEqual([
      'error.TAX-422-ENTRY-AMOUNT',
    ]);
    expect(entryProblems(entry({ tdsOtherSource: '0' }), SECTIONS, TODAY)).toEqual([]);
    expect(entryProblems(entry({ description: 'विवरण' }), SECTIONS, TODAY)).toEqual([
      'i18.problem.description',
    ]);
    const doc = (d: Partial<NewSavingsEntry['document']>) =>
      entryProblems(entry({ document: { ...entry().document, ...d } }), SECTIONS, TODAY);
    expect(doc({ documentDate: TODAY })).toEqual([]);
    expect(doc({ reference: '  ' })).toEqual([]);
    for (const bad of [
      { documentType: '' },
      { documentDate: '' },
      { documentDate: '2026-10-26' },
      { issuingAuthority: '' },
      { reference: 'संदर्भ' },
    ]) {
      expect(doc(bad), JSON.stringify(bad)).toEqual(['e01t.problem.proof']);
    }
    expect(INCOME_HEADS).toEqual(['Vehicle perquisite', 'Honorarium', 'NSC interest']);
    // External savings need no head; the second of two sections is found; blanks are none.
    const two = [...SECTIONS, { code: '80D', description: 'Health', limit: 25000, regime: 'BOTH' as const }];
    expect(
      entryProblems(entry({ type: 'EXTERNAL_SAVINGS', sectionCode: '80D', incomeHead: '' }), two, TODAY),
    ).toEqual([]);
    expect(entryProblems(entry({ tdsOtherSource: '   ' }), SECTIONS, TODAY)).toEqual([]);
    expect(entryProblems(entry({ description: '   ' }), SECTIONS, TODAY)).toEqual([]);
  });

  it('BR_TAX_015_theRebateMonthsAreTheYearsNewOnesAboveZero', () => {
    expect(monthsOf('2026-27')).toEqual([
      '202604',
      '202605',
      '202606',
      '202607',
      '202608',
      '202609',
      '202610',
      '202611',
      '202612',
      '202701',
      '202702',
      '202703',
    ]);
    const none = new Set<string>();
    expect(rebateProblems({ '202604': '1600' }, none, 'Rebate')).toEqual([]);
    expect(rebateProblems({ '202604': '1600', '202605': '' }, none, 'Rebate')).toEqual([]);
    expect(rebateProblems({}, none, 'Rebate')).toEqual(['i18.problem.rebate']);
    expect(rebateProblems({ '202604': ' ' }, none, 'Rebate')).toEqual(['i18.problem.rebate']);
    expect(rebateProblems({ '202604': '0' }, none, 'Rebate')).toEqual(['i18.problem.rebate']);
    expect(rebateProblems({ '202604': '12345678901' }, none, 'Rebate')).toEqual(['i18.problem.rebate']);
    expect(rebateProblems({ '202604': '1' }, new Set(['202604']), 'Rebate')).toEqual(['i18.problem.rebate']);
    expect(rebateProblems({ '202604': '1' }, none, '')).toEqual(['m18s.problem.reason']);
    // Blank months are left out; one wrong month of two stops it; ten digits (with spaces) are allowed.
    expect(rebateProblems({ '202604': '1600', '202605': '   ' }, none, 'Rebate')).toEqual([]);
    expect(rebateProblems({ '202604': '1600', '202605': '0' }, none, 'Rebate')).toEqual([
      'i18.problem.rebate',
    ]);
    expect(rebateProblems({ '202604': ' 1234567890 ' }, none, 'Rebate')).toEqual([]);
    expect(rebateProblems({ '202604': '1234567890.99' }, none, 'Rebate')).toEqual([]);
  });
});
