import { describe, expect, it } from 'vitest';
import { componentProblems, type ComponentDraft } from '@/modules/configuration/business/componentRules';

/** M-07 eligibility rule key (SRS-G-42): optional; when given, capitals, digits, '_', '.', '*', 1 to 100. */

const GOOD: ComponentDraft = {
  payCode: '913',
  name: 'Demo washing allowance',
  type: 'EARNING',
  payslipGroup: 'ALLOWANCES',
  basisRuleKey: 'DEMO.WASHING',
  eligibilityRuleKey: '',
  source: 'RULE',
  naturalAccount: 'NA-4101',
  taxable: 'Y',
  inCpfBase: 'N',
  subjectToLwp: false,
  payrollTypes: ['STAFF'],
  effectiveFrom: '2026-11-01',
  reason: 'New allowance by circular',
};

describe('M-07 eligibility rule key', () => {
  it.each(['', 'E', 'ELIG.WASHING', 'ELIG.*', 'A_1.B*', 'A'.repeat(100)])(
    'SRS_G_42_eligibilityKey %j isAccepted',
    (eligibilityRuleKey) => {
      expect(componentProblems({ ...GOOD, eligibilityRuleKey })).toEqual([]);
    },
  );

  it.each(['elig', 'x.ELIG', 'ELIG.x', 'ELIG WASHING', 'ELIG-1', 'A'.repeat(101)])(
    'SRS_G_42_eligibilityKey %j isRefused',
    (eligibilityRuleKey) => {
      expect(componentProblems({ ...GOOD, eligibilityRuleKey })).toEqual(['m07.bad.elig']);
    },
  );
});
