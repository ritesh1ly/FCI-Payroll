import { describe, expect, it } from 'vitest';
import type { SectionProposal, StateProposal } from '@/modules/tax/api/taxApi';
import { sectionProblems, stateProblems } from '@/modules/tax/business/taxConfigRules';

/** SRS-7-2, SRS-7-4, SRS-7-20, BR-TAX-002: the M-18 tax master checks before submission. */

const SECTION: SectionProposal & { reason: string } = {
  code: '80C',
  description: 'Life insurance, PF, ELSS',
  limit: '150000',
  regime: 'OLD',
  effectiveFrom: '2027-04-01',
  reason: 'Finance Act',
};

const STATE: StateProposal & { reason: string } = {
  code: 'MH',
  name: 'Maharashtra',
  ptApplicable: true,
  registrationNumber: null,
  returnPeriodicity: 'MONTHLY',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  reason: 'State notified',
};

describe('M-18 section rules', () => {
  it('SRS_7_20_aSectionIsNamedAsTheActWritesIt', () => {
    for (const code of ['80C', '80CCD(1B)', '24(b)', '10(13A)', '192', '80C(ABCD)']) {
      expect(sectionProblems({ ...SECTION, code }), code).toEqual([]);
    }
    for (const code of [
      'C80',
      '80c',
      '80CCD(1B',
      '1234',
      '',
      '80ABCDEF',
      '80C(12345)',
      '80C()',
      '(1B)',
      '80C(1B)(2)',
      `80C(${'1'.repeat(4)})X`,
    ]) {
      expect(sectionProblems({ ...SECTION, code }), code).toContain('error.TAX-422-SECTION-CODE');
    }
    expect(sectionProblems({ ...SECTION, code: '999ABCDE(12AB)' + 'X'.repeat(7) })).toContain(
      'error.TAX-422-SECTION-CODE',
    );
  });

  it('BR_TAX_002_aLimitIsRupeesAndPaiseOrNone', () => {
    for (const limit of [null, '', '0', '150000.5', '9999999999.99']) {
      expect(sectionProblems({ ...SECTION, limit }), String(limit)).toEqual([]);
    }
    for (const limit of ['-1', '1.005', '12345678901', 'abc', '1.2.3', '.5', '1.']) {
      expect(sectionProblems({ ...SECTION, limit }), limit).toContain('error.TAX-422-SECTION-LIMIT');
    }
  });

  it('SRS_7_2_theRegimeTextDateAndOrderAreChecked', () => {
    expect(sectionProblems({ ...SECTION, regime: 'ANY' as never })).toContain('error.TAX-422-SECTION-REGIME');
    expect(sectionProblems({ ...SECTION, regime: 'NEW' })).toEqual([]);
    expect(sectionProblems({ ...SECTION, regime: 'BOTH' })).toEqual([]);
    expect(sectionProblems({ ...SECTION, description: ' ' })).toContain('m18s.problem.description');
    expect(sectionProblems({ ...SECTION, description: 'बीमा' })).toContain('m18s.problem.description');
    expect(sectionProblems({ ...SECTION, reason: '' })).toContain('m18s.problem.reason');
    expect(sectionProblems({ ...SECTION, effectiveFrom: '' })).toContain('m18s.problem.order');
    expect(sectionProblems(SECTION, '2027-04-01')).toContain('m18s.problem.order');
    expect(sectionProblems(SECTION, '2026-04-01')).toEqual([]);
    expect(sectionProblems(SECTION, '2027-04-02')).toContain('m18s.problem.order');
    expect(sectionProblems({ ...SECTION, effectiveFrom: '' }, '2026-04-01')).toContain('m18s.problem.order');
  });
});

describe('M-18 PT state rules', () => {
  it('SRS_7_4_aStateHasItsCodeNameAndAnOrderedWindow', () => {
    expect(stateProblems(STATE)).toEqual([]);
    expect(stateProblems({ ...STATE, effectiveTo: '2026-04-01', registrationNumber: 'PTR-1' })).toEqual([]);
    // An empty end date is open-ended, like none.
    expect(stateProblems({ ...STATE, effectiveTo: '' })).toEqual([]);
    for (const bad of [
      { code: 'M' },
      { code: 'mh' },
      { code: 'ABCDEFGHIJK' },
      { name: ' ' },
      { effectiveFrom: '' },
      { effectiveTo: '2026-03-31' },
    ]) {
      expect(stateProblems({ ...STATE, ...bad }), JSON.stringify(bad)).toContain('error.TAX-422-PT-STATE');
    }
  });

  it('SRS_7_4_textPeriodicityAndReasonAreChecked', () => {
    expect(stateProblems({ ...STATE, name: 'महाराष्ट्र' })).toContain('m18p.problem.text');
    expect(stateProblems({ ...STATE, registrationNumber: 'पंजीकरण' })).toContain('m18p.problem.text');
    expect(stateProblems({ ...STATE, registrationNumber: '  ' })).toEqual([]);
    expect(stateProblems({ ...STATE, returnPeriodicity: 'monthly' })).toContain('m18p.problem.periodicity');
    expect(stateProblems({ ...STATE, returnPeriodicity: null })).toEqual([]);
    expect(stateProblems({ ...STATE, reason: ' ' })).toContain('m18s.problem.reason');
  });
});
