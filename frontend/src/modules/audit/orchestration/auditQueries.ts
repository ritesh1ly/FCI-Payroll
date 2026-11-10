'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useQuery } from '@tanstack/react-query';
import { auditApi, type RecordRef } from '../api/auditApi';
import { type AuditFilter, auditQuery } from '../business/auditFilter';

export const auditKeys = {
  search: ['audit', 'search'] as const,
  history: ['audit', 'history'] as const,
  legacy: ['audit', 'legacy'] as const,
};

/** A-05 search: one page of {@code filter} (server-side paging, the whole result reachable). */
export function fetchAuditPage(filter: AuditFilter) {
  const query = auditQuery(filter);
  return (page: number, size: number) => auditApi.search(query, page, size);
}

/** The A-05 export link of {@code filter}. */
export function auditExportUrl(filter: AuditFilter): string {
  return auditApi.exportUrl(auditQuery(filter));
}

/** X-04 tiles, within the reader's scope. */
export function useAuditSummary() {
  return useQuery({ queryKey: ['audit', 'summary'], queryFn: auditApi.summary, staleTime: 60_000 });
}

export const orderDocumentUrl = auditApi.orderDocumentUrl;

/** X-04: the record types an enquiry can name. */
export function useRecordTypes() {
  return useQuery({ queryKey: ['audit', 'record-types'], queryFn: auditApi.recordTypes, staleTime: 300_000 });
}

/** X-04 / History button: one page of a record's native history (with its versions). */
export function fetchHistoryPage(ref: RecordRef) {
  return (page: number, size: number) => auditApi.history(ref, page, size);
}

/** The record's head: its screen, whether it is versioned, and its approved versions. */
export function useRecordHead(ref: RecordRef) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...auditKeys.history, 'head', ref.entity, ref.key],
    // The smallest page the server offers (SRS-NF-3: any other size is refused, COM-400-PAGE).
    queryFn: () => auditApi.history(ref, 0, 10),
    retry: false,
  });
}

/** SRS-20-3: the record as of {@code date}; nothing is asked without a date. */
export function useAsOf(ref: RecordRef, date: string) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['audit', 'as-of', ref.entity, ref.key, date],
    queryFn: () => auditApi.asOf(ref, date),
    enabled: date !== '',
    retry: false,
  });
}

/** SRS-20-23: one page of the record's Legacy Audit rows (each enquiry is logged by the server). */
export function fetchLegacyPage(ref: RecordRef) {
  return (page: number, size: number) => auditApi.legacy(ref, page, size);
}

export const historyExportUrl = auditApi.historyExportUrl;
export const asOfExportUrl = auditApi.asOfExportUrl;
export const legacyExportUrl = auditApi.legacyExportUrl;
