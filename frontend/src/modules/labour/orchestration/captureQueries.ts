'use client';

import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { type CaptureFilter, captureApi } from '../api/captureApi';
import type { PayrollType } from '../api/labourApi';
import { engineType } from '../business/captureRules';

export const captureKeys = {
  all: ['labour-capture'] as const,
  of: (list: string, filter?: CaptureFilter) => ['labour-capture', list, filter ?? {}] as const,
};

/**
 * The payroll type of the open view: the entry's &engine= or Departmental, the drawn default. `chosen`
 * is the type the address or the toolbar named, which also narrows the gangs of the closed sections.
 */
export function useEngine() {
  const view = useSearchParams()?.get('engine') ?? null;
  const [selected, setSelected] = useState<PayrollType | null>(engineType(view));
  useEffect(() => setSelected(engineType(view)), [view]);
  return { type: selected ?? ('DEPT_LABOUR' as PayrollType), chosen: selected, choose: setSelected };
}

/** Every list of the screens read again after a save. */
export function useRefreshCapture() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: captureKeys.all });
}

export function useAttendanceOverview(filter: CaptureFilter) {
  return useQuery({
    queryKey: captureKeys.of('attendance-overview', filter),
    queryFn: () => captureApi.attendanceOverview(filter),
    placeholderData: keepPreviousData,
  });
}

export function useSlipOverview(filter: CaptureFilter) {
  return useQuery({
    queryKey: captureKeys.of('slip-overview', filter),
    queryFn: () => captureApi.slipOverview(filter),
    placeholderData: keepPreviousData,
  });
}

export const gangSummariesPage = (filter: CaptureFilter) => (page: number, size: number) =>
  captureApi.gangSummaries(filter, page, size);
export const daysPage = (filter: CaptureFilter) => (page: number, size: number) =>
  captureApi.days(filter, page, size);
export const leavePage = (filter: CaptureFilter) => (page: number, size: number) =>
  captureApi.leave(filter, page, size);
export const sourcesPage = (page: number, size: number) => captureApi.sources(page, size);
export const registerPage = (filter: CaptureFilter) => (page: number, size: number) =>
  captureApi.register(filter, page, size);
export const overtimePage = (filter: CaptureFilter) => (page: number, size: number) =>
  captureApi.overtime(filter, page, size);
export const gangSummariesExportUrl = captureApi.gangSummariesExportUrl;
export const registerExportUrl = captureApi.registerExportUrl;
