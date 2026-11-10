import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** SRS-1-4: one M-01 Payroll allowance flag with its value today. */
export interface FlagState {
  code: string;
  label: string;
  value: 'Y' | 'N';
  since: string | null;
  pending: boolean;
}

export interface EmployeeFlags {
  eis: string;
  joiningDate: string | null;
  flags: FlagState[];
}

/** BR-EMPM-004: one recorded version of a flag. */
export interface FlagVersion {
  code: string;
  value: 'Y' | 'N';
  effectiveFrom: string;
  effectiveTo: string | null;
  reason: string;
  changedBy: string;
  changedAt: string;
  approvalId: number | null;
}

export interface FlagProposal {
  flagCode: string;
  value: 'Y' | 'N';
  effectiveFrom: string;
  /** US-GAP-39: the end date, when the flag has one. */
  effectiveTo?: string | null;
  reason: string;
}

/** SRS-1-26 / SRS-G-30: the supporting document sent with a proposal. */
export interface FlagDocument {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

/** The proposal as one multipart request: the flag as JSON, the document's particulars, the file. */
function proposalForm(body: FlagProposal, doc: FlagDocument): FormData {
  const form = new FormData();
  form.append('flag', new Blob([JSON.stringify(body)], { type: 'application/json' }));
  form.append('documentType', doc.documentType);
  if (doc.reference) form.append('reference', doc.reference);
  form.append('orderDate', doc.orderDate);
  form.append('issuingAuthority', doc.issuingAuthority);
  form.append('file', doc.file);
  return form;
}

const base = (eis: string) => `/v1/employees/${encodeURIComponent(eis)}/allowance-flags`;

/** The only place the employee module talks to /api/v1/employees/{eis}/allowance-flags. */
export const flagApi = {
  flags: (eis: string) => api<EmployeeFlags>(base(eis)),
  history: (eis: string, page: number, size: number) =>
    api<PageResult<FlagVersion>>(`${base(eis)}/history?page=${page}&size=${size}`),
  propose: (eis: string, body: FlagProposal, doc: FlagDocument) =>
    api<{ approvalId: number; status: string }>(base(eis), {
      method: 'POST',
      body: proposalForm(body, doc),
    }),
};
