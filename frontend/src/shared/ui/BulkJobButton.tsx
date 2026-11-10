'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { usePdfDownload } from './download';
import { Problem } from './primitives';

/** The state of a bulk download job (OD-149) as its screen reads it. */
export interface BulkJob {
  jobRequestId: number;
  status: string;
  written: number;
  leftOut: number;
}

/** States in which the job is still being made (the screen asks again). */
export const OPEN_STATES = ['AWAITING_APPROVAL', 'PENDING', 'PICKED', 'RUNNING', 'PAUSED'] as const;

/** How often an open job's state is asked for again (ms). */
export const POLL_MS = 10_000;

/** What pressing the button does for a job in {@code status} (none: no job asked for yet). */
export function bulkAction(status: string | null): 'request' | 'wait' | 'download' {
  if (status === 'COMPLETED') return 'download';
  if (status !== null && (OPEN_STATES as readonly string[]).includes(status)) return 'wait';
  return 'request';
}

/**
 * The drawn Bulk PDF of O-01 and C-10 (OD-149; SRS-NF-2: the bulk download is a background job
 * with progress and a notification): the button reads the user's newest job for the filter; with
 * none (or one failed, stopped or abandoned) pressing it queues a new one, once (the button is
 * disabled while the request is in flight, so a double click sends one); while the job runs its
 * state is shown and asked for again every {@link POLL_MS}; when it is complete pressing it
 * downloads the ZIP, and the members left out (the file's manifest) are counted. A refusal is
 * shown with its reason; a 429 / 503 says when to try again.
 */
export function BulkJobButton({
  label,
  statusPath,
  requestPath,
  requestBody,
  filePath,
  fileName,
  why,
}: {
  label: string;
  /** GET: the user's newest job for the filter (204: none). */
  statusPath: string;
  /** POST: queue a new job. */
  requestPath: string;
  requestBody: unknown;
  /** GET: the finished job's ZIP. */
  filePath: (id: number) => string;
  fileName: string;
  /** Why the button cannot act (drawn disabled with it), or null. */
  why?: string | null;
}) {
  const { t } = useI18n();
  const [job, setJob] = useState<BulkJob | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false);
  const pdf = usePdfDownload();

  const refresh = useCallback(async () => {
    try {
      const found = await api<BulkJob | undefined>(statusPath, { silent: true });
      setJob(found ?? null);
    } catch (cause) {
      setError(cause);
    }
  }, [statusPath]);

  useEffect(() => {
    setJob(null);
    if (!why) void refresh();
  }, [refresh, why]);

  const open = job !== null && bulkAction(job.status) === 'wait';
  useEffect(() => {
    if (!open) return undefined;
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [open, refresh]);

  async function press() {
    if (why || sendingRef.current) return;
    setError(null);
    const action = bulkAction(job?.status ?? null);
    if (action === 'download' && job) {
      await pdf.download(filePath(job.jobRequestId), fileName);
      return;
    }
    if (action === 'wait') return;
    sendingRef.current = true;
    setSending(true);
    try {
      setJob(await api<BulkJob>(requestPath, { method: 'POST', body: requestBody }));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, {}, undefined));
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }

  const state = job ? t(`bulk.state.${job.status}`, [job.written, job.leftOut]) : null;
  const statusId = `bulk-${label.replace(/\W+/g, '-')}`;
  return (
    <>
      <button
        type="button"
        className="btn"
        disabled={Boolean(why) || sending || pdf.busy || open || pdf.retryIn > 0}
        title={why ?? (open ? t('bulk.why.running') : undefined)}
        data-why={why ?? undefined}
        aria-describedby={state ? statusId : undefined}
        aria-busy={sending || pdf.busy || undefined}
        onClick={() => void press()}
      >
        {label}
      </button>
      {state && (
        <span className="sub2" id={statusId} role="status" data-status={job?.status}>
          {state}
          {job && job.leftOut > 0 && ` ${t('bulk.leftOut', [job.leftOut])}`}
        </span>
      )}
      {pdf.retryIn > 0 && (
        <span className="sub2" role="status">
          {t('download.retryIn', [pdf.retryIn])}
        </span>
      )}
      <Problem error={error ?? pdf.error} />
    </>
  );
}
