'use client';

import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { api } from '@/shared/api';
import { useI18n } from '@/shared/i18n';

/**
 * The M-16 reference lists (GET /api/v1/reference/lists): the values of every dropdown, maintained in
 * the database through maker-checker, never hard-coded in a screen. Loaded once per session and kept
 * for five minutes; then the browser revalidates with the ETag (304 while nothing changed). The backend
 * serves them from memory on every instance.
 */
export interface ReferenceValue {
  code: string;
  label: string;
  labelHi: string | null;
  sortOrder: number | null;
  numeric: number | null;
  parent: string | null;
  /** In force today: offered in dropdowns. Values not in force still label older records. */
  active: boolean;
}

export interface ReferenceList {
  code: string;
  name: string;
  module: string | null;
  system: boolean;
  values: ReferenceValue[];
}

export interface ReferenceLists {
  asOf: string;
  lists: Record<string, ReferenceList>;
}

/** The list codes the screens use (reference_list.list_code). */
export type ListCode =
  | 'PAY_PATTERN'
  | 'PAY_SCALE_TYPE'
  | 'PENALTY_NATURE'
  | 'CITY_CLASS'
  | 'RATE_UNIT'
  | 'EMPLOYEE_RECORD_STATUS'
  | 'EMPLOYMENT_STATUS'
  | 'LIFECYCLE_EVENT_TYPE'
  | 'USER_STATUS'
  | 'USER_CATEGORY'
  | 'ROLE_TYPE'
  | 'BASE_ROLE'
  | 'SCOPE_TYPE'
  | 'ACCESS_MODE'
  | 'JOB_TYPE'
  | 'JOB_STATUS'
  | 'CLAIM_TYPE'
  | 'ELIGIBILITY_CATEGORY'
  | 'CEILING_BASIS'
  | 'ISSUING_AUTHORITY'
  | 'CIRCULAR_CATEGORY'
  | 'LANGUAGE'
  | 'OB_CORRECTION_REASON'
  | 'NOMINEE_RELATIONSHIP'
  | 'EXCEPTION_STATUS'
  | 'ORDER_COPY_DOCUMENT_TYPE'
  | 'MDP_ENTRY_STATUS'
  | 'OFF_CYCLE_CASE_TYPE'
  | 'HRMS_FALLBACK_TYPE'
  | 'SUPPLEMENTARY_PAYMENT_TYPE'
  | 'PAYROLL_QUERY_STATUS'
  | 'LE_ARREAR_STATUS'
  | 'WAGE_REVISION_FITMENT'
  | 'WAGE_REVISION_ROUNDING'
  | 'INTERFACE_RUN_STATUS'
  | 'EXCEPTION_SEVERITY'
  | 'C08_SEVERITY'
  | 'C02_PAY_PATTERN_FILTER'
  | 'C02_RESULT_FILTER'
  | 'C02_RESULT_STATUS'
  | 'LABOUR_GANG_STATUS'
  | 'LEAVE_RECOVERY_EVENT'
  | 'LEAVE_RECOVERY_STATUS'
  | 'STATUTORY_OUTPUT_FAMILY'
  | 'INVOICE_OUTPUT_TYPE'
  | 'FORM16_REGISTER_STATUS'
  | 'TAX_RETURN_QUARTER'
  | 'DA_RATE_PATTERN'
  | 'RATE_MASTER_PATTERN'
  | 'RATE_MASTER_GROUP'
  | 'LABOUR_NORM_TYPE'
  | 'NWNP_LOC_TYPE'
  | 'AUDIT_OBJECT_GROUP'
  | 'AUDIT_USER_GROUP'
  | 'COMPONENT_VIEW'
  | 'COMPONENT_TYPE'
  | 'FIELD_SOURCE'
  | 'TAXABLE_FLAG'
  | 'YES_NO'
  | 'LEAVE_FREQUENCY'
  | 'LEAVE_CARRY_FORWARD'
  | 'LEAVE_ENCASHMENT_ON'
  | 'LEAVE_CALCULATED_ON'
  | 'LEAVE_ZONE_SCOPE'
  | 'PENALTY_PAYROLL_SCOPE'
  | 'PENALTY_EXCLUDED_FROM'
  | 'ALLOWANCE_PERIODICITY'
  | 'REPORT_FAMILY'
  | 'REPORT_PAYROLL_TYPE';

export const referenceKey = ['reference'] as const;

export function useReferenceLists() {
  return useQuery({
    queryKey: referenceKey,
    queryFn: () => api<ReferenceLists>('/v1/reference/lists', { revalidate: true }),
    staleTime: 5 * 60_000,
  });
}

export interface Reference {
  /** The values of a list in force today, in order, labelled in the UI language. */
  options: (list: ListCode) => { value: string; label: string }[];
  /** The label of a code in the UI language; the code itself while loading or if unknown. */
  label: (list: ListCode, code: string | null | undefined) => string;
  isLoading: boolean;
  error: unknown;
}

export function useReference(): Reference {
  const { locale } = useI18n();
  const query = useReferenceLists();
  const lists = query.data?.lists;
  const pick = useCallback(
    (v: ReferenceValue) => (locale === 'hi' && v.labelHi ? v.labelHi : v.label),
    [locale],
  );
  const options = useCallback(
    (list: ListCode) =>
      (lists?.[list]?.values ?? []).filter((v) => v.active).map((v) => ({ value: v.code, label: pick(v) })),
    [lists, pick],
  );
  const label = useCallback(
    (list: ListCode, code: string | null | undefined) => {
      if (!code) return '';
      const value = lists?.[list]?.values.find((v) => v.code === code);
      return value ? pick(value) : code;
    },
    [lists, pick],
  );
  return useMemo(
    () => ({ options, label, isLoading: query.isLoading, error: query.error }),
    [options, label, query.isLoading, query.error],
  );
}

/** The <option>s of a list, for a <select>. */
export function ReferenceOptions({ list, only }: { list: ListCode; only?: readonly string[] }) {
  const { options } = useReference();
  return (
    <>
      {options(list)
        .filter((o) => only === undefined || only.includes(o.value))
        .map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
    </>
  );
}
