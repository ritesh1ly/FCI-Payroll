'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { calendarApi, type CalendarPeriod } from '../api/calendarApi';

/**
 * M-10 use cases (plan 05 phase 05f): the years, a year's periods and the run order are read; each
 * change is a proposal the Payroll Checker approves, so the screen re-reads to show it waiting.
 */
export const calendarKeys = {
  years: () => ['calendar', 'years'] as const,
  year: (finYear: string) => ['calendar', 'year', finYear] as const,
  runOrder: () => ['calendar', 'run-order'] as const,
  parameters: () => ['calendar', 'cycle-parameters'] as const,
};

/** M-10 Cycle Parameters panel (D-23): a read-only summary. */
export function useCycleParameters() {
  return useQuery({ queryKey: calendarKeys.parameters(), queryFn: calendarApi.cycleParameters });
}

export function useCalendarYears() {
  return useQuery({ queryKey: calendarKeys.years(), queryFn: calendarApi.years });
}

export function useCalendarYear(finYear: string) {
  return useQuery({
    queryKey: calendarKeys.year(finYear),
    queryFn: () => calendarApi.year(finYear),
    enabled: finYear !== '',
  });
}

export function useRunOrder() {
  return useQuery({ queryKey: calendarKeys.runOrder(), queryFn: calendarApi.runOrder });
}

export function useProposePeriod(finYear: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { period: CalendarPeriod; reason: string }) =>
      calendarApi.propose(finYear, [input.period], input.reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: calendarKeys.year(finYear) }),
  });
}

export function useProposeOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { order: string[]; reason: string }) =>
      calendarApi.proposeOrder(input.order, input.reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: calendarKeys.runOrder() }),
  });
}
