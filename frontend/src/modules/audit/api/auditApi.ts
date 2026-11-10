import { api, API_BASE } from '@/shared/api';
import type { PageResult } from '@/shared/ui';

/** A-05: one row of audit_event (append-only). */
export interface AuditEvent {
  id: number;
  changedAt: string;
  changedBy: string;
  entityName: string;
  entityKey: string;
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string;
  approvalId: number | null;
  moduleCode: string | null;
  sourceIp: string | null;
  effectiveDate: string;
  outcome: Outcome;
  approvalStatus: string | null;
  maker: Person | null;
  checker: Person | null;
  order: OrderRef | null;
}

/** X-04 tiles, each within the reader's own scope (legacyRows is null outside Headquarters). */
export interface AuditSummary {
  rowsThisCycle: number;
  legacyRows: number | null;
  retentionMonths: number | null;
  retentionSet: boolean;
}

/** SRS l.1248: the order of a change, with the id of its stored document for the link. */
export interface OrderRef {
  number: string;
  documentId: number;
}

/** X-04 Maker / Checker: login id with name and Employee No. (from the signed-in users). */
export interface Person {
  loginId: string;
  name: string | null;
  employeeNumber: string | null;
}

/** FAP-13 outcome of a row (a later decision kind is shown as recorded). */
export type Outcome = 'SUBMITTED' | 'APPROVED' | 'RETURNED' | 'REJECTED' | 'POSTED' | 'FAILED' | string;

/** X-04: one native audit row of a record with its audit fields (SRS-G-2). */
export interface HistoryRow {
  transactionId: number;
  changedAt: string;
  objectKey: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  outcome: Outcome;
  changeType: string | null;
  effectiveDate: string;
  reason: string;
  approvalId: number | null;
  approvalStatus: string | null;
  maker: Person | null;
  checker: Person | null;
  sourceIp: string | null;
  moduleCode: string | null;
  origin: 'NATIVE';
  order: OrderRef | null;
}

/** One approved version of a versioned master or rule record. */
export interface RecordVersion {
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalId: number | null;
  values: Record<string, string | null>;
}

/** X-04 / History button: one record's versions and one page of its native rows. */
export interface RecordView extends PageResult<HistoryRow> {
  entity: string;
  key: string;
  /** The record's own screen (its History button). */
  screen: string;
  versioned: boolean;
  versions: RecordVersion[];
}

/** SRS-20-3 / 20-6: the record as it stood on a date (exactly one version, or not in force). */
export interface AsOfView {
  entity: string;
  key: string;
  date: string;
  status: 'FOUND' | 'NOT_IN_FORCE';
  versioned: boolean;
  versionNo: number | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  approvalId: number | null;
  values: Record<string, string | null>;
}

/** SRS-20-23: one Legacy Audit row as held in legacy (never merged into the native trail). */
export interface LegacyRow {
  id: number;
  origin: 'LEGACY';
  sourceObject: string;
  sourceKey: string;
  grain: 'ROW_IMAGE' | 'FIELD';
  actionCode: string | null;
  fieldName: string | null;
  oldValue: string | null;
  newValue: string | null;
  rowImage: string | null;
  changedFields: string | null;
  changedBy: string | null;
  changedAt: string;
  reason: string | null;
}

export interface LegacyPage extends PageResult<LegacyRow> {
  entity: string;
  key: string;
}

/** A record as the enquiry names it: its type (audit entity) and key. */
export interface RecordRef {
  entity: string;
  key: string;
}

function recordQuery(ref: RecordRef, extra: Record<string, string> = {}): string {
  return new URLSearchParams({ entity: ref.entity, key: ref.key, ...extra }).toString();
}

/** The only place the audit module talks to /api/v1/admin/audit and /api/v1/audit. */
export const auditApi = {
  search: (query: string, page: number, size: number) =>
    api<PageResult<AuditEvent>>(`/v1/admin/audit?${query}&page=${page}&size=${size}`),
  /** The export link (a download through the browser's session, not a fetch). */
  exportUrl: (query: string) => `${API_BASE}/v1/admin/audit/export?${query}`,

  summary: () => api<AuditSummary>('/v1/admin/audit/summary'),
  /** SRS l.1248: the link to the order document of one audit row. */
  orderDocumentUrl: (auditId: number, documentId: number) =>
    `${API_BASE}/v1/admin/audit/${auditId}/order-documents/${documentId}`,

  recordTypes: () => api<string[]>('/v1/audit/record-types'),
  history: (ref: RecordRef, page: number, size: number) =>
    api<RecordView>(`/v1/audit/history?${recordQuery(ref, { page: String(page), size: String(size) })}`),
  asOf: (ref: RecordRef, date: string) =>
    api<AsOfView>(`/v1/audit/history/as-of?${recordQuery(ref, { date })}`),
  legacy: (ref: RecordRef, page: number, size: number) =>
    api<LegacyPage>(`/v1/audit/legacy?${recordQuery(ref, { page: String(page), size: String(size) })}`),
  /** SRS-20-29 Change History Report. */
  historyExportUrl: (ref: RecordRef) => `${API_BASE}/v1/audit/history/export?${recordQuery(ref)}`,
  /** SRS-20-29 Point-in-Time Reconstruction Extract. */
  asOfExportUrl: (ref: RecordRef, date: string) =>
    `${API_BASE}/v1/audit/history/as-of/export?${recordQuery(ref, { date })}`,
  legacyExportUrl: (ref: RecordRef) => `${API_BASE}/v1/audit/legacy/export?${recordQuery(ref)}`,
};
