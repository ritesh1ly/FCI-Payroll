import { PageResult } from '@/shared/ui';
import { api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/**
 * A-07: one system parameter as approved, with a pending change if any. A setting the sources give
 * no value for is defined but not configured: value, version, effectiveFrom and changedBy are null.
 */
export interface Parameter {
  code: string;
  group: string;
  valueType: 'INTEGER' | 'BOOLEAN';
  value: number | null;
  minValue: number;
  maxValue: number;
  unit: string | null;
  enforced: boolean;
  version: number | null;
  /** The date (yyyy-mm-dd) the version in force took effect (rule_version.effective_from). */
  effectiveFrom: string | null;
  changedBy: string | null;
  pending: {
    value: number;
    requestedBy: string;
    requestedAt: string;
    /** The date the proposal takes effect once approved (the approval day if later). */
    effectiveFrom: string;
    reason: string | null;
  } | null;
}

/** The only place the configuration module talks to /api/v1/admin/parameters. */
export const parametersApi = {
  list: (page: number, size: number) =>
    api<PageResult<Parameter>>(`/v1/admin/parameters?page=${page}&size=${size}`),
  propose: (code: string, value: number, reason: string, otp?: OtpProof) =>
    api(`/v1/admin/parameters/${encodeURIComponent(code)}`, {
      method: 'PUT',
      body: { value, reason, ...(otp ? { otp } : {}) },
    }),
};
