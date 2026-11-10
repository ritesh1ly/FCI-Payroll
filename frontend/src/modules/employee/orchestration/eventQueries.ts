'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { eventsApi, type NewTransfer, type TransferOrderCopy } from '../api/eventsApi';

/**
 * I-17 and M-11 use cases. Lists are bounded by the session's data scope on the server. I-17 is
 * read-only (SRS-1-63: no event is keyed in Payroll); a recorded transfer is a maker-checker
 * submission and changes nothing until it is approved.
 */
export const eventKeys = {
  recentEvents: ['lifecycle-events'] as const,
  events: (eis: string) => ['lifecycle-events', eis] as const,
  recentTransfers: ['transfers'] as const,
  destinations: ['transfer-destinations'] as const,
};

export function useDestinations(enabled: boolean) {
  return useQuery({
    queryKey: eventKeys.destinations,
    queryFn: eventsApi.destinations,
    enabled,
    staleTime: 300_000,
  });
}

/**
 * M-11 (SRS-16-18, SRS-G-31): the order copy is stored first, once, and the transfer names it, so
 * an OTP retry of the record sends the same copy.
 */
export function useRecordTransfer() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: async (input: { eis: string; body: NewTransfer; copy?: TransferOrderCopy }) => {
      const stored = input.copy ? await eventsApi.storeTransferOrder(input.eis, input.copy) : null;
      const body: NewTransfer = stored
        ? {
            ...input.body,
            orderAttachmentId: stored.attachmentId,
            orderProvisionalKey: stored.provisionalKey,
          }
        : input.body;
      return run((otp) => eventsApi.recordTransfer(input.eis, body, otp));
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['transfers'] }),
  });
}

/** I-17 / M-11: the page fetchers of the paged lists (docs/plan/X-pagination.md). */
export const eventPages = {
  recentEvents: eventsApi.recentEvents,
  events: (eis: string) => (page: number, size: number) => eventsApi.events(eis, page, size),
  recentTransfers: eventsApi.recentTransfers,
};
