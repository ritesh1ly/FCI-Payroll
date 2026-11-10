import { PageResult } from '@/shared/ui';
import { api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/**
 * SRS-20-5: the retention period of an audit store (retention_class). A class nobody has set is not
 * confirmed and has no period: "not decided", never a guess.
 */
export interface RetentionClass {
  code: string;
  name: string;
  months: number | null;
  indefinite: boolean;
  confirmed: boolean;
  basis: string | null;
  archivalAction: string;
  pendingApprovalId: number | null;
}

export interface RetentionProposal {
  code: string;
  months: number | null;
  indefinite: boolean;
  basis: string;
  reason: string;
}

/** The only place the configuration module talks to /api/v1/admin/retention-classes. */
export const retentionApi = {
  list: (page: number, size: number) =>
    api<PageResult<RetentionClass>>(`/v1/admin/retention-classes?page=${page}&size=${size}`),
  propose: (p: RetentionProposal, otp?: OtpProof) =>
    api<RetentionClass>(`/v1/admin/retention-classes/${encodeURIComponent(p.code)}`, {
      method: 'PUT',
      body: {
        months: p.months,
        indefinite: p.indefinite,
        basis: p.basis,
        reason: p.reason,
        ...(otp ? { otp } : {}),
      },
    }),
};
