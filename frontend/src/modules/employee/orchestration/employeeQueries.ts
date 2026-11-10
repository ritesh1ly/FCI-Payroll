'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type EmployeeFilter, employeeApi } from '../api/employeeApi';

export const employeeKeys = {
  all: ['employees'] as const,
  list: (filter: EmployeeFilter) => ['employees', filter] as const,
  counts: (filter: EmployeeFilter) => ['employees', 'counts', filter] as const,
  detail: (eis: string) => ['employee', eis] as const,
  own: ['eseva'] as const,
  headCount: ['headcount'] as const,
};

/** M-01 list, bounded by the session's data scope on the server. */
export const employeePages = {
  list: (filter: EmployeeFilter) => (page: number, size: number) => employeeApi.list(filter, page, size),
};

/** The M-01 tiles over the whole filtered scope (the status filter aside: it picks a tile's rows). */
export function useEmployeeCounts(q: string, payPattern: string, office = '') {
  const filter: EmployeeFilter = { q, payPattern, status: '', office };
  return useQuery({ queryKey: employeeKeys.counts(filter), queryFn: () => employeeApi.counts(filter) });
}

/** M-01 dossier; a record outside the scope answers 403 (not retried). */
export function useEmployee(eis: string) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: employeeKeys.detail(eis),
    queryFn: () => employeeApi.detail(eis),
    retry: false,
  });
}

/** Proposes a change (maker-checker with OTP); it reaches the record once a checker approves it. */
/** M-01 onboarding: the completed record goes to the Payroll Checker (SRS-1-11). */
export function useSubmitOnboarding(eis: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { cdaStaffCode: string | null; reason: string }) =>
      employeeApi.submitOnboarding(eis, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: employeeKeys.detail(eis) });
      void queryClient.invalidateQueries({ queryKey: employeeKeys.all });
    },
  });
}

/** E-01: the signed-in employee's own record. */
export function useOwnRecord() {
  return useQuery({ ...PERSONAL_DATA, queryKey: employeeKeys.own, queryFn: employeeApi.ownRecord });
}

/** R-01: head count per location in scope. */

/** R-01: the page fetcher of the head count (with its all-office totals). */
export const headCountPage = employeeApi.headCount;
