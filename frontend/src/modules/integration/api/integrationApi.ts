import { API_BASE, api } from '@/shared/api';
import type { PageResult } from '@/shared/ui';

/** The settings in force of an interface (SRS-INT-16; read-only on X-03). */
export interface InterfaceSettings {
  retryCount: number;
  backoffSeconds: number;
  backoffStrategy: string;
  timeoutSeconds: number;
  circuitBreakerRequired: boolean;
  circuitBreakerThreshold: number | null;
  ackTimeoutSeconds: number | null;
  counterpartySla: string | null;
  effectiveFrom: string;
  confirmed: boolean;
}

/** One interface as X-03 lists it. */
export interface InterfaceRow {
  code: string;
  name: string;
  counterparty: string;
  direction: string;
  transport: string | null;
  endpointObject: string | null;
  stagingObject: string | null;
  consumingScreen: string | null;
  specSignedOff: boolean;
  scopeStatus: string;
  active: boolean;
  status: string | null;
  lastExchangeAt: string | null;
  lastGoodExchangeAt: string | null;
  received: number;
  accepted: number;
  rejected: number;
  openExceptions: number;
  breakerState: string;
  adapter: boolean;
  settings: InterfaceSettings | null;
}

/** The specification held for an interface (Contract). */
export interface Contract {
  row: InterfaceRow;
  frequency: string | null;
  idempotencyKey: string | null;
  reconciliation: string | null;
}

/** X-03 HRMS Fallback Uploads (SRS-INT-22; US-GAP-63): one office of one enabled upload. */
export interface FallbackUploadRow {
  enablementId: number;
  reference: string;
  type: string;
  payrollYymm: string;
  officeCode: string;
  officeName: string;
  enablementStatus: string;
  batchNo: string | null;
  uploadStatus: string | null;
  reconStatus: string | null;
}

/** What a window's Reprocess Failed did (SRS-INT-33). */
export interface WindowReprocess {
  runId: number;
  interfaceCode: string;
  reprocessed: number[];
}

/** One exchange window. */
export interface Window {
  runId: number;
  interfaceCode: string;
  direction: string;
  batchReference: string | null;
  windowStart: string | null;
  windowEnd: string | null;
  received: number;
  accepted: number;
  rejected: number;
  status: string;
  retryAttempt: number;
  startedAt: string;
  completedAt: string | null;
  failureDetail: string | null;
  /** SRS-INT-33: the window's interface has an adapter to reprocess through. */
  adapter?: boolean;
}

/** One item of the exception queue. */
export interface QueueItem {
  id: number;
  interfaceCode: string;
  runId: number | null;
  stagingId: number | null;
  txnType: string | null;
  sourceKey: string | null;
  hrmsRequestId: string | null;
  reason: string;
  status: string;
  raisedAt: string;
  resolvedAt: string | null;
  writtenOffBy: string | null;
  writtenOffReason: string | null;
  reprocessedStagingId: number | null;
  adapter: boolean;
}

/** The figures above the lists. */
export interface Summary {
  interfaces: number;
  notConfigured: number;
  openExceptions: number;
  failedWindows: number;
  awaitingI21: number;
  oldestAwaitingI21: string | null;
}

export interface Outcome {
  interfaceCode: string;
  outcome: string;
}

/** One API of the HRMS Postman collection as X-03 lists it (read-only; the path only, never a full URL). */
export interface HrmsApiRow {
  code: string;
  method: string;
  selectionClass: 'REQUIRED' | 'RULE_PENDING';
  direction: 'INBOUND' | 'OUTBOUND';
  configured: boolean;
  path: string | null;
  interfaceCode: string | null;
  retryCount: number | null;
  backoffSeconds: number | null;
  backoffStrategy: string | null;
  timeoutSeconds: number | null;
  bulkheadMaxConcurrentCalls: number | null;
  bulkheadMaxWaitMillis: number | null;
  lastCallAt: string | null;
  lastStatus: 'OK' | 'PARTIAL' | 'FAILED' | 'NONE';
  received: number | null;
  accepted: number | null;
  rejected: number | null;
  refused: number | null;
  specSignedOff: boolean;
  days: number;
}

/** The filters of the HRMS APIs list; an empty value means no filter. */
export interface HrmsApiFilter {
  selectionClass: string;
  direction: string;
  configured: string;
  status: string;
  days: string;
}

/** One call of the HRMS API console (the data is the masked JSON text of the answer). */
export interface HrmsCallResult {
  code: string;
  method: string;
  outcome: 'ACCEPTED' | 'REJECTED' | 'FAILED_IN_BODY' | 'UNREADABLE' | 'INVALID_REQUEST';
  httpStatus: number | null;
  errorCode: string | null;
  message: string | null;
  problems: string[];
  duplicate: boolean;
  attempts: number;
  elapsedMillis: number;
  data: string | null;
}

/** What the HRMS API console sends. */
export interface HrmsCallInput {
  code: string;
  query: Record<string, string> | null;
  body: unknown;
  reason: string;
}

function page(query: URLSearchParams, p: number, size: number): string {
  query.set('page', String(p));
  query.set('size', String(size));
  return `?${query.toString()}`;
}

/** X-03 Integration Monitor API (plan 23 phase 23b; API-23-7). */
export const integrationApi = {
  summary: () => api<Summary>('/v1/integration/summary'),
  interfaces: (counterparty: string, p: number, size: number) => {
    const q = new URLSearchParams();
    if (counterparty) q.set('counterparty', counterparty);
    return api<PageResult<InterfaceRow>>(`/v1/integration/interfaces${page(q, p, size)}`);
  },
  /** The drawn tables of X-03 (OD-121): HRMS or ENTERPRISE interfaces, of one latest status, matching a search. */
  drawnInterfaces: (scope: string, status: string, search: string, p: number, size: number) => {
    const q = new URLSearchParams();
    if (scope) q.set('scope', scope);
    if (status) q.set('status', status);
    if (search) q.set('q', search);
    return api<PageResult<InterfaceRow>>(`/v1/integration/interfaces${page(q, p, size)}`);
  },
  /** HRMS Postman APIs with configuration and activity (SRS-INT-6, SRS-INT-16, SRS-INT-32). */
  hrmsApis: (f: HrmsApiFilter, p: number, size: number) => {
    const q = new URLSearchParams();
    if (f.selectionClass) q.set('class', f.selectionClass);
    if (f.direction) q.set('direction', f.direction);
    if (f.configured) q.set('configured', f.configured);
    if (f.status) q.set('status', f.status);
    if (f.days) q.set('days', f.days);
    return api<PageResult<HrmsApiRow>>(`/v1/integration/hrms-apis${page(q, p, size)}`);
  },
  /** The configured outbound APIs the console can call (one page of 100). */
  hrmsApiOptions: () => {
    const q = new URLSearchParams({ direction: 'OUTBOUND', configured: 'true' });
    return api<PageResult<HrmsApiRow>>(`/v1/integration/hrms-apis${page(q, 0, 100)}`);
  },
  /** X-03 EDIT: one audited call of an HRMS API (403 INT-403-CONSOLE-DISABLED when the console is off). */
  callHrmsApi: (input: HrmsCallInput) =>
    api<HrmsCallResult>(`/v1/integration/hrms-apis/${encodeURIComponent(input.code)}/call`, {
      method: 'POST',
      body: { query: input.query, body: input.body, reason: input.reason },
    }),
  contract: (code: string) => api<Contract>(`/v1/integration/interfaces/${encodeURIComponent(code)}`),
  fallbackUploads: (p: number, size: number) =>
    api<PageResult<FallbackUploadRow>>(
      `/v1/integration/hrms-fallback-uploads${page(new URLSearchParams(), p, size)}`,
    ),
  windows: (code: string, p: number, size: number) => {
    const q = new URLSearchParams();
    if (code) q.set('interface', code);
    return api<PageResult<Window>>(`/v1/integration/windows${page(q, p, size)}`);
  },
  /** FRS V0.5 Integration Audit Trail: the envelope log as a file (a download link). */
  auditTrailUrl: (code: string) =>
    `${API_BASE}/v1/integration/windows/export${code ? `?interface=${encodeURIComponent(code)}` : ''}`,
  exceptions: (open: boolean, code: string, p: number, size: number) => {
    const q = new URLSearchParams({ open: String(open) });
    if (code) q.set('interface', code);
    return api<PageResult<QueueItem>>(`/v1/integration/exceptions${page(q, p, size)}`);
  },
  reprocess: (id: number, reason: string) =>
    api<QueueItem>(`/v1/integration/exceptions/${id}/reprocess`, { method: 'POST', body: { reason } }),
  /** X-03 l.5172 / SRS-INT-33: Reprocess Failed for every open record of one exchange window. */
  reprocessWindow: (runId: number, reason: string) =>
    api<WindowReprocess>(`/v1/integration/windows/${runId}/reprocess`, { method: 'POST', body: { reason } }),
  writeOff: (id: number, reason: string) =>
    api<QueueItem>(`/v1/integration/exceptions/${id}/write-off`, { method: 'POST', body: { reason } }),
  testConnection: (code: string) =>
    api<Outcome>(`/v1/integration/interfaces/${encodeURIComponent(code)}/test-connection`, {
      method: 'POST',
      body: {},
    }),
};
