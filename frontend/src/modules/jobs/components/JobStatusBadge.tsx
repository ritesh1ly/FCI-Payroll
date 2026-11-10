'use client';

import { useReference } from '@/shared/reference';
import type { JobStatus } from '../api/types';

const TONE: Record<JobStatus, string> = {
  AWAITING_APPROVAL: 'p-warn',
  REJECTED: 'p-bad',
  PENDING: 'p-info',
  PICKED: 'p-info',
  RUNNING: 'p-warn',
  COMPLETED: 'p-ok',
  FAILED: 'p-bad',
  STOPPED: 'p-gray',
  PAUSED: 'p-pur',
  ABANDONED: 'p-gray',
};

/** Status of a batch job request (live wireframe pill colours). */
export function JobStatusBadge({ status }: { status: JobStatus }) {
  const reference = useReference();
  return <span className={`pill ${TONE[status] ?? 'p-gray'}`}>{reference.label('JOB_STATUS', status)}</span>;
}
