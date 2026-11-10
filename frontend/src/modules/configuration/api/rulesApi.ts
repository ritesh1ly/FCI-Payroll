import { API_BASE, api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';
import type { OtpProof } from '@/modules/workflow';

/**
 * M-08 Rule / Parameter Umbrella Catalogue (SRS-19-1 … 21) over /v1/rules. The value a version holds
 * depends on its rule type's shape (backend RuleValueShape): one number, slab bands or a formula.
 */
export type ValueShape = 'SCALAR' | 'SLAB' | 'NUMBER_FORMULA' | 'CONDITION_FORMULA';

/** Derived as at today, never stored (SRS-19-11, CF-28; plan 06 D-5). */
export type RuleStatus =
  'DRAFT' | 'PENDING_APPROVAL' | 'REJECTED' | 'APPROVED_FUTURE' | 'ACTIVE' | 'SUPERSEDED';

/** One slab band: covers from <= amount < to; no "to" = and above (SRS-19-5). */
export interface RuleBand {
  order: number;
  from: number;
  to: number | null;
  rateOrAmount: number;
  step: number | null;
  qualifier: string | null;
}

export interface RuleVersionView {
  /** null for a pending proposal: the version is written only on approval (plan 06 D-7). */
  id: number | null;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: RuleStatus;
  value: number | null;
  formula: string | null;
  bands: RuleBand[];
  roundingRuleKey: string | null;
  circularId: number | null;
  circularNumber: string | null;
  blocked: boolean;
  blockedReason: string | null;
  createdBy: string;
  createdAt: string;
  approvalId: number | null;
  /** FRS §24.9: the Rule Approver who decided it; null while pending or for a seeded version. */
  approvedBy: string | null;
  /** FRS §24.9: the approved version it superseded (ended the day before it starts), or null. */
  supersedesVersionNo: number | null;
}

export interface PendingEnd {
  approvalId: number;
  versionNo: number;
  endOn: string;
  requestedBy: string;
  requestedAt: string;
}

export interface RuleRow {
  key: string;
  type: string;
  domain: string;
  name: string;
  description: string | null;
  payrollTypeScope: string | null;
  officeScopeId: number | null;
  officeScopeName: string | null;
  /** The screen that shows the rule as its own register (M-18 statutory rates, M-19 CPF parameters). */
  owningModule: string | null;
  orderRequired: boolean;
  active: boolean;
  /** Maintained on M-08: one of the seven SRS-19-1 types of the COMPUTATION domain. */
  maintained: boolean;
  valueShape: ValueShape | null;
  inForce: RuleVersionView | null;
  pending: RuleVersionView | null;
  pendingEnd: PendingEnd | null;
  versions: number;
  /** A domain with its own master screen, listed read-only on M-08 (SRS-19-21). */
  enteredOn?: string;
  records?: number;
  heldFrom?: string | null;
  readThroughPending?: boolean;
}

/** SRS-19-21: what a domain entered on M-14, M-15 or M-20 holds in force today. */
export interface ReadThroughRow {
  key: string;
  type: string;
  screen: string;
  records: number;
  from: string | null;
  pending: boolean;
}

/** BR-RULE-006: the amounts of a run, on the sample, for one month. */
export interface SimulationPriced {
  priced: number;
  notComputed: number;
  currentComponent: number;
  proposedComponent: number;
  currentNet: number;
  proposedNet: number;
  statutoryDelta: Record<string, number>;
  negativeNet: number;
  retroPeriods: number;
}

export interface RuleSummary {
  rules: number;
  byType: Record<string, number>;
  pendingRuleChanges: number;
  pendingOtherChanges: number;
  notConfigured: number;
}

export interface RuleCatalogue {
  asOf: string;
  summary: RuleSummary;
  rules: RuleRow[];
}

/** A change awaiting its decision on M-08, M-14 or M-15 (SRS-19-21). */
export interface PendingItem {
  screen: string;
  entity: string;
  key: string;
  approvalId: number;
  submittedBy: string;
  submittedAt: string;
}

/** The formula grammar in force (SRS-19-15): a function's arity null = any number of arguments. */
export interface Vocabulary {
  operators: string[];
  functions: Record<string, number | null>;
  variables: string[];
}

export interface ProposedRuleVersion {
  effectiveFrom: string;
  effectiveTo: string | null;
  value: number | null;
  formula: string | null;
  bands: RuleBand[] | null;
  roundingRuleKey: string | null;
  circularId: number | null;
  reason: string;
}

export interface ProposedEnd {
  endOn: string;
  reason: string;
}

/** One group of an impact simulation (06e): members of a payroll type at an office. */
export interface SimulationGroup {
  payrollType: string;
  officeId: number;
  officeName: string | null;
  members: number;
  /** The value in force, a value text, or NOT_CONFIGURED / NOT_APPLICABLE. */
  current: string;
  proposed: string;
  changes: boolean;
}

/** An M-08 impact simulation of the version awaiting approval (SRS-19-4). */
export interface RuleSimulation {
  id: number;
  approvalId: number;
  ruleKey: string;
  payrollType: string | null;
  officeId: number | null;
  requestedBy: string;
  requestedAt: string;
  population: number;
  impact: {
    sampled: number;
    inScope: number;
    changed: number;
    groups: SimulationGroup[];
    priced?: SimulationPriced | null;
  };
  ruleVersionId: number | null;
  inputsChanged: boolean;
}

export interface SimulationScope {
  payrollType: string | null;
  officeId: number | null;
}

const withOtp = <T extends object>(body: T, otp?: OtpProof) => ({ ...body, ...(otp ? { otp } : {}) });

/** A rule key as a path segment: encoded, so no key can change the path it is sent to. */
const segment = (key: string) => encodeURIComponent(key);

export const rulesApi = {
  catalogue: () => api<RuleCatalogue>('/v1/rules'),
  readThrough: () => api<ReadThroughRow[]>('/v1/rules/read-through'),
  pending: (page: number, size: number) =>
    api<PageResult<PendingItem>>(`/v1/rules/pending?page=${page}&size=${size}`),
  vocabulary: () => api<Vocabulary>('/v1/rules/vocabulary'),
  versions: (key: string, page: number, size: number) =>
    api<PageResult<RuleVersionView>>(`/v1/rules/${segment(key)}/versions?page=${page}&size=${size}`),
  propose: (key: string, body: ProposedRuleVersion, otp?: OtpProof) =>
    api<RuleVersionView>(`/v1/rules/${segment(key)}/versions`, {
      method: 'POST',
      body: withOtp(body, otp),
    }),
  simulationOffices: () =>
    api<{ id: number; code: string; name: string; type: string }[]>('/v1/rules/simulation-offices'),
  simulations: (key: string, page: number, size: number) =>
    api<PageResult<RuleSimulation>>(`/v1/rules/${segment(key)}/simulations?page=${page}&size=${size}`),
  simulate: (key: string, body: SimulationScope) =>
    api<RuleSimulation>(`/v1/rules/${segment(key)}/simulations`, { method: 'POST', body }),
  end: (key: string, body: ProposedEnd, otp?: OtpProof) =>
    api<PendingEnd>(`/v1/rules/${segment(key)}/end`, { method: 'POST', body: withOtp(body, otp) }),
  /** SRS-19-21: the Rule Catalogue Summary report (all rules by type and status), on demand. */
  catalogueSummaryUrl: () => `${API_BASE}/v1/rules/export`,
  /** SRS-19-21: the Pending Approval - Cross-Domain report (counts across M-08, M-14, M-15). */
  pendingReportUrl: () => `${API_BASE}/v1/rules/pending/export`,
};
