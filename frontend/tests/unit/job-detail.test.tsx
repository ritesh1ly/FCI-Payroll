import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Job, JobDetail, Step } from '@/modules/jobs/api/types';
import { JobDetailView } from '@/modules/jobs/components/JobDetailView';
import { backend, problem, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * C-13 Job Detail (SRS-G-10 batch control): the request, each attempt with step progress, the
 * failure log of skipped records (paged with the shared pager), the control commands and the job's audit trail.
 */

afterEach(() => vi.unstubAllGlobals());

const ACCESS = { view: true, submit: true, control: true, halt: false };

const job = (over: Partial<Job> = {}): Job => ({
  id: 12,
  jobType: 'PAYROLL_DRY_RUN',
  params: { locationId: '3', payPeriod: '2026-07' },
  priority: 5,
  status: 'FAILED',
  source: 'UI',
  requestedBy: 'maker.delhi@fci-demo.test',
  locationId: 3,
  locationLabel: 'DO — Delhi HQ (DH001)',
  payPeriod: '2026-07',
  requestedAt: '2026-07-15T10:00:00Z',
  startedAt: '2026-07-15T10:00:05Z',
  finishedAt: '2026-07-15T10:01:10Z',
  durationSeconds: 65,
  attemptNo: 2,
  jobExecutionId: 4,
  approvalId: null,
  canDecide: false,
  readCount: 10,
  writeCount: 8,
  skipCount: 12,
  resultSummary: '8 results',
  errorMessage: 'Rate not configured',
  pendingCommand: 'RESTART',
  allowedCommands: [],
  ...over,
});

const step = (over: Partial<Step> = {}): Step => ({
  name: 'compute',
  partition: false,
  status: 'COMPLETED',
  readCount: 10,
  writeCount: 8,
  filterCount: 0,
  readSkipCount: 1,
  processSkipCount: 1,
  writeSkipCount: 0,
  commitCount: 2,
  rollbackCount: 0,
  plannedItems: null,
  startTime: '2026-07-15T10:00:05Z',
  endTime: '2026-07-15T10:01:00Z',
  exitCode: 'COMPLETED',
  exitDescription: '',
  ...over,
});

const DETAIL: JobDetail = {
  job: job(),
  attempts: [
    {
      jobExecutionId: 5,
      status: 'FAILED',
      exitCode: 'FAILED',
      exitDescription: 'boom',
      startTime: '2026-07-15T10:00:30Z',
      endTime: '2026-07-15T10:01:10Z',
      steps: [
        step({ status: 'STARTED', plannedItems: 40, readCount: 10 }),
        step({
          name: 'compute:partition1',
          partition: true,
          status: 'FAILED',
          rollbackCount: 3,
          exitCode: 'FAILED',
          exitDescription: 'Rate missing',
        }),
      ],
    },
    {
      jobExecutionId: 4,
      status: 'COMPLETED',
      exitCode: 'COMPLETED',
      exitDescription: '',
      startTime: null,
      endTime: null,
      steps: [step()],
    },
  ],
  controls: [
    {
      id: 1,
      command: 'RESTART',
      status: 'PENDING',
      requestedBy: 'maker.delhi@fci-demo.test',
      requestedAt: '2026-07-15T10:02:00Z',
      reason: 'Rate fixed',
      appliedBy: null,
      appliedAt: null,
      outcome: null,
    },
    {
      id: 2,
      command: 'STOP',
      status: 'APPLIED',
      requestedBy: 'maker.delhi@fci-demo.test',
      requestedAt: '2026-07-15T10:00:40Z',
      reason: 'Wrong period',
      appliedBy: 'batch',
      appliedAt: '2026-07-15T10:00:41Z',
      outcome: 'Stopped',
    },
  ],
  audit: [
    {
      id: 1,
      event: 'SUBMITTED',
      actor: 'maker.delhi@fci-demo.test',
      at: '2026-07-15T10:00:00Z',
      fromStatus: null,
      toStatus: 'PENDING',
      jobExecutionId: null,
      detail: null,
    },
    {
      id: 2,
      event: 'NOTE',
      actor: 'batch',
      at: '2026-07-15T10:00:01Z',
      fromStatus: null,
      toStatus: null,
      jobExecutionId: 4,
      detail: 'claimed',
    },
  ],
  skippedTotal: 12,
};

const skipped = (page: number) => ({
  items: Array.from({ length: page === 0 ? 10 : 2 }, (_, i) => ({
    id: page * 10 + i + 1,
    jobExecutionId: 5,
    step: 'compute',
    phase: 'PROCESS',
    itemKey: i === 0 ? null : `EIS-${page * 10 + i}`,
    errorClass: 'RateNotConfigured',
    errorMessage: i === 0 ? null : 'No DA rate',
    at: '2026-07-15T10:00:50Z',
  })),
  page,
  size: 10,
  total: 12,
});

describe('C-13 Job Detail', () => {
  it('SRS-G-10: shows the request, attempts with progress and partitions, commands and the audit trail', async () => {
    backend({
      'GET /v1/jobs/12': () => DETAIL,
      'GET /v1/jobs/12/skipped': (url) => skipped(url.includes('page=1') ? 1 : 0),
    });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText('Rate not configured')).toBeTruthy();
    expect(screen.getByText('8 results')).toBeTruthy();
    expect(screen.getByText(t('c13.commandPending'))).toBeTruthy();
    expect(screen.getByText(/DO — Delhi HQ \(DH001\)/)).toBeTruthy();
    expect(screen.getByRole('progressbar', { name: t('c13.progressOf', ['compute', 25]) })).toBeTruthy();
    expect(screen.getByText('↳ partition1')).toBeTruthy();
    expect(screen.getByText(`2 (${t('c13.rollbacks', [3])})`)).toBeTruthy();
    expect(screen.getByTitle('Rate missing')).toBeTruthy();
    expect(screen.getByRole('cell', { name: t('c13.command.RESTART') })).toBeTruthy();
    expect(screen.getByText('Stopped')).toBeTruthy();
    expect(screen.getByText('— → PENDING')).toBeTruthy();
    expect(screen.getByText('claimed')).toBeTruthy();
  });

  it('SRS_NF_3_theFailureLogPagesWithTheSharedPager', async () => {
    const calls = backend({
      'GET /v1/jobs/12': () => DETAIL,
      'GET /v1/jobs/12/skipped': (url) => skipped(url.includes('page=1') ? 1 : 0),
    });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText('EIS-1')).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/skipped?page=0&size=20'))).toBe(true);
    await userEvent.selectOptions(screen.getByLabelText(t('page.size')), '10');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/skipped?page=0&size=10'))).toBe(true));
    const prev = screen.getByRole('button', { name: t('page.prev') }) as HTMLButtonElement;
    expect(prev.disabled).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('page.next') }));
    expect(await screen.findByText('EIS-11')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('page.prev') }));
    expect(await screen.findByText('EIS-1')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('page.size')), '50');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/skipped?page=0&size=50'))).toBe(true));
  });

  it('a job not started yet has no attempts, no failures and no commands', async () => {
    backend({
      'GET /v1/jobs/12': () => ({
        job: job({
          status: 'PENDING',
          resultSummary: null,
          errorMessage: null,
          pendingCommand: null,
          startedAt: null,
          finishedAt: null,
          locationLabel: null,
        }),
        attempts: [],
        controls: [],
        audit: [],
        skippedTotal: 0,
      }),
    });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('c13.detail.notStarted'))).toBeTruthy();
    expect(screen.getByText(t('c13.failureLog.none'))).toBeTruthy();
    expect(screen.getByText(t('c13.commands.none'))).toBeTruthy();
  });

  it('a refused failure log is shown in its card', async () => {
    backend({
      'GET /v1/jobs/12': () => DETAIL,
      'GET /v1/jobs/12/skipped': () => problem(403, 'AUTH-403-FORBIDDEN'),
    });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
  });

  it('a job that cannot be read shows the refusal and the way back', async () => {
    const back = vi.fn();
    backend({ 'GET /v1/jobs/12': () => problem(404, 'JOB-404-NOT-FOUND') });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={back} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('error.JOB-404-NOT-FOUND'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: `← ${t('c13.back')}` }));
    expect(back).toHaveBeenCalled();
  });

  it('shows a loading line first, then goes back from the detail', async () => {
    const back = vi.fn();
    backend({ 'GET /v1/jobs/12': () => DETAIL, 'GET /v1/jobs/12/skipped': () => skipped(0) });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={back} />, ['C-13:VIEW']);
    expect(screen.getByText(t('c13.loading'))).toBeTruthy();
    await screen.findByText('Rate not configured');
    await userEvent.click(screen.getByRole('button', { name: `← ${t('c13.back')}` }));
    expect(back).toHaveBeenCalled();
  });
});
