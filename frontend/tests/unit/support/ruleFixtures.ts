import type { RuleRow, RuleVersionView } from '@/modules/configuration/api/rulesApi';

/** M-08 fixtures shared by the rule tests (rule-rules.test.ts) and the screen tests (rule-catalogue.test.tsx). */

export const TODAY = '2026-10-23';

export function version(over: Partial<RuleVersionView> = {}): RuleVersionView {
  return {
    id: 11,
    versionNo: 3,
    effectiveFrom: '2026-07-01',
    effectiveTo: null,
    status: 'ACTIVE',
    value: 53,
    formula: null,
    bands: [],
    roundingRuleKey: null,
    circularId: 7,
    circularNumber: 'HQ/DA/2026/3',
    blocked: false,
    blockedReason: null,
    createdBy: 'su.hq@fci-demo.test',
    createdAt: '2026-06-20T05:00:00Z',
    approvalId: 90,
    approvedBy: null,
    supersedesVersionNo: null,
    ...over,
  };
}

export function rule(over: Partial<RuleRow> = {}): RuleRow {
  return {
    key: 'DA.IDA.RATE',
    type: 'RATE',
    domain: 'COMPUTATION',
    name: 'Dearness Allowance rate (IDA)',
    description: null,
    payrollTypeScope: 'STAFF',
    officeScopeId: null,
    officeScopeName: null,
    owningModule: null,
    orderRequired: true,
    active: true,
    maintained: true,
    valueShape: 'SCALAR',
    inForce: version(),
    pending: null,
    pendingEnd: null,
    versions: 3,
    ...over,
  };
}

export const RULES: RuleRow[] = [
  rule(),
  rule({
    key: 'CPF.SANCTION.LIMIT',
    type: 'CEILING',
    name: 'CPF sanction limit',
    inForce: null,
    versions: 0,
    orderRequired: false,
  }),
  rule({
    key: 'INCENTIVE.SLAB',
    type: 'SLAB',
    name: 'Labour incentive slab',
    payrollTypeScope: 'DEPT_LABOUR,DPS_LABOUR',
    valueShape: 'SLAB',
    inForce: version({
      value: null,
      bands: [
        { order: 1, from: 0, to: 1000, rateOrAmount: 0, step: null, qualifier: null },
        { order: 2, from: 1000, to: null, rateOrAmount: 5, step: 10, qualifier: 'CITY-X' },
      ],
    }),
  }),
  rule({
    key: 'TA.FORMULA',
    type: 'FORMULA',
    name: 'Transport allowance',
    valueShape: 'NUMBER_FORMULA',
    officeScopeId: 5,
    officeScopeName: 'Zonal Office North',
    inForce: version({ value: null, formula: 'ROUND(BASIC * 0.06, 0)' }),
    pending: version({ id: null, versionNo: 4, status: 'PENDING_APPROVAL', effectiveFrom: '2027-01-01' }),
  }),
  rule({
    key: 'LWF.348.APPLICABILITY',
    type: 'APPLICABILITY',
    name: 'Labour Welfare Fund applicability',
    valueShape: 'CONDITION_FORMULA',
    officeScopeId: 9,
    inForce: version({ value: null, formula: 'CITY = 1' }),
    pendingEnd: {
      approvalId: 95,
      versionNo: 3,
      endOn: '2026-12-31',
      requestedBy: 'su.hq@fci-demo.test',
      requestedAt: '2026-10-20T05:00:00Z',
    },
  }),
  rule({
    key: 'FORM16.DEFINITION',
    type: 'STATUTORY_OUTPUT',
    domain: 'STATUTORY_OUTPUT',
    name: 'Form 16 output definition',
    maintained: false,
    valueShape: null,
    payrollTypeScope: null,
    inForce: version({ value: null, blocked: true }),
  }),
  rule({
    key: 'OLD.RATE',
    name: 'Retired rate',
    active: false,
    maintained: false,
    inForce: null,
    versions: 2,
  }),
];
