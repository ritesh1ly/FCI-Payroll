import { api } from '@/shared/api';

/** One CPF ledger of the year (E-01, SRS-17-17). */
export interface StatementLedger {
  ledgerId: number;
  memberStatus: string;
  openingEmployee: number;
  openingEmployer: number;
  openingVpf: number;
  closingEmployee: number;
  closingEmployer: number;
  closingVpf: number;
  interestCredited: number;
}

/** One posting of the year. */
export interface StatementPosting {
  yymm: string;
  paymentType: string;
  employee: number;
  employer: number;
  vpf: number;
}

export interface Statement {
  finYear: string;
  ledgers: StatementLedger[];
  postings: StatementPosting[];
}

/** The only place the CPF statement talks to /api/v1/cpf/statements. */
export const statementApi = {
  mine: (finYear?: string) =>
    api<Statement>(`/v1/cpf/statements/mine${finYear ? `?finYear=${encodeURIComponent(finYear)}` : ''}`),
};
