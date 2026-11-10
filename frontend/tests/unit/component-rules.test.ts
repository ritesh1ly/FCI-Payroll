import { describe, expect, it } from 'vitest';
import {
  componentProblems,
  type ComponentDraft,
  EMPTY_COMPONENT,
  PAYROLL_TYPES,
  removesPayrollType,
} from '@/modules/configuration/business/componentRules';

/** M-07 checks before a component is sent (plan 06 phase 06d; SRS-G-42, FAP-14). */

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

describe('M-07 component rules', () => {
  it('SRS_G_42_aWellFormedDefinitionHasNoProblem', () => {
    expect(componentProblems(GOOD)).toEqual([]);
    expect(componentProblems({ ...GOOD, payslipGroup: '' })).toEqual([]);
    // SRS l.1907: Driver / Basis is mandatory (Yes), so an empty one is a problem.
    expect(componentProblems({ ...GOOD, basisRuleKey: '' })).toEqual(['m07.bad.basis']);
  });

  it('SRS_G_42_anEmptyFormSaysEveryRequiredField', () => {
    expect(componentProblems(EMPTY_COMPONENT)).toEqual([
      'm07.bad.code',
      'm07.bad.name',
      'm07.bad.type',
      'm07.bad.basis',
      'm07.bad.source',
      'm07.bad.account',
      'm07.bad.taxable',
      // SRS l.1912: In CPF Base is mandatory, drawn as a Y / N select with no default.
      'm07.bad.cpf',
      'm07.bad.payrollTypes',
      'm07.bad.from',
      'm07.bad.reason',
    ]);
  });

  it.each([
    [{ payCode: '1000' }, 'm07.bad.code'],
    [{ payCode: '0' }, 'm07.bad.code'],
    [{ payCode: 'A1' }, 'm07.bad.code'],
    [{ name: 'x'.repeat(101) }, 'm07.bad.name'],
    [{ name: 'Bhatta भत्ता' }, 'm07.bad.name'],
    [{ payslipGroup: 'allowances' }, 'm07.bad.group'],
    [{ basisRuleKey: 'demo washing' }, 'm07.bad.basis'],
    [{ source: 'MANUAL' }, 'm07.bad.source'],
    [{ naturalAccount: '-4101' }, 'm07.bad.account'],
    [{ effectiveFrom: '01-11-2026' }, 'm07.bad.from'],
    [{ reason: '   ' }, 'm07.bad.reason'],
    [{ reason: 'r'.repeat(501) }, 'm07.bad.reason'],
    [{ reason: 'Kaaran कारण' }, 'm07.bad.reason'],
  ] as [Partial<ComponentDraft>, string][])('SRS_G_42_aBadFieldIsSaid %#', (patch, problem) => {
    expect(componentProblems({ ...GOOD, ...patch })).toEqual([problem]);
  });

  it('FAP_14_aRevisionAddsPayrollTypesAndNeverRemovesOne', () => {
    expect(removesPayrollType(['STAFF'], ['STAFF', 'DPS_LABOUR'])).toBe(false);
    expect(removesPayrollType(['STAFF', 'DPS_LABOUR'], ['DPS_LABOUR'])).toBe(true);
    expect(removesPayrollType([], [])).toBe(false);
  });
});

describe('M-07 component checks at their edges', () => {
  it('SRS-19-1: the payroll types and an empty draft', () => {
    expect(PAYROLL_TYPES).toEqual(['STAFF', 'DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR']);
    expect(EMPTY_COMPONENT).toEqual({
      payCode: '',
      name: '',
      type: '',
      payslipGroup: '',
      basisRuleKey: '',
      eligibilityRuleKey: '',
      source: '',
      naturalAccount: '',
      taxable: '',
      inCpfBase: '',
      subjectToLwp: false,
      payrollTypes: [],
      effectiveFrom: '',
      reason: '',
    });
  });

  it('SRS-19-1: the code is 1 to 999, the name and reason are trimmed to their limits', () => {
    expect(componentProblems({ ...GOOD, payCode: '1' })).toEqual([]);
    expect(componentProblems({ ...GOOD, payCode: '0' })).toContain('m07.bad.code');
    expect(componentProblems({ ...GOOD, name: ` ${'n'.repeat(100)} ` })).toEqual([]);
    expect(componentProblems({ ...GOOD, name: 'n'.repeat(101) })).toContain('m07.bad.name');
    expect(componentProblems({ ...GOOD, name: '   ' })).toContain('m07.bad.name');
    expect(componentProblems({ ...GOOD, reason: ` ${'r'.repeat(500)} ` })).toEqual([]);
    expect(componentProblems({ ...GOOD, reason: 'r'.repeat(501) })).toContain('m07.bad.reason');
    expect(componentProblems({ ...GOOD, reason: '   ' })).toContain('m07.bad.reason');
  });

  it('SRS-19-1: group, basis, account and date match whole values only', () => {
    expect(componentProblems({ ...GOOD, payslipGroup: 'x_ALLOW' })).toContain('m07.bad.group');
    expect(componentProblems({ ...GOOD, payslipGroup: 'ALLOW_x' })).toContain('m07.bad.group');
    expect(componentProblems({ ...GOOD, basisRuleKey: 'a.KEY' })).toContain('m07.bad.basis');
    expect(componentProblems({ ...GOOD, basisRuleKey: 'KEY.a' })).toContain('m07.bad.basis');
    expect(componentProblems({ ...GOOD, naturalAccount: '4100x' })).toContain('m07.bad.account');
    expect(componentProblems({ ...GOOD, effectiveFrom: 'x2026-04-01' })).toContain('m07.bad.from');
    expect(componentProblems({ ...GOOD, effectiveFrom: '2026-04-01x' })).toContain('m07.bad.from');
  });
});
