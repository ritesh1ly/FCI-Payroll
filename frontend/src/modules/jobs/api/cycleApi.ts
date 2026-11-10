import { type PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';
import type { StoredOrder } from '@/shared/ui/orderCopy';

/** C-02 payroll cycle of the session's cycle unit (payroll_cycle_instance, target DDL v4.0). */
export interface CycleRun {
  id: number;
  type: 'ACTUAL' | 'SIMULATION';
  runNo: number;
  status: string;
  startedBy: string;
  startedAt: string;
  completedAt: string | null;
  eligible: number | null;
  results: number;
  units: number | null;
  unitsCommitted: number;
  unitsFailed: number;
}

/** SRS-15-28: the exclusive lease held on the instance (cycle_lease), as the screen shows it. */
export interface CycleLease {
  cycleId: number;
  action: 'COMPUTE' | 'FREEZE' | 'UNFREEZE' | 'UNDO' | 'LOCK' | 'RETURN' | 'CLOSE';
  heldBy: string;
  acquiredAt: string;
  heartbeatAt: string;
  runId: number | null;
  /** The heartbeat stopped: the next action takes the lease over (plan 05 D-7). */
  expired: boolean;
}

/** The run of record on C-01 (SRS l.1764). */
export interface CycleRunState {
  id: number;
  status: string;
  startedBy: string;
  startedAt: string;
  units: number | null;
  unitsCommitted: number;
  unitsFailed: number;
}

/** C-01 tiles: `daysToDisbursement` from today; `engines` the engine rows by payroll type. */
export interface CycleTiles {
  disbursement: string | null;
  daysToDisbursement: number | null;
  engines: Record<string, string>;
  blockingExceptions: number;
  verifiedBy: string | null;
  verifiedAt: string | null;
}

/** C-01 beside the gates; the cycle fields are null where no cycle was initiated. */
export interface CycleOverview {
  period: string;
  finYear: string;
  unitCode: string;
  unitLabel: string;
  unitLevel: string;
  cycleId: number | null;
  status: string | null;
  frozenBy: string | null;
  frozenAt: string | null;
  postedToEbs: boolean;
  supplementaryOpen: boolean;
  tiles: CycleTiles;
  preRun: PreRunCheck[];
  run: CycleRunState | null;
  lease: CycleLease | null;
  levels: { level: string; instances: number; locked: number }[];
  fallback: { unitCode: string; unitLabel: string; type: string; status: string; blocksLock: boolean }[];
}

/** One row of the Period Register (SRS-15-12: the flags stand beside the status). */
export interface PeriodRow {
  period: string;
  status: string;
  computedAt: string | null;
  verifiedBy: string | null;
  verifiedAt: string | null;
  lockedAt: string | null;
  postedToEbs: boolean;
  supplementaryOpen: boolean;
  mdpOpen: boolean;
}

/** One failed check of the Pre-Payroll Exception Report, by office (SRS-15-39). */
export interface PrePayrollReportRow {
  unitCode: string;
  unitLabel: string;
  checkCode: string;
  checkName: string;
  severity: string;
  failures: number;
  detail: string | null;
  correctionScreen: string | null;
}

/** DDL `cycle_return.return_scope` (SRS-15-30). */
export type ReturnScope = 'INSTANCE' | 'ORG_UNIT' | 'MEMBERS';

/** SRS-15-30: what the Checker returns, and why. */
export interface ReturnInput {
  scope: ReturnScope;
  reason: string;
  orgUnitCode?: string;
  members?: string[];
}

/** SRS-15-30: the Checker's open return of the cycle (cycle_return OPEN). */
export interface CycleReturn {
  id: number;
  scope: ReturnScope;
  orgUnitCode: string | null;
  /** EIS numbers of the returned members (MEMBERS). */
  members: string[];
  fromStatus: 'COMPUTED' | 'VERIFIED';
  reason: string;
  returnedBy: string;
  returnedAt: string;
  runId: number;
}

export interface Cycle {
  period: string;
  unitCode: string;
  unitLabel: string;
  unitLevel: string;
  inCalendar: boolean;
  computeBy: string | null;
  lockTarget: string | null;
  id: number | null;
  status: string | null;
  initiatedBy: string | null;
  initiatedAt: string | null;
  frozenBy: string | null;
  frozenAt: string | null;
  verifiedBy: string | null;
  verifiedAt: string | null;
  lockedBy: string | null;
  lockedAt: string | null;
  currentRunId: number | null;
  runs: CycleRun[];
  resultsRunId: number | null;
  resultsRunType: 'ACTUAL' | 'SIMULATION' | null;
  totals: {
    members: number;
    gross: number;
    deductions: number;
    net: number;
    components: { payCode: number; name: string; amount: number }[];
  };
  lockApprovalId: number | null;
  lockApprovalStatus: string | null;
  /** SRS-15-12: independent flags beside the status, never a step of the status sequence. */
  postedToEbs: boolean;
  supplementaryOpen: boolean;
  /** SRS-15-28: who holds the instance now (null when free) and the open force-release request. */
  lease: CycleLease | null;
  leaseReleaseApprovalId: number | null;
  /** SRS-15-30 / SRS-15-31: the open return (null when none) and a waiting un-freeze request. */
  openReturn: CycleReturn | null;
  unfreezeApprovalId: number | null;
}

export interface ResultRow {
  resultId: number;
  eis: string;
  name: string;
  gross: number;
  deductions: number;
  net: number;
  status: string;
  locked: boolean;
  /** C-02 l.3689-3692: the member's pay pattern, Basic and DA as computed, the HRA city class. */
  payPattern: string | null;
  basic: number | null;
  da: number | null;
  cityClass: string | null;
  /** Wireframes v6.1: the scale of Pattern / Scale, HRA and the other earnings as computed. */
  scale: string | null;
  hra: number | null;
  other: number;
  /** The drawn Status (M-16 list C02_RESULT_STATUS) and its filter group. */
  rowStatus: string;
  statusGroup: 'COMPUTED' | 'EXCEPTION' | 'HELD';
}

/** C-02 Pay Pattern (SRS l.3686): IDA, CDA, or both (no filter). */
export type PayPatternFilter = '' | 'IDA' | 'CDA';
/** The drawn Status filter (M-16 list C02_RESULT_FILTER); '' is all. */
export type ResultStatusFilter = '' | 'COMPUTED' | 'EXCEPTION' | 'HELD';

export interface ResultFilter {
  payPattern: PayPatternFilter;
  status: ResultStatusFilter;
  search: string;
}

/** The run C-02 shows: the session's period, or the unit's latest with a run. */
export interface ResultsPeriod {
  period: string;
  runType: 'ACTUAL' | 'SIMULATION';
}

/** The four tiles (the whole run) and the totals line (the filter). */
export interface ResultsSummary {
  ida: { members: number; exceptions: number };
  cda: { members: number; exceptions: number };
  gross: number;
  deductions: number;
  net: number;
  held: number;
  shown: { members: number; gross: number; deductions: number; net: number };
}

/** A component line of the trace: the drawn row (head) and what produced it (BR-RULE-007). */
export interface TraceLine {
  payCode: number;
  name: string;
  head: string;
  amount: number;
  rate: number | null;
  basis: number | null;
  rule: string | null;
  ruleVersion: number | null;
}

/** The calculation trace of one member (FRS §7.4). */
export interface CalculationTrace {
  resultId: number;
  eis: string;
  name: string;
  period: string;
  payPattern: string | null;
  cityClass: string | null;
  stagnationCase: boolean;
  accommodation: { type: string; amount: number | null; hraSuppressed: boolean } | null;
  gross: number;
  deductions: number;
  net: number;
  lines: TraceLine[];
}

/** A PLI transaction of the unit with its invoice status (BR-AUDIT-009 / 010). */
export interface PliPayment {
  id: number;
  entry: string;
  eis: string;
  member: string;
  type: string;
  finYear: string;
  amount: number;
  invoiceNo: number | null;
  invoiceStatus: string;
  sentToAppsAt: string | null;
  appsCancelStatus: string;
}

export interface ResultLine {
  payCode: number;
  name: string;
  type: string;
  amount: number;
  rate: number | null;
  basis: number | null;
}

/** C-01 gate G0 - G6 as evaluated (SRS-15-14). */
export interface GateView {
  code: string;
  met: boolean;
  overridable: boolean;
  /** The Checker may accept it now (G2 / G5 unmet and not accepted; G2 without blocking items). */
  acceptable: boolean;
  detail: string | null;
  acceptedBy: string | null;
  acceptedAt: string | null;
  acceptedReason: string | null;
}

/** C-01 pre-lock check, its latest evaluation (SRS-15-39). */
export interface CheckView {
  checkId: number;
  code: string;
  name: string;
  appliesTo: string;
  severity: 'BLOCK' | 'WARN';
  result: 'PASS' | 'FAIL';
  failures: number;
  detail: string | null;
  correctionScreen: string | null;
  acknowledgedBy: string | null;
  acknowledgeReason: string | null;
}

export interface GatesView {
  cycleId: number;
  status: string;
  gates: GateView[];
  checks: CheckView[];
  blocking: string[];
  lockable: boolean;
}

/** G6: a quarantined member (SRS-15-13). */
export interface QuarantineView {
  id: number;
  employeeId: number;
  empNum: string;
  name: string;
  reason: string;
  net: number | null;
  reviewStatus: 'UNREVIEWED' | 'CORRECTED' | 'ACCEPTED';
  reviewedBy: string | null;
  reviewReason: string | null;
}

/** SRS-15-17: a row of the Gate Override & Undo / Reversal Register (undo_reversal_register). */
export interface RegisterEntry {
  id: number;
  type: 'GATE_OVERRIDE' | 'UNDO' | 'REVERSAL';
  period: string | null;
  entity: string;
  key: string;
  stage: string | null;
  reason: string;
  requestedBy: string;
  checkedBy: string | null;
  authorisedBy: string;
  approvalId: number | null;
  executedAt: string;
}

/** One initiation check P1-P5 (SRS-15-23): NOT_AVAILABLE = configured inactive until its source exists. */
export interface PreRunCheck {
  code: string;
  name: string;
  status: 'PASS' | 'FAIL' | 'NOT_AVAILABLE';
  failures: number;
  /** The failing Employee Numbers (the first 20). */
  members: string[];
  correctionScreen: string | null;
}

const base = (period: string) => `/v1/cycles/${encodeURIComponent(period)}`;

/** The drawn filters as query parameters; an absent filter is not sent. */
const filterQuery = (f: ResultFilter): string =>
  [
    f.payPattern && `payPattern=${f.payPattern}`,
    f.status && `status=${f.status}`,
    f.search && `search=${encodeURIComponent(f.search)}`,
  ]
    .filter(Boolean)
    .join('&');

/** The only place the jobs module talks to /api/v1/cycles. */
export const cycleApi = {
  cycle: (period: string) => api<Cycle>(base(period)),
  /** Empty (204) when the unit has no run at all. */
  resultsPeriod: (period: string) => api<ResultsPeriod | undefined>(`${base(period)}/results-period`),
  results: (period: string, page: number, size: number, filter: ResultFilter) => {
    const query = filterQuery(filter);
    return api<PageResult<ResultRow>>(
      `${base(period)}/results?page=${page}&size=${size}${query ? `&${query}` : ''}`,
    );
  },
  resultsSummary: (period: string, filter: ResultFilter) => {
    const query = filterQuery(filter);
    return api<ResultsSummary>(`${base(period)}/results/summary${query ? `?${query}` : ''}`);
  },
  /** SRS-G-13: the export link of the filtered list. */
  resultsExportUrl: (period: string, filter: ResultFilter) => {
    const query = filterQuery(filter);
    return `${API_BASE}${base(period)}/results/export${query ? `?${query}` : ''}`;
  },
  trace: (period: string, resultId: number) =>
    api<CalculationTrace>(`${base(period)}/results/${resultId}/trace`),
  pliPayments: (period: string, page: number, size: number) =>
    api<PageResult<PliPayment>>(`${base(period)}/pli-payments?page=${page}&size=${size}`),
  lines: (period: string, resultId: number, page: number, size: number) =>
    api<PageResult<ResultLine>>(`${base(period)}/results/${resultId}?page=${page}&size=${size}`),
  preRunChecks: (period: string) => api<PreRunCheck[]>(`${base(period)}/pre-run-checks`),
  /** C-01: what the screen draws beside the gates, on C-01 VIEW (SRS l.1755-1767). */
  overview: (period: string) => api<CycleOverview>(`${base(period)}/overview`),
  /** SRS-15-12: the Period Register of the unit, newest first. */
  periodRegister: (page: number, size: number) =>
    api<PageResult<PeriodRow>>(`/v1/cycles/period-register?page=${page}&size=${size}`),
  /** SRS-15-39: the Pre-Payroll Exception Report by office. */
  prePayrollReport: (period: string, page: number, size: number) =>
    api<PageResult<PrePayrollReportRow>>(`${base(period)}/pre-payroll-report?page=${page}&size=${size}`),
  /** SRS-G-32: the override approval notes stored with the period's instance. */
  documentTypes: () =>
    api<{ code: string; name: string; referenceRequired: boolean }[]>('/v1/document-types'),
  orders: (period: string) => api<StoredOrder[]>(`${base(period)}/orders`),
  attachOrder: (period: string, order: FormData) =>
    api<StoredOrder>(`${base(period)}/order`, { method: 'POST', body: order }),
  orderFileUrl: (period: string, id: number) => `${API_BASE}${base(period)}/files/${id}`,
  initiate: (period: string) => api<Cycle>(`${base(period)}/initiate`, { method: 'POST', body: {} }),
  freeze: (period: string, otp?: OtpProof) =>
    api<Cycle>(`${base(period)}/freeze`, { method: 'POST', body: otp ? { otp } : {} }),
  verify: (period: string) => api<Cycle>(`${base(period)}/verify`, { method: 'POST', body: {} }),
  gates: (period: string) => api<GatesView>(`${base(period)}/gates`),
  /** G5 names the members legitimately excluded (SRS l.1524; BMAD TR-010). */
  acceptGate: (period: string, gate: string, reason: string, members?: string[]) =>
    api<GatesView>(`${base(period)}/gates/${gate}/accept`, {
      method: 'POST',
      body: members && members.length > 0 ? { reason, members } : { reason },
    }),
  acknowledgeCheck: (period: string, checkId: number, reason: string) =>
    api<GatesView>(`${base(period)}/checks/${checkId}/acknowledge`, { method: 'POST', body: { reason } }),
  quarantine: (period: string, page: number, size: number) =>
    api<PageResult<QuarantineView>>(`${base(period)}/quarantine?page=${page}&size=${size}`),
  reviewMember: (period: string, id: number, accepted: boolean, reason: string) =>
    api<GatesView>(`${base(period)}/quarantine/${id}/review`, { method: 'POST', body: { accepted, reason } }),
  lock: (period: string, reason: string, otp?: OtpProof) =>
    api<Cycle>(`${base(period)}/lock`, { method: 'POST', body: { reason, ...(otp ? { otp } : {}) } }),
  /** SRS-15-28: asks the X-01 authority to force-release the lease (202; the Checker decides with OTP). */
  requestLeaseRelease: (period: string, reason: string) =>
    api<Cycle>(`${base(period)}/lease-releases`, { method: 'POST', body: { reason } }),
  /** SRS-15-30: the Checker returns the cycle with a scope and a reason. */
  returnCycle: (period: string, input: ReturnInput) =>
    api<Cycle>(`${base(period)}/returns`, { method: 'POST', body: input }),
  /** SRS-15-30: withdraws a return raised in error. */
  withdrawReturn: (period: string, returnId: number, reason: string) =>
    api<Cycle>(`${base(period)}/returns/${returnId}/withdraw`, { method: 'POST', body: { reason } }),
  /** SRS-15-17: the unit's register, newest first, one page. */
  register: (page: number, size: number) =>
    api<PageResult<RegisterEntry>>(`/v1/cycle-register-entries?page=${page}&size=${size}`),
  /** SRS-15-17, SRS-G-13: the register's export link (the format is chosen in the export dialog). */
  registerExportUrl: () => `${API_BASE}/v1/cycle-register-entries/export`,
  /** SRS-15-31: asks for the un-freeze before lock (202; route C02_UNFREEZE decides). */
  requestUnfreeze: (period: string, reason: string) =>
    api<Cycle>(`${base(period)}/unfreeze-requests`, { method: 'POST', body: { reason } }),
};
