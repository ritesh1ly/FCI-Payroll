'use client';

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { bankAdviceApi } from '../api/bankAdviceApi';

/** O-02 read side (SRS-15-34, SRS-15-41). */
export const bankAdviceKeys = { overview: ['bank-overview'] as const };

export function useBankOverview(yymm: string) {
  return useQuery({
    queryKey: [...bankAdviceKeys.overview, yymm],
    queryFn: () => bankAdviceApi.overview(yymm),
    placeholderData: keepPreviousData,
  });
}

/** OD-123: the months to choose from, read only by a role that holds O-01 VIEW. */
export function useBankPeriods(enabled: boolean) {
  return useQuery({ queryKey: ['bank-periods'], queryFn: bankAdviceApi.periods, enabled });
}
