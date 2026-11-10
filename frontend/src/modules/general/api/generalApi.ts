import { api, API_BASE } from '@/shared/api';
import type { PageResult } from '@/shared/ui';

/**
 * Module 01 transactions (backend module `general`): X-05 Exceptions & Holds, X-06 Payroll Query,
 * X-07 HRMS Fallback Upload, I-20 Off-Cycle Salary, I-23 Supplementary Payment. The only place
 * the module talks to /api; every call is authorised again by the backend (screen gate + scope).
 */

// ---------------------------------------------------------------- shared

/** A document type of the order control (SRS-G-30). */
export interface DocumentType {
  code: string;
  name: string;
  referenceRequired: boolean;
}

/** The particulars of an order or approval note (SRS-G-30 / SRS-DM-13) and its file. */
export interface OrderInput {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface AttachmentView {
  id: number;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  orderDate: string | null;
  issuingAuthority: string | null;
  fileName: string;
  status: string;
  uploadedBy: string;
  uploadedAt: string;
}

/** C-08: the session's month and the optional severity and search of the worklist. */
export interface ReviewFilter {
  period: string;
  severity?: string;
  search?: string;
  module?: string;
  code?: string;
}

/** One condition of C-08 (SRS l.1669-1676). */
export interface ComputationCondition {
  moduleCode: string | null;
  exceptionCode: string;
  payrollType: string;
  count: number;
  severity: string;
  fieldName: string | null;
  fieldSource: string | null;
  correctionScreen: string | null;
  blocksLock: boolean;
  owner: string | null;
  resolvable: boolean;
  detail: string;
}

export interface ComputationTiles {
  total: number;
  blocking: number;
  fixable: number;
  hrms: number;
  systemOrMember: number;
}

/** The current run's review: its month (null: no run in scope), tiles and one page of conditions. */
export interface ComputationReview extends PageResult<ComputationCondition> {
  period: string | null;
  tiles: ComputationTiles;
}

function reviewQuery(filter: ReviewFilter): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (value) query.set(key, value);
  }
  return `?${query.toString()}`;
}

function orderForm(order: OrderInput, extra: Record<string, string | null | undefined> = {}): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(extra)) {
    if (value !== null && value !== undefined && value !== '') form.append(key, value);
  }
  form.append('documentType', order.documentType);
  if (order.reference) form.append('reference', order.reference);
  form.append('orderDate', order.orderDate);
  form.append('issuingAuthority', order.issuingAuthority);
  form.append('file', order.file);
  return form;
}

// ---------------------------------------------------------------- X-06 Payroll Query

export type QueryStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';

export interface PayrollQuery {
  id: number;
  reference: string;
  /** Null for a query without a Payroll ID (DDL payroll_result_ref is nullable). */
  payrollId: number | null;
  eis: string | null;
  memberName: string | null;
  payrollYymm: string | null;
  channel: 'ESEVA' | 'STAFF';
  queryText: string;
  concernsComputedAmount: boolean;
  status: QueryStatus;
  raisedBy: string;
  raisedAt: string;
  makerResponse: string | null;
  respondedBy: string | null;
  respondedAt: string | null;
  resolutionText: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

// ---------------------------------------------------------------- X-05 Exceptions & Holds

export type ExceptionStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'ACCEPTED' | 'ROUTED_UPSTREAM';

export interface ExceptionItem {
  id: number;
  cycleInstanceId: number | null;
  eis: string | null;
  memberName: string | null;
  moduleCode: string;
  exceptionCode: string;
  severity: string;
  blocksGate: string | null;
  entityName: string | null;
  fieldName: string | null;
  fieldSource: string | null;
  resolvableInPayroll: boolean;
  correctionScreen: string | null;
  ownerRoleCode: string | null;
  detail: string;
  status: ExceptionStatus;
  acceptedBy: string | null;
  acceptedReason: string | null;
  raisedAt: string;
  ageDays: number;
  resolvedAt: string | null;
}

export interface ExceptionWorklist extends PageResult<ExceptionItem> {
  tiles: { open: number; blockingG2: number; acceptedWithReason: number; upstream: number };
}

/**
 * The X-05 filters (SRS l.604: period, office scope, exception class; SRS-G-9): module = the class,
 * period a payroll month YYYY-MM, office an office id of the session's scope, code one condition
 * (the opened group). The engine is not a filter: payroll_exception keeps no payroll type
 * (PENDING F-05-3).
 */
export interface ExceptionFilter {
  module?: string;
  status?: string;
  severity?: string;
  period?: string;
  office?: string;
  code?: string;
  /** C-08 (SRS l.1643): one cycle instance's run, the id C-02 links with. */
  cycle?: string;
}

/**
 * One condition of the worklist (module + exception code), counted by the server over the whole
 * filtered scope (SRS l.634 "Count - Members affected"; never over one page of rows).
 */
export interface ExceptionCondition {
  moduleCode: string | null;
  exceptionCode: string;
  /** Entries of the condition and the distinct members they are about (cycle-level ones have none). */
  items: number;
  members: number;
  /** An unsettled blocking item holds gate G2: it must be cleared, never accepted. */
  blocksG2: boolean;
  /** The owner every item shares, or null. */
  owner: string | null;
  resolvableInPayroll: boolean;
  /** Something the Checker can still do: an unsettled item resolvable in Payroll. */
  actionable: boolean;
  correctionScreen: string | null;
  detail: string;
  firstRaisedAt: string;
}

export interface ExceptionConditions extends PageResult<ExceptionCondition> {
  tiles: ExceptionWorklist['tiles'];
  /** The exception classes (module codes) present in the session's scope. */
  modules: string[];
}

/** An office the worklist may be filtered to. */
export interface ExceptionOffice {
  id: number;
  code: string;
  name: string;
  type: string;
}

/** A query string with the page appended (docs/plan/X-pagination.md). */
function pageQuery(query: string, page: number, size: number): string {
  return `${query ? `${query}&` : '?'}page=${page}&size=${size}`;
}

function filterQuery(filter: ExceptionFilter): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) if (value) params.set(key, value);
  const q = params.toString();
  return q ? `?${q}` : '';
}

// ---------------------------------------------------------------- X-07 HRMS Fallback Upload

export type FallbackType = 'ATTENDANCE_LEAVE' | 'SUSPENSION_ABSCONDING';

/** X-07 tiles over the whole scope. */
export interface FallbackSummary {
  awaitingApproval: number;
  reconciliationPending: number;
}

export interface Enablement {
  id: number;
  reference: string;
  type: FallbackType;
  payrollYymm: string;
  offices: string[];
  reason: string;
  enabledBy: string;
  enabledAt: string;
  status: string;
  withdrawnReason: string | null;
  /** The offices by code and name, as a screen shows them. */
  officeNames: string[];
  /** When HRMS came back (a reconciled enablement only). */
  hrmsRestoredAt: string | null;
}

export interface FallbackUpload {
  id: number;
  batchNo: string;
  enablementId: number;
  type: FallbackType;
  office: string;
  payrollYymm: string;
  fileName: string;
  recordCount: number;
  acceptedCount: number;
  rejectedCount: number;
  uploadedBy: string;
  uploadedAt: string;
  status: string;
  checkerUser: string | null;
  decisionReason: string | null;
  reconStatus: string;
  orderCopies: number;
  sourceAttachmentId: number | null;
  /** The pending approval item the Checker decides (null before submission). */
  approvalId: number | null;
  enablementReference: string | null;
}

/** One reason records of an upload were rejected for (X-07 "Validation"). */
export interface FallbackRejection {
  reason: string;
  count: number;
}

/** The tile "HRMS interface". */
export interface HrmsInterfaceStatus {
  down: boolean;
  since: string | null;
}

/** One row of the HRMS Fallback Upload Reconciliation (Annexure A.11); others above 0: matched lines folded. */
export interface ReconciliationRow {
  reference: string;
  payrollYymm: string;
  employeeNumber: string | null;
  employeeName: string | null;
  others: number;
  item: string;
  uploaded: string | null;
  hrms: string | null;
  effect: 'NONE' | 'ARREAR' | 'RECOVERY';
  difference: string | null;
  nextCycleYymm: string | null;
  status: 'MATCHED' | 'DIFFERENCE' | 'NOT_IN_HRMS' | 'CLOSED';
}

export interface FallbackLine {
  lineNo: number;
  employeeNumber: string | null;
  recordType: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  payDays: string | null;
  lwpDays: string | null;
  halfPayDays: string | null;
  eventEffectiveDate: string | null;
  orderNo: string | null;
  validationStatus: 'ACCEPTED' | 'REJECTED';
  rejectionReason: string | null;
  reconStatus: string;
}

export interface NewEnablement {
  type: FallbackType;
  payrollYymm: string;
  offices: string[];
  reason: string;
}

// ---------------------------------------------------------------- I-20 Off-Cycle Salary

export interface OffCycleCase {
  id: number;
  reference: string;
  eis: string | null;
  memberName: string | null;
  caseType: 'LATE_JOINER' | 'DEATH_IN_SERVICE' | 'OTHER';
  salaryYymm: string;
  processingYymm: string;
  amount: number;
  approvalReference: string;
  duplicateCheck: string | null;
  status: string;
  invoiceStatus: string;
  invoiceNo: string | null;
  invoiceSentToAppsDate: string | null;
  appsCancelConfirmedAt: string | null;
  excludedFromYymm: string | null;
  cancelReason: string | null;
  orders: number;
  approvalId: number | null;
}

export interface NewOffCycle {
  eis: string;
  caseType: string;
  salaryYymm: string;
  approvalReference: string;
}

// ---------------------------------------------------------------- I-23 Supplementary Payment

export interface SupplementaryPayment {
  id: number;
  reference: string;
  eis: string | null;
  memberName: string | null;
  paymentType: string;
  payCode: number | null;
  forPeriodYymm: string;
  amount: number;
  applicationDate: string;
  sanctionDate: string | null;
  sanctionOrderRef: string | null;
  duplicateCheck: 'PENDING' | 'PASSED' | 'BLOCKED';
  duplicateOf: string | null;
  status: string;
  invoiceStatus: string;
  invoiceId: number | null;
  invoiceSentAt: string | null;
  appsCancelStatus: string;
  cancelReason: string | null;
  orderAttachmentId: number | null;
  orderReference: string | null;
  approvalId: number | null;
}

export interface SupplementaryTiles {
  open: number;
  pendingChecker: number;
  blockedDuplicates: number;
  rejected: number;
}

export interface NewSupplementary {
  eis: string;
  paymentType: string;
  forPeriodYymm: string;
  applicationDate: string;
  sanctionDate: string;
  sanctionOrderRef: string;
  order: OrderInput;
}

// ---------------------------------------------------------------- calls

export const generalApi = {
  documentTypes: () => api<DocumentType[]>('/v1/document-types'),

  queries: (status: QueryStatus | undefined, page: number, size: number) =>
    api<PageResult<PayrollQuery>>(
      `/v1/queries?${status ? `status=${status}&` : ''}page=${page}&size=${size}`,
    ),
  queryCounts: () => api<Record<QueryStatus, number>>('/v1/queries/counts'),
  raiseQuery: (payrollId: number, queryText: string, concernsComputedAmount: boolean) =>
    api<PayrollQuery>('/v1/queries', {
      method: 'POST',
      body: { payrollId, queryText, concernsComputedAmount },
    }),
  respondQuery: (id: number, response: string, concernsComputedAmount: boolean) =>
    api<PayrollQuery>(`/v1/queries/${id}/respond`, {
      method: 'POST',
      body: { response, concernsComputedAmount },
    }),
  resolveQuery: (id: number, resolution: string) =>
    api<PayrollQuery>(`/v1/queries/${id}/resolve`, { method: 'POST', body: { resolution } }),

  exceptions: (filter: ExceptionFilter, page: number, size: number) =>
    api<ExceptionWorklist>(`/v1/exceptions${pageQuery(filterQuery(filter), page, size)}`),
  exceptionGroups: (filter: ExceptionFilter, page: number, size: number) =>
    api<ExceptionConditions>(`/v1/exceptions/groups${pageQuery(filterQuery(filter), page, size)}`),
  exceptionOffices: () => api<ExceptionOffice[]>('/v1/exceptions/offices'),
  /** C-08: the conditions of the current run with the drawn tiles (SRS-G-9, SRS l.1669-1676). */
  computationExceptions: (filter: ReviewFilter, page: number, size: number) =>
    api<ComputationReview>(`/v1/computation-exceptions${pageQuery(reviewQuery(filter), page, size)}`),
  computationItems: (filter: ReviewFilter, module: string | null, code: string, page: number, size: number) =>
    api<PageResult<ExceptionItem>>(
      `/v1/computation-exceptions/items${pageQuery(
        reviewQuery({ period: filter.period, module: module ?? undefined, code }),
        page,
        size,
      )}`,
    ),
  computationExportUrl: (filter: ReviewFilter) =>
    `${API_BASE}/v1/computation-exceptions/export${reviewQuery(filter)}`,
  exceptionsExportUrl: (filter: ExceptionFilter) => `${API_BASE}/v1/exceptions/export${filterQuery(filter)}`,
  assignException: (id: number, owner: string) =>
    api<ExceptionItem>(`/v1/exceptions/${id}/assign`, { method: 'POST', body: { owner } }),
  acceptException: (id: number, reason: string) =>
    api<ExceptionItem>(`/v1/exceptions/${id}/accept`, { method: 'POST', body: { reason } }),

  enablements: (page: number, size: number) =>
    api<PageResult<Enablement>>(`/v1/hrms-fallback/enablements?page=${page}&size=${size}`),
  proposeEnablement: (body: NewEnablement) =>
    api<{ approvalId: number }>('/v1/hrms-fallback/enablements', { method: 'POST', body }),
  withdrawEnablement: (id: number, reason: string) =>
    api<Enablement>(`/v1/hrms-fallback/enablements/${id}/withdraw`, { method: 'POST', body: { reason } }),
  liveEnablements: () => api<Enablement[]>('/v1/hrms-fallback/enablements/live'),
  fallbackSummary: () => api<FallbackSummary>('/v1/hrms-fallback/summary'),
  hrmsInterfaceStatus: () => api<HrmsInterfaceStatus>('/v1/hrms-fallback/interface-status'),
  uploads: (page: number, size: number, enablementId?: number) =>
    api<PageResult<FallbackUpload>>(
      `/v1/hrms-fallback/uploads?page=${page}&size=${size}${enablementId ? `&enablementId=${enablementId}` : ''}`,
    ),
  reconciliation: (page: number, size: number) =>
    api<PageResult<ReconciliationRow>>(`/v1/hrms-fallback/reconciliation?page=${page}&size=${size}`),
  uploadRejections: (id: number) => api<FallbackRejection[]>(`/v1/hrms-fallback/uploads/${id}/rejections`),
  upload: (enablementId: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api<FallbackUpload>(`/v1/hrms-fallback/uploads?enablementId=${enablementId}`, {
      method: 'POST',
      body: form,
    });
  },
  uploadLines: (id: number, page: number, size: number) =>
    api<PageResult<FallbackLine>>(`/v1/hrms-fallback/uploads/${id}/lines?page=${page}&size=${size}`),
  uploadOrder: (id: number, order: OrderInput) =>
    api<AttachmentView>(`/v1/hrms-fallback/uploads/${id}/order`, { method: 'POST', body: orderForm(order) }),
  uploadOrders: (id: number) => api<AttachmentView[]>(`/v1/hrms-fallback/uploads/${id}/orders`),
  submitUpload: (id: number) =>
    api<FallbackUpload>(`/v1/hrms-fallback/uploads/${id}/submit`, { method: 'POST', body: {} }),
  cancelUpload: (id: number, reason: string) =>
    api<FallbackUpload>(`/v1/hrms-fallback/uploads/${id}/cancel`, { method: 'POST', body: { reason } }),

  offCycleCases: (page: number, size: number, caseType?: string) =>
    api<PageResult<OffCycleCase>>(
      `/v1/off-cycle?${caseType ? `caseType=${encodeURIComponent(caseType)}&` : ''}page=${page}&size=${size}`,
    ),
  captureOffCycle: (body: NewOffCycle) => api<OffCycleCase>('/v1/off-cycle', { method: 'POST', body }),
  offCycleOrder: (id: number, order: OrderInput) =>
    api<AttachmentView>(`/v1/off-cycle/${id}/order`, { method: 'POST', body: orderForm(order) }),
  offCycleOrders: (id: number) => api<AttachmentView[]>(`/v1/off-cycle/${id}/orders`),
  submitOffCycle: (id: number) =>
    api<OffCycleCase>(`/v1/off-cycle/${id}/submit`, { method: 'POST', body: {} }),
  cancelOffCycle: (id: number, reason: string) =>
    api<OffCycleCase>(`/v1/off-cycle/${id}/cancel`, { method: 'POST', body: { reason } }),

  supplementary: (page: number, size: number, paymentType?: string) =>
    api<PageResult<SupplementaryPayment>>(
      `/v1/supplementary?${paymentType ? `paymentType=${encodeURIComponent(paymentType)}&` : ''}page=${page}&size=${size}`,
    ),
  supplementaryTiles: () => api<SupplementaryTiles>('/v1/supplementary/tiles'),
  captureSupplementary: (input: NewSupplementary) =>
    api<SupplementaryPayment>('/v1/supplementary', {
      method: 'POST',
      body: orderForm(input.order, {
        eis: input.eis,
        paymentType: input.paymentType,
        forPeriodYymm: input.forPeriodYymm,
        applicationDate: input.applicationDate,
        sanctionDate: input.sanctionDate,
        sanctionOrderRef: input.sanctionOrderRef,
      }),
    }),
  supplementaryOrders: (id: number) => api<AttachmentView[]>(`/v1/supplementary/${id}/orders`),
  submitSupplementary: (id: number) =>
    api<SupplementaryPayment>(`/v1/supplementary/${id}/submit`, { method: 'POST', body: {} }),
  cancelSupplementary: (id: number, reason: string) =>
    api<SupplementaryPayment>(`/v1/supplementary/${id}/cancel`, { method: 'POST', body: { reason } }),
  /** SRS-G-13 list exports: downloads through the browser's session (links, not fetches). */
  queriesExportUrl: (status?: QueryStatus) =>
    `${API_BASE}/v1/queries/export${status ? `?status=${status}` : ''}`,
  offCycleExportUrl: () => `${API_BASE}/v1/off-cycle/export`,
  supplementaryExportUrl: () => `${API_BASE}/v1/supplementary/export`,
  uploadsExportUrl: () => `${API_BASE}/v1/hrms-fallback/uploads/export`,
  reconciliationExportUrl: () => `${API_BASE}/v1/hrms-fallback/reconciliation/export`,
  /** A kept file is downloaded through the browser's session (a link, not a fetch). */
  fileUrl: (screen: 'off-cycle' | 'supplementary' | 'hrms-fallback/uploads', id: number, fileId: number) =>
    `${API_BASE}/v1/${screen}/${id}/files/${fileId}`,
};
