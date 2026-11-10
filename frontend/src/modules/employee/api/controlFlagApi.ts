import { api } from '@/shared/api';
import type { FlagDocument } from './flagApi';

/** SRS-1-7: one M-01 Control flag (Hold, Stop-Payment) with its value. */
export interface ControlFlagState {
  code: 'HOLD' | 'STOP_PAYMENT';
  value: 'Y' | 'N';
  pending: boolean;
}

export interface ControlFlags {
  eis: string;
  joiningDate: string | null;
  flags: ControlFlagState[];
}

export interface ControlFlagProposal {
  flagCode: 'HOLD' | 'STOP_PAYMENT';
  value: 'Y' | 'N';
  effectiveFrom: string;
  reason: string;
}

const base = (eis: string) => `/v1/employees/${encodeURIComponent(eis)}/control-flags`;

/** The only place the employee module talks to /api/v1/employees/{eis}/control-flags. */
export const controlFlagApi = {
  flags: (eis: string) => api<ControlFlags>(base(eis)),
  propose: (eis: string, body: ControlFlagProposal, doc: FlagDocument) => {
    const form = new FormData();
    form.append('flag', new Blob([JSON.stringify(body)], { type: 'application/json' }));
    form.append('documentType', doc.documentType);
    if (doc.reference) form.append('reference', doc.reference);
    form.append('orderDate', doc.orderDate);
    form.append('issuingAuthority', doc.issuingAuthority);
    form.append('file', doc.file);
    return api<{ approvalId: number; status: string }>(base(eis), { method: 'POST', body: form });
  },
};
