import { api } from '@/shared/api';

/** One bank of the disbursement summary; status: bank_advice status, or NOT_GENERATED. */
export interface BankRow {
  bank: string;
  members: number;
  net: number;
  blocked: number;
  fileFormat: string | null;
  status: string;
}

/** One line of the GL Posting Preview. */
export interface GlRow {
  head: string;
  account: string;
  debit: number | null;
  credit: number | null;
}

/** One invoice state of the month (SRS l.1713). */
export interface InvoiceState {
  status: string;
  count: number;
  sentToApps: string | null;
}

/** O-02 of a month (SRS-15-34, SRS-15-41). locked: every cycle of the month in scope is locked. */
export interface BankOverview {
  yymm: string;
  locked: boolean;
  net: number;
  banks: number;
  blocked: number;
  ebsStatus: string;
  suspended: boolean;
  bankRows: BankRow[];
  members: number;
  gl: GlRow[];
  invoices: InvoiceState[];
}

/** The only place the jobs module reads the O-02 overview; no month: the newest month of the offices. */
export const bankAdviceApi = {
  overview: (yymm: string) =>
    api<BankOverview>(yymm ? `/v1/reports/bank-advice?yymm=${yymm}` : '/v1/reports/bank-advice'),
  /** The months of O-01 (O-01 VIEW only: never called without it). */
  periods: () => api<{ yymm: string; locked: boolean }[]>('/v1/payslips/periods'),
};
