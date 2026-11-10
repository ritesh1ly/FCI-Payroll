'use client';

import { useQuery } from '@tanstack/react-query';
import { cycleStatusApi } from '../api/cycleStatusApi';

/** D-01 use cases (plan 05 phase 05i): read only; the unit list pages on the server. */
export const cycleStatusKeys = {
  status: () => ['cycle-status'] as const,
  units: () => ['cycle-status', 'units'] as const,
};

export function useCycleStatus() {
  return useQuery({ queryKey: cycleStatusKeys.status(), queryFn: cycleStatusApi.status });
}

export function cycleUnitPage(page: number, size: number) {
  return cycleStatusApi.units(page, size);
}
