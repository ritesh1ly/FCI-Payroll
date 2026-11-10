'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { OtpProof } from '@/modules/workflow';
import { useGoverned } from '@/modules/workflow';
import { jobsApi } from '../api/jobsApi';
import type { Decision, Job, JobCommand, JobFilter } from '../api/types';
import { isLive } from '../business/jobRules';

/**
 * C-13 use cases for the components: server state through TanStack Query (cache keys, polling while
 * a job is live, invalidation after a change). Components render; they never call the API.
 */

export const jobKeys = {
  all: ['jobs'] as const,
  list: (filter: JobFilter) => ['jobs', filter] as const,
  types: ['job-types'] as const,
  detail: (id: number) => ['job', id] as const,
  skipped: (id: number, page: number) => ['job', id, 'skipped', page] as const,
  halt: ['jobs-halt'] as const,
};

/** Submit form definitions: job types, their parameters, the current period, the units in scope. */
export function useJobTypes() {
  return useQuery({ queryKey: jobKeys.types, queryFn: jobsApi.types });
}

/** History page; refreshed every 5 s while a listed job is still changing. */
export function useJobList(filter: JobFilter) {
  return useQuery({
    queryKey: jobKeys.list(filter),
    queryFn: () => jobsApi.list(filter),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      (query.state.data?.items ?? []).some((j) => isLive(j.status)) ? 5000 : false,
  });
}

/** One job with its attempts, steps, controls and audit; every 3 s while live or a command is pending. */
export function useJobDetail(id: number) {
  return useQuery({
    queryKey: jobKeys.detail(id),
    queryFn: () => jobsApi.detail(id),
    refetchInterval: (query) => {
      const job = query.state.data?.job;
      return job && (isLive(job.status) || job.pendingCommand) ? 3000 : false;
    },
  });
}

export function useSkippedItems(id: number, page: number, size: number, enabled: boolean) {
  return useQuery({
    queryKey: [...jobKeys.skipped(id, page), size],
    queryFn: () => jobsApi.skipped(id, page, size),
    enabled,
    placeholderData: keepPreviousData,
  });
}

function useRefreshJob() {
  const queryClient = useQueryClient();
  return async (id?: number) => {
    await queryClient.invalidateQueries({ queryKey: jobKeys.all });
    if (id !== undefined) await queryClient.invalidateQueries({ queryKey: jobKeys.detail(id) });
  };
}

/** Maker: queue a job (it waits for a Payroll Checker first). */
export function useSubmitJob() {
  const refresh = useRefreshJob();
  return useMutation({
    mutationFn: (input: {
      jobType: string;
      params: Record<string, string>;
      priority: number;
      reason: string;
    }) => jobsApi.submit(input.jobType, input.params, input.priority, input.reason),
    onSuccess: () => refresh(),
  });
}

/** Stop / Pause / Resume / Restart with a reason. */
export function useJobCommand(job: Pick<Job, 'id'>) {
  const refresh = useRefreshJob();
  return useMutation({
    mutationFn: (input: { command: JobCommand; reason: string }) =>
      jobsApi.control(job.id, input.command, input.reason),
    onSuccess: () => refresh(job.id),
  });
}

/** Plan 05j: propose that a failed, stopped or paused job be abandoned for good. */
export function useAbandonJob(job: Pick<Job, 'id'>) {
  const refresh = useRefreshJob();
  return useMutation({
    mutationFn: (reason: string) => jobsApi.abandon(job.id, reason),
    onSuccess: () => refresh(job.id),
  });
}

/** The kill switch, refreshed every 15 s: the banner and the Halt / Release buttons read it. */
export function useHaltState() {
  return useQuery({ queryKey: jobKeys.halt, queryFn: jobsApi.halt, refetchInterval: 15_000 });
}

/** System Administrator at HQ: halt everything now, or ask for the release. */
export function useHaltCommand() {
  const queryClient = useQueryClient();
  const refresh = useRefreshJob();
  return useMutation({
    mutationFn: (input: { action: 'HALT' | 'RELEASE'; reason: string }) =>
      input.action === 'HALT' ? jobsApi.engageHalt(input.reason) : jobsApi.requestRelease(input.reason),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: jobKeys.halt });
      await refresh();
    },
  });
}

/** Checker: approve / reject / return through the workflow endpoint (with the OTP step if asked). */
export function useJobDecision(job: Pick<Job, 'id'>) {
  const refresh = useRefreshJob();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { approvalId: number; decision: Decision; reason: string }) =>
      run((otp?: OtpProof) => jobsApi.decide(input.approvalId, input.decision, input.reason, otp)),
    onSuccess: () => refresh(job.id),
  });
}
