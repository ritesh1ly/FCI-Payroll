'use client';

import { useQuery } from '@tanstack/react-query';
import { computationApi } from '../api/computationApi';
import type { PayrollType } from '../api/labourApi';

/** C-03 to C-06 read side (Wireframes v6.1, OD-121): the pages of the gang-wise tables. */
export const computationPages = {
  gangs: (engine: PayrollType, period: string) => (page: number, size: number) =>
    computationApi.gangs(engine, period, page, size),
  incentiveGangs: (period: string) => (page: number, size: number) =>
    computationApi.incentiveGangs(period, page, size),
  recomputations: computationApi.recomputations,
  basics: computationApi.basics,
};

/** The tiles and rates of C-03 / C-04 / C-05. */
export function useComputationSummary(engine: PayrollType, period: string) {
  return useQuery({
    queryKey: ['labour-computation', engine, period, 'summary'],
    queryFn: () => computationApi.summary(engine, period),
    enabled: period !== '',
  });
}

/** The tiles of C-06. */
export function useIncentiveSummary(period: string) {
  return useQuery({
    queryKey: ['labour-incentive', period, 'summary'],
    queryFn: () => computationApi.incentiveSummary(period),
    enabled: period !== '',
  });
}
