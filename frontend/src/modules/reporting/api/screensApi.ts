import { api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';
import type { CatalogueEntry } from './reportApi';

/** BR-CPF-036: the version of a month's CPF reports and of the month before. */
export interface CpfMonths {
  period: string;
  version: 'FINAL' | 'PROVISIONAL';
  previous: string;
  previousVersion: 'FINAL' | 'PROVISIONAL';
}

/** The labour payroll of the O-03 report list. */
export type LabourEngine = 'DEPT' | 'DPS' | 'NWNP';

/** The toolbar's choices and the tiles of O-06; a null period: no invoice is staged yet. */
export interface InvoiceOverview {
  period: string | null;
  periods: string[];
  units: { id: number; label: string }[];
  count: number;
  value: number;
  salary: number;
  incentive: number;
  glGap: number;
  released: number;
  held: number;
  locked: boolean;
  types: { key: string; name: string }[];
}

/** A row of the Invoice Register, one per invoice output. */
export interface InvoiceRegisterRow {
  key: string;
  name: string;
  section: string;
  count: number;
  value: number;
  held: number;
  released: number;
  blockedReason: string | null;
}

/** An invoice with its status (ap_invoice_staging, BR-AUDIT-010). */
export interface InvoiceLine {
  id: number;
  number: string;
  unit: string;
  paymentType: string | null;
  amount: number;
  postingStatus: string;
  sentToApps: string | null;
  appsCancelStatus: string;
}

function query(params: Record<string, string | number | null | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') p.set(k, String(v));
  }
  return p.toString();
}

/** API-15-3 in PENDING: O-03 and O-06 read side. */
export const screensApi = {
  /** O-07: the CPF & e-Seva report family (BR-CPF-036). */
  cpfEseva: (page: number, size: number) =>
    api<PageResult<CatalogueEntry>>(`/v1/reports/cpf-eseva?page=${page}&size=${size}`),
  cpfMonths: (yymm: string) =>
    api<CpfMonths>(`/v1/reports/cpf-eseva/months?yymm=${encodeURIComponent(yymm)}`),
  /** O-08: the CPF report set of the SRS table. */
  cpfReconciliation: (page: number, size: number) =>
    api<PageResult<CatalogueEntry>>(`/v1/reports/cpf-reconciliation?page=${page}&size=${size}`),
  labourCatalogue: (annexure: boolean, engine: LabourEngine, page: number, size: number) =>
    api<PageResult<CatalogueEntry>>(
      `/v1/reports/labour-catalogue?${query({ annexure: String(annexure), engine, page, size })}`,
    ),
  invoiceOverview: (yymm: string, unit: number | null) =>
    api<InvoiceOverview>(`/v1/reports/invoices/overview?${query({ yymm, unit })}`),
  invoiceRegister: (yymm: string, unit: number | null, type: string, page: number, size: number) =>
    api<PageResult<InvoiceRegisterRow>>(
      `/v1/reports/invoices/register?${query({ yymm, unit, type, page, size })}`,
    ),
  invoiceLines: (yymm: string, unit: number | null, page: number, size: number) =>
    api<PageResult<InvoiceLine>>(`/v1/reports/invoices/lines?${query({ yymm, unit, page, size })}`),
};
